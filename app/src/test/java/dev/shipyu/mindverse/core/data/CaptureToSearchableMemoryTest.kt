package dev.shipyu.mindverse.core.data

import dev.shipyu.mindverse.core.data.db.CaptureKind
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.data.ingest.CaptureIngestionService
import dev.shipyu.mindverse.core.data.ingest.IngestOutcome
import dev.shipyu.mindverse.core.data.ingest.ProposedCapture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tracer bullet for ticket 02's acceptance row: inserting a Capture + a synthesized
 * Memory exercises the FTS query, the vector load (KNN), and the dedupe boundary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureToSearchableMemoryTest {
    private fun db(): MindverseDatabase =
        TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())

    @Test
    fun captureAndSynthesizedMemoryAreSearchableByFtsAndVector() {
        runBlocking {
            val db = db()
            val clock = FixedClock()
            val ingestion = CaptureIngestionService(db, clock)
            val outcome = ingestion.ingest(
                ProposedCapture(
                    kind = CaptureKind.SHARE,
                    blobPath = null,
                    sourceApp = "com.example.browser",
                    deepLink = "https://example.com/flight",
                    contentHash = "a".repeat(64),
                    phash = null,
                    capturedAt = 500L,
                ),
            )
            val capture = (outcome as IngestOutcome.Stored).capture

            val repository = MemoryRepository(db, clock)
            val embedding = FloatArray(MindverseDatabaseSchemaHolder.DIM) { index -> index * 0.001f }
            val memory = repository.createMemory(
                MemoryDraft(
                    sourceCaptureIds = listOf(capture.id),
                    title = "Flight to Tokyo",
                    summary = "Boarding pass for tomorrow morning",
                    category = "travel",
                    tags = listOf("flight", "tokyo", "boarding"),
                    extractedText = "Gate 12 Seat 14A departure 09:40",
                    embedding = embedding,
                ),
            )

            // FTS5 full-text: title, tags and extracted text all match; bm25 ranks first hit.
            assertEquals(listOf(memory.id), repository.searchByFullText("tokyo").map { it.id })
            assertEquals(listOf(memory.id), repository.searchByFullText("boarding pass").map { it.id })
            assertEquals(listOf(memory.id), repository.searchByFullText("gate 12").map { it.id })
            assertEquals(emptyList<String>(), repository.searchByFullText("unrelated word").map { it.id })

            // Vector: the card's own embedding is its own nearest neighbor at distance 0.
            val hits = repository.searchByVector(embedding, k = 5)
            assertTrue(hits.isNotEmpty())
            assertEquals(memory.id, hits.first().memoryId)
            assertEquals(0.0, hits.first().distance, 1e-6)

            // Typed Entity rows round-trip with the card.
            assertTrue(repository.itemsFor(memory.id).isEmpty())
        }
    }
}

/** Shared constants for tests (separate from prod code so prod can't depend on tests). */
object MindverseDatabaseSchemaHolder {
    const val DIM = 384
}
