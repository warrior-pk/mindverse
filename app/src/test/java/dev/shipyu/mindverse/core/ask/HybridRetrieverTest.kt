package dev.shipyu.mindverse.core.ask

import dev.shipyu.mindverse.core.data.FixedClock
import dev.shipyu.mindverse.core.data.MemoryDraft
import dev.shipyu.mindverse.core.data.MemoryRepository
import dev.shipyu.mindverse.core.data.TestDatabaseFactory
import dev.shipyu.mindverse.core.data.db.CaptureKind
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.data.ingest.CaptureIngestionService
import dev.shipyu.mindverse.core.data.ingest.IngestOutcome
import dev.shipyu.mindverse.core.data.ingest.ProposedCapture
import dev.shipyu.mindverse.core.embed.FeatureHashingEmbeddingEngine
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Ticket 07 acceptance seam: golden queries over a fixture DB return the intended Memory
 * in the top-3 for paraphrase phrasing, and metadata filters narrow correctly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HybridRetrieverTest {

    private lateinit var db: MindverseDatabase
    private lateinit var clock: FixedClock
    private lateinit var repository: MemoryRepository
    private lateinit var ingestion: CaptureIngestionService
    private lateinit var retriever: HybridRetriever

    @Before
    fun setUp() {
        db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
        clock = FixedClock(2_000_000_000_000L) // ~2033 baseline; fixtures back-date from here
        repository = MemoryRepository(db, clock)
        ingestion = CaptureIngestionService(db, clock)
        retriever = HybridRetriever(
            db = db,
            repository = repository,
            embedding = FeatureHashingEmbeddingEngine(384),
            clock = clock,
            zone = ZoneId.of("UTC"),
        )
    }

    private suspend fun seed(
        hash: String,
        title: String,
        summary: String,
        extracted: String?,
        category: String?,
        tags: List<String>,
        sourceApp: String?,
        ageDays: Long,
    ): String {
        val createdAt = clock.millis() - ageDays * DAY
        val outcome = ingestion.ingest(
            ProposedCapture(
                kind = CaptureKind.TEXT, blobPath = null, sourceApp = sourceApp, deepLink = null,
                contentHash = hash, phash = null, capturedAt = createdAt, mimeType = "text/plain",
            ),
        ) as IngestOutcome.Stored
        val memory = repository.createMemory(
            MemoryDraft(
                sourceCaptureIds = listOf(outcome.capture.id),
                title = title,
                summary = summary,
                category = category,
                tags = tags,
                extractedText = extracted,
                embedding = null,
            ),
        )
        // The repository stamps creation time at insert; back-date the row so recency
        // decay and time filters see the fixture's intended age.
        db.memoryDao().update(memory.copy(createdAt = createdAt, updatedAt = createdAt))
        return memory.id
    }

    @Test
    fun `golden paraphrase queries hit the intended memory in top 3`() = runBlocking {
        val flight = seed(
            hash = "a".repeat(64),
            title = "Flight AI 302 to Tokyo",
            summary = "Boarding pass, seat 14A, gate 12, departs 09:40",
            extracted = "PNR ABC123 seat 14A gate 12 departs 2026-11-02T09:40:00Z",
            category = "travel",
            tags = listOf("flight", "tokyo"),
            sourceApp = "com.airline.app",
            ageDays = 1,
        )
        seed(
            hash = "b".repeat(64),
            title = "Pasta recipe",
            summary = "Carbonara with guanciale and pecorino",
            extracted = "Boil spaghetti, fry guanciale, mix egg yolks and pecorino",
            category = "food",
            tags = listOf("recipe", "pasta"),
            sourceApp = "com.notes.app",
            ageDays = 2,
        )
        seed(
            hash = "c".repeat(64),
            title = "Dentist appointment",
            summary = "Tuesday 4pm with Dr. Rao, clinic near the station",
            extracted = null,
            category = "health",
            tags = emptyList(),
            sourceApp = "com.calendar.app",
            ageDays = 3,
        )

        // Paraphrase phrasings that never appear verbatim in the fixture text.
        val queries = listOf(
            "what gate is my tokyo flight?",
            "where do I board the plane to japan",
            "which seat did I get on ai 302",
        )
        for (query in queries) {
            val hits = retriever.retrieve(query, topK = 3)
            assertTrue("no hits for: $query", hits.isNotEmpty())
            assertEquals("query missed: $query", flight, hits.take(3).first().memory.id)
        }
    }

    @Test
    fun `metadata filters narrow the candidate pool`() = runBlocking {
        val recipe = seed(
            hash = "a".repeat(64),
            title = "Pasta recipe", summary = "Carbonara", extracted = null,
            category = "food", tags = emptyList(), sourceApp = "com.notes.app", ageDays = 1,
        )
        seed(
            hash = "b".repeat(64),
            title = "Flight AI 302 to Tokyo", summary = "Boarding pass", extracted = null,
            category = "travel", tags = emptyList(), sourceApp = "com.airline.app", ageDays = 40,
        )

        val byCategory = retriever.retrieve("what category:food carbonara", topK = 5)
        assertEquals(listOf(recipe), byCategory.map { it.memory.id })

        val byApp = retriever.retrieve("notes app:com.notes.app", topK = 5)
        assertEquals(listOf(recipe), byApp.map { it.memory.id })

        val byRecency = retriever.retrieve("everything this week", topK = 5)
        assertEquals(listOf(recipe), byRecency.map { it.memory.id })
    }

    @Test
    fun `no matches yields an empty ranking`() = runBlocking {
        assertTrue(retriever.retrieve("quantum submarine", topK = 5).isEmpty())
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
