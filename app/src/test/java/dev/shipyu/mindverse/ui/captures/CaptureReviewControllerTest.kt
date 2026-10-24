package dev.shipyu.mindverse.ui.captures

import dev.shipyu.mindverse.core.data.FixedClock
import dev.shipyu.mindverse.core.data.MemoryDraft
import dev.shipyu.mindverse.core.data.MemoryRepository
import dev.shipyu.mindverse.core.data.TestDatabaseFactory
import dev.shipyu.mindverse.core.data.db.CaptureProcessingState
import dev.shipyu.mindverse.core.data.db.CaptureKind
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.data.ingest.CaptureIngestionService
import dev.shipyu.mindverse.core.data.ingest.IngestOutcome
import dev.shipyu.mindverse.core.data.ingest.ProposedCapture
import dev.shipyu.mindverse.core.pipeline.PipelineScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Capture review seam (ticket 06): edit source context, confirm/retry, discard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureReviewControllerTest {

    private lateinit var db: MindverseDatabase
    private lateinit var clock: FixedClock
    private lateinit var repository: MemoryRepository
    private lateinit var scheduler: RecordingScheduler
    private lateinit var controller: CaptureReviewController
    private lateinit var captureId: String

    @Before
    fun setUp() = runBlocking {
        db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
        clock = FixedClock()
        repository = MemoryRepository(db, clock)
        scheduler = RecordingScheduler()
        controller = CaptureReviewController(
            db = db,
            repository = repository,
            scheduler = scheduler,
            clock = clock,
        )
        val outcome = CaptureIngestionService(db, clock).ingest(
            ProposedCapture(
                kind = CaptureKind.SHARE,
                blobPath = null,
                sourceApp = "com.example.app",
                deepLink = "https://example.com/a",
                contentHash = "a".repeat(64),
                phash = null,
                capturedAt = 1L,
                mimeType = "text/plain",
            ),
        ) as IngestOutcome.Stored
        captureId = outcome.capture.id
    }

    @Test
    fun `source context edits persist`() = runBlocking {
        controller.load(captureId)
        controller.updateSource("com.other.app", "https://example.com/b")
        val capture = db.captureDao().getCapture(captureId)!!
        assertEquals("com.other.app", capture.sourceApp)
        assertEquals("https://example.com/b", capture.deepLink)

        // Blank edits degrade to nulls, not empty strings.
        controller.updateSource("   ", " ")
        val cleared = db.captureDao().getCapture(captureId)!!
        assertEquals(null, cleared.sourceApp)
        assertEquals(null, cleared.deepLink)
    }

    @Test
    fun `confirm re-queues a dead-lettered capture and resets its state`() = runBlocking {
        db.captureDao().setProcessingState(
            captureId, CaptureProcessingState.DEAD_LETTER, 5, "gave up", clock.millis(),
        )
        controller.load(captureId)
        controller.confirm()

        assertEquals(listOf(captureId), scheduler.enqueued)
        val capture = db.captureDao().getCapture(captureId)!!
        assertEquals(CaptureProcessingState.PENDING, capture.processingState)
        assertEquals(0, capture.processingAttempts)
        assertEquals(null, capture.processingError)
    }

    @Test
    fun `confirm on a finished capture does not re-queue`() = runBlocking {
        db.captureDao().setProcessingState(captureId, CaptureProcessingState.DONE, 1, null, clock.millis())
        controller.load(captureId)
        controller.confirm()
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `discard tombstones the capture and its Memory`() = runBlocking {
        val memory = repository.createMemory(
            MemoryDraft(
                sourceCaptureIds = listOf(captureId),
                title = "T", summary = "S", category = null, tags = emptyList(), extractedText = null,
            ),
        )
        db.captureDao().setMemoryId(captureId, memory.id, clock.millis())

        controller.load(captureId)
        controller.discard()

        assertTrue(db.captureDao().getCapture(captureId)!!.deleted)
        assertTrue(repository.getMemory(memory.id)!!.deleted)
    }

    private class RecordingScheduler : PipelineScheduler {
        val enqueued = mutableListOf<String>()
        override fun enqueue(captureId: String) {
            enqueued += captureId
        }
    }
}
