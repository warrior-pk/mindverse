package dev.shipyu.mindverse.core.pipeline

import dev.shipyu.mindverse.core.data.FixedClock
import dev.shipyu.mindverse.core.data.MemoryRepository
import dev.shipyu.mindverse.core.data.TestDatabaseFactory
import dev.shipyu.mindverse.core.data.db.CaptureProcessingState
import dev.shipyu.mindverse.core.data.db.CaptureKind
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.data.db.PinKind
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
import dev.shipyu.mindverse.core.embed.FeatureHashingEmbeddingEngine
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Ticket 04 acceptance: a synthetic Capture (text + image fixtures) round-trips through
 * extract → embed into an indexed Memory without UI; the failure path leaves a
 * dead-lettered Capture with visible state and no data loss.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PipelineProcessorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: MindverseDatabase
    private lateinit var clock: FixedClock
    private lateinit var repository: MemoryRepository
    private lateinit var gateway: FakeAiGateway
    private lateinit var chip: RecordingChip
    private lateinit var processor: PipelineProcessor
    private lateinit var scheduler: RecordingScheduler
    private lateinit var controller: PipelineController
    private val embedding = FeatureHashingEmbeddingEngine(384)

    private fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = TestDatabaseFactory.inMemoryDatabase(context)
        clock = FixedClock()
        repository = MemoryRepository(db, clock)
        gateway = FakeAiGateway()
        chip = RecordingChip()
        processor = PipelineProcessor(
            db = db,
            repository = repository,
            ai = gateway,
            embedding = embedding,
            chip = chip,
            clock = clock,
        )
        scheduler = RecordingScheduler()
        controller = PipelineController(CaptureIngestionService(db, clock), scheduler)
    }

    @Test
    fun `text capture round-trips into a searchable Memory with a ticket Pin`() = runBlocking {
        setUp()
        val blob = folder.newFile("boarding-pass.txt").apply {
            writeText("Boarding pass PNR ABC123 seat 14A gate 12 departs 2026-11-02T09:40:00Z")
        }
        val outcome = controller.submit(
            proposed(blob = blob, kind = CaptureKind.TEXT, mime = "text/plain", hash = "a".repeat(64)),
        ) as IngestOutcome.Stored
        assertEquals(listOf(outcome.capture.id), scheduler.enqueued)

        gateway.chatReplies += AiOutcome.Success(AiReply("", structured(TICKET_JSON)))
        val extract = processor.extract(outcome.capture.id, attempt = 0)
        assertTrue(extract is StageOutcome.Success)
        val memoryId = (extract as StageOutcome.Success).memoryId!!
        assertNotNull(gateway.chatRequests.single().responseSchema)
        assertEquals(outcome.capture.id, gateway.chatRequests.single().captureId)

        val memory = repository.getMemory(memoryId)!!
        assertEquals("Flight AI 302 to Tokyo", memory.title)
        assertEquals("travel", memory.category)
        assertEquals(listOf("flight", "tokyo"), memory.tags)
        assertEquals(1, repository.itemsFor(memoryId).count { it.kind.name == "TICKET" })

        // Pin probe: departure-derived TTL (departure + 24h grace).
        val pin = db.pinDao().forMemory(memoryId).single()
        assertEquals(PinKind.TICKET_DEPARTURE, pin.kind)
        assertEquals(1_793_612_400_000L + PipelineProcessor.PIN_GRACE_AFTER_DEPARTURE_MS, pin.expiresAtMs)
        assertEquals(1, db.pinDao().countActive(clock.millis()))

        // Capture links to its Memory and is still PROCESSING until embed finishes.
        val afterExtract = db.captureDao().getCapture(outcome.capture.id)!!
        assertEquals(memoryId, afterExtract.memoryId)
        assertEquals(CaptureProcessingState.PROCESSING, afterExtract.processingState)

        val embed = processor.embed(outcome.capture.id, attempt = 0)
        assertEquals(memoryId, (embed as StageOutcome.Success).memoryId)
        assertEquals(CaptureProcessingState.DONE, db.captureDao().getCapture(outcome.capture.id)!!.processingState)
        assertEquals(1, db.vectorCount())

        // Indexed: FTS finds it by wording, vec finds it by its own embedding.
        assertEquals(listOf(memoryId), repository.searchByFullText("tokyo").map { it.id })
        assertEquals(listOf(memoryId), repository.searchByFullText("boarding").map { it.id })
        val queryVector = embedding.embed("boarding pass flight tokyo gate 12")
        assertEquals(memoryId, repository.searchByVector(queryVector, k = 1).single().memoryId)

        assertEquals(
            listOf("processing:${outcome.capture.id}", "indexing:${outcome.capture.id}:$memoryId", "done:${outcome.capture.id}:$memoryId"),
            chip.events,
        )
    }

    @Test
    fun `image capture goes through the vision path`() = runBlocking {
        setUp()
        val blob = folder.newFile("screenshot.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) }
        val outcome = controller.submit(
            proposed(blob = blob, kind = CaptureKind.IMAGE, mime = "image/png", hash = "b".repeat(64)),
        ) as IngestOutcome.Stored

        gateway.visionReplies += AiOutcome.Success(AiReply("", structured(TICKET_JSON)))
        val extract = processor.extract(outcome.capture.id, attempt = 0)
        assertTrue(extract is StageOutcome.Success)

        assertTrue(gateway.chatRequests.isEmpty())
        val request = gateway.visionRequests.single()
        assertEquals("image/png", request.images.single().mimeType)
        assertEquals("${outcome.capture.id}:extract", request.requestId)
        assertNotNull(repository.getMemory((extract as StageOutcome.Success).memoryId!!))
    }

    @Test
    fun `persistent transient failure dead-letters without data loss`() = runBlocking {
        setUp()
        val blob = folder.newFile("note.txt").apply { writeText("Some note") }
        val outcome = controller.submit(
            proposed(blob = blob, kind = CaptureKind.TEXT, mime = "text/plain", hash = "c".repeat(64)),
        ) as IngestOutcome.Stored

        repeat(PipelineProcessor.MAX_ATTEMPTS) {
            gateway.chatReplies += AiOutcome.Failed(AiFailure.Unreachable("offline"))
        }
        var last: StageOutcome? = null
        for (attempt in 0 until PipelineProcessor.MAX_ATTEMPTS) {
            last = processor.extract(outcome.capture.id, attempt)
            if (attempt < PipelineProcessor.MAX_ATTEMPTS - 1) assertTrue(last is StageOutcome.Retry)
        }
        assertTrue(last is StageOutcome.DeadLetter)

        val capture = db.captureDao().getCapture(outcome.capture.id)!!
        assertEquals(CaptureProcessingState.DEAD_LETTER, capture.processingState)
        assertEquals(PipelineProcessor.MAX_ATTEMPTS, capture.processingAttempts)
        assertTrue(capture.processingError!!.contains("gave up after ${PipelineProcessor.MAX_ATTEMPTS}"))
        assertTrue("blob must survive dead-lettering", blob.exists())
        assertTrue(db.captureDao().listDeadLettered().map { it.id }.contains(capture.id))

        // No Memory, no vector — but the Capture row is intact.
        assertTrue(repository.listActive().isEmpty())
        assertEquals(0, db.vectorCount())
        assertTrue(chip.events.any { it.startsWith("failed:${capture.id}:") })
    }

    @Test
    fun `keyless toggle-off creates local Memory instead of dead-lettering`() = runBlocking {
        setUp()
        val outcome = controller.submit(
            proposed(blob = null, kind = CaptureKind.SHARE, mime = null, hash = "d".repeat(64), deepLink = "https://example.com"),
        ) as IngestOutcome.Stored

        gateway.chatReplies += AiOutcome.Failed(AiFailure.DeniedByToggle(dev.shipyu.mindverse.core.egress.EgressFeature.MEMORY_EXTRACTION))
        val result = processor.extract(outcome.capture.id, attempt = 0)
        assertTrue(result is StageOutcome.Success)
        val memoryId = (result as StageOutcome.Success).memoryId!!
        val memory = repository.getMemory(memoryId)!!
        assertTrue(memory.tags.contains("local-only"))
        assertEquals(memoryId, db.captureDao().getCapture(outcome.capture.id)!!.memoryId)
        // Embed still runs on-device so the local Memory is indexed.
        val embed = processor.embed(outcome.capture.id, attempt = 0)
        assertEquals(memoryId, (embed as StageOutcome.Success).memoryId)
        assertEquals(CaptureProcessingState.DONE, db.captureDao().getCapture(outcome.capture.id)!!.processingState)
    }

    @Test
    fun `keyless fallback with nothing readable still dead-letters`() = runBlocking {
        setUp()
        val outcome = controller.submit(
            proposed(blob = null, kind = CaptureKind.SHARE, mime = null, hash = "d2".repeat(32), deepLink = null),
        ) as IngestOutcome.Stored

        gateway.chatReplies += AiOutcome.Failed(AiFailure.MissingKey("gemini-key"))
        val result = processor.extract(outcome.capture.id, attempt = 0)
        assertTrue(result is StageOutcome.DeadLetter)
        val capture = db.captureDao().getCapture(outcome.capture.id)!!
        assertEquals(CaptureProcessingState.DEAD_LETTER, capture.processingState)
    }

    @Test
    fun `transient failure retries then succeeds`() = runBlocking {
        setUp()
        val outcome = controller.submit(
            proposed(blob = null, kind = CaptureKind.SHARE, mime = null, hash = "e".repeat(64), deepLink = "https://example.com/article"),
        ) as IngestOutcome.Stored

        gateway.chatReplies += AiOutcome.Failed(AiFailure.Http(503, "unavailable"))
        val first = processor.extract(outcome.capture.id, attempt = 0)
        assertTrue(first is StageOutcome.Retry)
        val waiting = db.captureDao().getCapture(outcome.capture.id)!!
        assertEquals(CaptureProcessingState.PENDING, waiting.processingState)
        assertTrue(waiting.processingError!!.contains("unavailable"))

        gateway.chatReplies += AiOutcome.Success(AiReply("", structured("""{"title":"Article","summary":"Read later","tags":[]}""")))
        val second = processor.extract(outcome.capture.id, attempt = 1)
        assertTrue(second is StageOutcome.Success)
        assertEquals(1, repository.listActive().size)
    }

    @Test
    fun `exact duplicate is never queued`() = runBlocking {
        setUp()
        val proposed = proposed(blob = null, kind = CaptureKind.SHARE, mime = null, hash = "f".repeat(64), deepLink = "https://example.com")
        assertTrue(controller.submit(proposed) is IngestOutcome.Stored)
        assertTrue(controller.submit(proposed) is IngestOutcome.ExactDuplicate)
        assertEquals(1, scheduler.enqueued.size)
    }

    // --- fixtures + fakes -------------------------------------------------------------

    private fun proposed(
        blob: File?,
        kind: CaptureKind,
        mime: String?,
        hash: String,
        deepLink: String? = null,
    ) = ProposedCapture(
        kind = kind,
        blobPath = blob?.absolutePath,
        sourceApp = "com.example.app",
        deepLink = deepLink,
        contentHash = hash,
        phash = null,
        capturedAt = 1_000L,
        mimeType = mime,
    )

    private fun structured(json: String) = Json.parseToJsonElement(json).jsonObject

    private class RecordingScheduler : PipelineScheduler {
        val enqueued = mutableListOf<String>()
        override fun enqueue(captureId: String) {
            enqueued += captureId
        }
    }

    private class RecordingChip : PipelineChip {
        val events = mutableListOf<String>()
        override fun processing(captureId: String) {
            events += "processing:$captureId"
        }

        override fun indexing(captureId: String, memoryId: String) {
            events += "indexing:$captureId:$memoryId"
        }

        override fun done(captureId: String, memoryId: String) {
            events += "done:$captureId:$memoryId"
        }

        override fun failed(captureId: String, reason: String) {
            events += "failed:$captureId:$reason"
        }
    }

    private class FakeAiGateway : AiGateway {
        val chatRequests = mutableListOf<AiChatRequest>()
        val visionRequests = mutableListOf<AiVisionRequest>()
        val chatReplies = ArrayDeque<AiOutcome<AiReply>>()
        val visionReplies = ArrayDeque<AiOutcome<AiReply>>()

        override suspend fun chat(request: AiChatRequest): AiOutcome<AiReply> {
            chatRequests += request
            return chatReplies.removeFirstOrNull()
                ?: AiOutcome.Failed(AiFailure.BadResponse("no fake chat reply queued"))
        }

        override suspend fun vision(request: AiVisionRequest): AiOutcome<AiReply> {
            visionRequests += request
            return visionReplies.removeFirstOrNull()
                ?: AiOutcome.Failed(AiFailure.BadResponse("no fake vision reply queued"))
        }

        override fun chatStreaming(request: AiChatRequest): Flow<AiStreamEvent> = emptyFlow()

        override suspend fun embed(text: String): AiOutcome<FloatArray?> = AiOutcome.Success(null)
    }

    private companion object {
        val TICKET_JSON = """
            {
              "title": "Flight AI 302 to Tokyo",
              "summary": "Boarding pass departs 09:40 from gate 12",
              "category": "travel",
              "extractedText": "PNR ABC123 seat 14A gate 12",
              "tags": ["flight", "tokyo"],
              "entities": {"ticket": {"pnr": "ABC123", "seat": "14A", "gate": "12", "departure": "2026-11-02T09:40:00Z"}}
            }
        """.trimIndent()
    }
}
