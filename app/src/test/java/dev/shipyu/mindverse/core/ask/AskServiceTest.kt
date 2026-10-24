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
import dev.shipyu.mindverse.core.egress.AiChatRequest
import dev.shipyu.mindverse.core.egress.AiFailure
import dev.shipyu.mindverse.core.egress.AiGateway
import dev.shipyu.mindverse.core.egress.AiOutcome
import dev.shipyu.mindverse.core.egress.AiReply
import dev.shipyu.mindverse.core.egress.AiStreamEvent
import dev.shipyu.mindverse.core.egress.AiVisionRequest
import dev.shipyu.mindverse.core.egress.EgressFeature
import dev.shipyu.mindverse.core.embed.FeatureHashingEmbeddingEngine
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Ask streaming seam (ticket 07): grounded prompt with numbered sources, per-block
 * citation chips, and the keyless paragraph fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AskServiceTest {

    private lateinit var db: MindverseDatabase
    private lateinit var repository: MemoryRepository
    private lateinit var gateway: FakeGateway
    private lateinit var service: AskService
    private lateinit var flightId: String
    private lateinit var recipeId: String

    @Before
    fun setUp() = runBlocking {
        db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
        val clock = FixedClock(2_000_000_000_000L)
        repository = MemoryRepository(db, clock)
        val ingestion = CaptureIngestionService(db, clock)
        flightId = seed(ingestion, "Flight AI 302 to Tokyo", "Boarding pass gate 12", "a".repeat(64))
        recipeId = seed(ingestion, "Pasta recipe", "Carbonara with pecorino", "b".repeat(64))
        gateway = FakeGateway()
        service = AskService(
            retriever = HybridRetriever(db, repository, FeatureHashingEmbeddingEngine(384), clock, ZoneId.of("UTC")),
            ai = gateway,
            idFactory = { "req-1" },
        )
    }

    private suspend fun seed(ingestion: CaptureIngestionService, title: String, summary: String, hash: String): String {
        val outcome = ingestion.ingest(
            ProposedCapture(
                kind = CaptureKind.TEXT, blobPath = null, sourceApp = "com.notes", deepLink = null,
                contentHash = hash, phash = null, capturedAt = 1L, mimeType = "text/plain",
            ),
        ) as IngestOutcome.Stored
        val memory = repository.createMemory(
            MemoryDraft(
                sourceCaptureIds = listOf(outcome.capture.id),
                title = title,
                summary = summary,
                category = null,
                tags = emptyList(),
                extractedText = null,
            ),
        )
        return memory.id
    }

    @Test
    fun `streaming answer resolves citation markers into per-block chips`() = runBlocking {
        gateway.streamEvents = listOf(
            AiStreamEvent.Delta("Your flight boards at gate 12 [1]."),
            AiStreamEvent.Delta("\n\nThe recipe uses pecorino [2]."),
            AiStreamEvent.Completed(AiReply("", null)),
        )

        val emissions = service.ask("tokyo flight gate and pecorino recipe").toList()
        val first = emissions.first()
        assertTrue(first.streaming)
        assertEquals(2, first.sources.size)
        assertEquals(flightId, first.sources.first().memoryId)

        val final = emissions.last()
        assertFalse(final.streaming)
        assertEquals("Your flight boards at gate 12 [1].\n\nThe recipe uses pecorino [2].", final.answerText)
        assertEquals(2, final.blocks.size)
        assertEquals(listOf(flightId), final.blocks[0].citations.map { it.memoryId })
        assertEquals(listOf(recipeId), final.blocks[1].citations.map { it.memoryId })

        // Grounding prompt carries the numbered Memory context and the strict rule.
        val request = gateway.request!!
        assertEquals(EgressFeature.ASK, request.feature)
        val prompt = request.messages.single().text
        assertTrue(prompt.contains("[1] Flight AI 302 to Tokyo"))
        assertTrue(prompt.contains("[2] Pasta recipe"))
        assertTrue(AskService.SYSTEM_PROMPT.contains("STRICT GROUNDING"))
    }

    @Test
    fun `keyless failure degrades to a cited local fallback`() = runBlocking {
        gateway.streamEvents = listOf(
            AiStreamEvent.Failed(AiFailure.MissingKey("gemini")),
        )

        val final = service.ask("tokyo flight gate").toList().last()
        assertFalse(final.streaming)
        assertNotNull(final.fallbackReason)
        assertTrue(final.answerText.contains("Flight AI 302 to Tokyo"))
        assertTrue(final.blocks.any { block -> block.citations.any { it.memoryId == flightId } })
    }

    @Test
    fun `no matching memories short-circuits without an AI call`() = runBlocking {
        val final = service.ask("quantum submarine").toList().last()
        assertEquals("No matching Memories found on this device.", final.answerText)
        assertEquals(null, gateway.request)
    }

    private class FakeGateway : AiGateway {
        var streamEvents: List<AiStreamEvent> = emptyList()
        var request: AiChatRequest? = null

        override suspend fun chat(request: AiChatRequest): AiOutcome<AiReply> =
            AiOutcome.Failed(AiFailure.BadResponse("unused"))

        override suspend fun vision(request: AiVisionRequest): AiOutcome<AiReply> =
            AiOutcome.Failed(AiFailure.BadResponse("unused"))

        override fun chatStreaming(request: AiChatRequest): Flow<AiStreamEvent> {
            this.request = request
            return flowOf(*streamEvents.toTypedArray())
        }

        override suspend fun embed(text: String): AiOutcome<FloatArray?> = AiOutcome.Success(null)
    }
}
