package dev.shipyu.mindverse.ui.detail

import dev.shipyu.mindverse.core.data.FixedClock
import dev.shipyu.mindverse.core.data.MemoryDraft
import dev.shipyu.mindverse.core.data.MemoryItemDraft
import dev.shipyu.mindverse.core.data.MemoryRepository
import dev.shipyu.mindverse.core.data.TestDatabaseFactory
import dev.shipyu.mindverse.core.data.db.MemoryItemKind
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.data.db.PinEntity
import dev.shipyu.mindverse.core.data.db.PinKind
import dev.shipyu.mindverse.core.data.ingest.CaptureIngestionService
import dev.shipyu.mindverse.core.data.ingest.IngestOutcome
import dev.shipyu.mindverse.core.data.ingest.ProposedCapture
import dev.shipyu.mindverse.core.data.db.CaptureKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Memory detail seam (ticket 06): card + entities + source context + pins. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryDetailControllerTest {

    private lateinit var db: MindverseDatabase
    private lateinit var clock: FixedClock
    private lateinit var repository: MemoryRepository
    private lateinit var controller: MemoryDetailController

    @Before
    fun setUp() {
        db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
        clock = FixedClock()
        repository = MemoryRepository(db, clock)
        controller = MemoryDetailController(
            db = db,
            repository = repository,
        )
    }

    @Test
    fun `detail state carries card, entities, source capture and pins`() = runBlocking {
        val outcome = CaptureIngestionService(db, clock).ingest(
            ProposedCapture(
                kind = CaptureKind.IMAGE,
                blobPath = "/tmp/shot.png",
                sourceApp = "com.shots",
                deepLink = "https://example.com/shot",
                contentHash = "a".repeat(64),
                phash = 1L,
                capturedAt = 1L,
                mimeType = "image/png",
            ),
        ) as IngestOutcome.Stored
        val memory = repository.createMemory(
            MemoryDraft(
                sourceCaptureIds = listOf(outcome.capture.id),
                title = "Flight",
                summary = "To Tokyo",
                category = "travel",
                tags = listOf("flight"),
                extractedText = null,
                items = listOf(
                    MemoryItemDraft(kind = MemoryItemKind.TICKET, text = "ABC123", ticketCode = "ABC123"),
                ),
            ),
        )
        db.pinDao().insert(
            PinEntity(
                id = "pin-1",
                memoryId = memory.id,
                captureId = outcome.capture.id,
                kind = PinKind.TICKET_DEPARTURE,
                expiresAtMs = 99_000L,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        controller.load(memory.id)
        val state = controller.state.value
        assertTrue(state.exists)
        assertEquals("Flight", state.memory!!.title)
        assertEquals(listOf(MemoryItemKind.TICKET), state.items.map { it.kind })
        assertEquals("com.shots", state.sourceCapture!!.sourceApp)
        assertEquals(listOf("pin-1"), state.pins.map { it.id })
        assertEquals("/tmp/shot.png", state.thumbnailPath)
    }

    @Test
    fun `missing memory renders the gone state`() = runBlocking {
        controller.load("nope")
        assertFalse(controller.state.value.exists)
    }
}
