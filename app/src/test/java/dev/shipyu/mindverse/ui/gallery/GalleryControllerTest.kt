package dev.shipyu.mindverse.ui.gallery

import dev.shipyu.mindverse.core.data.FixedClock
import dev.shipyu.mindverse.core.data.MemoryDraft
import dev.shipyu.mindverse.core.data.MemoryItemDraft
import dev.shipyu.mindverse.core.data.MemoryRepository
import dev.shipyu.mindverse.core.data.TestDatabaseFactory
import dev.shipyu.mindverse.core.data.db.CaptureKind
import dev.shipyu.mindverse.core.data.db.MemoryItemKind
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.data.ingest.CaptureIngestionService
import dev.shipyu.mindverse.core.data.ingest.IngestOutcome
import dev.shipyu.mindverse.core.data.ingest.ProposedCapture
import dev.shipyu.mindverse.core.pipeline.PipelineScheduler
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Gallery seam (ticket 06): cards, filters, and the near-duplicate merge prompt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryControllerTest {

    private lateinit var db: MindverseDatabase
    private lateinit var clock: FixedClock
    private lateinit var repository: MemoryRepository
    private lateinit var ingestion: CaptureIngestionService
    private lateinit var controller: GalleryController

    @Before
    fun setUp() {
        db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
        clock = FixedClock(1_000_000L)
        repository = MemoryRepository(db, clock)
        ingestion = CaptureIngestionService(db, clock)
        controller = GalleryController(
            db = db,
            repository = repository,
            clock = clock,
            zone = ZoneId.of("UTC"),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private suspend fun addMemory(
        hash: String,
        title: String,
        category: String?,
        sourceApp: String?,
        createdAt: Long = clock.millis(),
    ): String {
        clock.millis = createdAt
        val outcome = ingestion.ingest(
            ProposedCapture(
                kind = CaptureKind.SHARE,
                blobPath = null,
                sourceApp = sourceApp,
                deepLink = null,
                contentHash = hash,
                phash = null,
                capturedAt = createdAt,
                mimeType = "text/plain",
            ),
        ) as IngestOutcome.Stored
        val memory = repository.createMemory(
            MemoryDraft(
                sourceCaptureIds = listOf(outcome.capture.id),
                title = title,
                summary = "$title summary",
                category = category,
                tags = emptyList(),
                extractedText = null,
            ),
        )
        return memory.id
    }

    @Test
    fun `cards render with source context and filters narrow the list`() = runBlocking {
        val now = 5_000_000L
        val travelId = addMemory("a".repeat(64), "Flight to Tokyo", "travel", "com.airline.app", now - DAY)
        addMemory("b".repeat(64), "Dinner recipe", "food", "com.notes.app", now - 30 * DAY)
        clock.millis = now

        controller.refresh()
        assertEquals(2, controller.state.value.cards.size)

        controller.setCategory("travel")
        assertEquals(listOf(travelId), controller.state.value.cards.map { it.memoryId })

        controller.setCategory(null)
        controller.setSourceApp("com.notes.app")
        assertEquals(listOf("Dinner recipe"), controller.state.value.cards.map { it.title })

        controller.setSourceApp(null)
        controller.setTimeRange(TimeRange.LAST_7_DAYS)
        assertEquals(listOf(travelId), controller.state.value.cards.map { it.memoryId })

        controller.setTimeRange(TimeRange.ALL)
        assertEquals(2, controller.state.value.cards.size)
        assertEquals(listOf("food", "travel"), controller.state.value.categories)
    }

    @Test
    fun `empty gallery shows an empty state without filters`() = runBlocking {
        controller.refresh()
        val state = controller.state.value
        assertFalse(state.loading)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `near-duplicates queue for the merge prompt and resolve both ways`() = runBlocking {
        clock.millis = 1000L
        val first = ingestion.ingest(
            ProposedCapture(
                kind = CaptureKind.IMAGE, blobPath = null, sourceApp = "com.shots", deepLink = null,
                contentHash = "1".repeat(64), phash = 42L, capturedAt = 1L, mimeType = "image/png",
            ),
        ) as IngestOutcome.Stored
        val second = ingestion.ingest(
            ProposedCapture(
                kind = CaptureKind.IMAGE, blobPath = null, sourceApp = "com.shots", deepLink = null,
                contentHash = "2".repeat(64), phash = 42L, capturedAt = 2L, mimeType = "image/png",
            ),
        ) as IngestOutcome.NearDuplicate

        controller.refresh()
        val pair = controller.state.value.nearDups.single()
        assertEquals(second.stored.id, pair.capture.id)
        assertEquals(first.capture.id, pair.similarTo.id)

        // Keep both: the prompt clears, nothing is touched.
        controller.keepBoth(second.stored.id)
        assertTrue(controller.state.value.nearDups.isEmpty())
        assertNull(db.captureDao().getCapture(second.stored.id)!!.nearDuplicateOf)
    }

    @Test
    fun `removing a duplicate tombstones the capture and its linked Memory`() = runBlocking {
        clock.millis = 1000L
        ingestion.ingest(
            ProposedCapture(
                kind = CaptureKind.IMAGE, blobPath = null, sourceApp = "com.shots", deepLink = null,
                contentHash = "1".repeat(64), phash = 7L, capturedAt = 1L, mimeType = "image/png",
            ),
        ) as IngestOutcome.Stored
        val dup = ingestion.ingest(
            ProposedCapture(
                kind = CaptureKind.IMAGE, blobPath = null, sourceApp = "com.shots", deepLink = null,
                contentHash = "2".repeat(64), phash = 7L, capturedAt = 2L, mimeType = "image/png",
            ),
        ) as IngestOutcome.NearDuplicate
        val memory = repository.createMemory(
            MemoryDraft(
                sourceCaptureIds = listOf(dup.stored.id),
                title = "Shot", summary = "Shot", category = null, tags = emptyList(), extractedText = null,
            ),
        )
        db.captureDao().setMemoryId(dup.stored.id, memory.id, clock.millis())

        controller.discardDuplicate(dup.stored.id)
        assertTrue(controller.state.value.nearDups.isEmpty())
        assertTrue(db.captureDao().getCapture(dup.stored.id)!!.deleted)
        assertTrue(repository.getMemory(memory.id)!!.deleted)
        assertEquals(0, repository.listActive().size)
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
