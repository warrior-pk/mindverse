package dev.shipyu.mindverse.core.pipeline

import dev.shipyu.mindverse.core.data.MemoryRepository
import dev.shipyu.mindverse.core.data.db.CaptureEntity
import dev.shipyu.mindverse.core.data.db.CaptureKind
import dev.shipyu.mindverse.core.data.db.CaptureProcessingState
import dev.shipyu.mindverse.core.data.db.MemoryItemKind
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.data.db.MindverseDatabaseSchema
import dev.shipyu.mindverse.core.data.db.PinEntity
import dev.shipyu.mindverse.core.data.db.PinKind
import dev.shipyu.mindverse.core.egress.AiChatRequest
import dev.shipyu.mindverse.core.egress.AiFailure
import dev.shipyu.mindverse.core.egress.AiGateway
import dev.shipyu.mindverse.core.egress.AiImage
import dev.shipyu.mindverse.core.egress.AiMessage
import dev.shipyu.mindverse.core.egress.AiOutcome
import dev.shipyu.mindverse.core.egress.AiReply
import dev.shipyu.mindverse.core.egress.AiRole
import dev.shipyu.mindverse.core.egress.AiVisionRequest
import dev.shipyu.mindverse.core.egress.EgressFeature
import dev.shipyu.mindverse.core.embed.EmbeddingEngine
import java.io.File
import java.io.IOException
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import okio.ByteString.Companion.toByteString

/**
 * Outcome of one pipeline stage, mapped by the workers to WorkManager results.
 * - [Success] — stage done (or nothing to do: capture deleted/absent).
 * - [Retry] — transient failure; WorkManager's exponential backoff re-runs the stage.
 * - [DeadLetter] — retries exhausted or a terminal failure (toggle off, bad key,
 *   unsupported kind, unreadable content); Capture is flagged DEAD_LETTER with the reason.
 */
sealed interface StageOutcome {
    data class Success(val memoryId: String? = null) : StageOutcome
    data object Retry : StageOutcome
    data class DeadLetter(val reason: String) : StageOutcome
}

/**
 * The capture → Memory pipeline (ticket 04), split into the two stages WorkManager runs:
 *
 * 1. [extract] — one egress-chokepoint call (vision for images/PDFs, chat for
 *    text/URL passthrough) producing the structured Memory + typed Entity rows, the Pin
 *    probe (ticket fields with a departure → departure-derived TTL), and the capture →
 *    memory linkage. Never makes a second API call on a model miss.
 * 2. [embed] — on-device embedding of title+summary+tags+extracted text via the local
 *    [EmbeddingEngine], written to `memory_vec`, then the capture is marked DONE.
 *
 * Idempotency: extraction skips when a Memory already exists for the capture (crash-window
 * retry), embedding re-runs harmlessly (upsert by memory id), Pin probing checks existing
 * pins first. Dead-lettering never deletes the Capture row or blob.
 */
class PipelineProcessor(
    private val db: MindverseDatabase,
    private val repository: MemoryRepository,
    private val ai: AiGateway,
    private val embedding: EmbeddingEngine,
    private val chip: PipelineChip,
    private val clock: Clock,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {

    suspend fun extract(captureId: String, attempt: Int): StageOutcome {
        val capture = db.captureDao().getCapture(captureId) ?: return StageOutcome.Success()
        if (capture.deleted) return StageOutcome.Success()

        // Crash-window idempotency: a Memory may exist even if the linkage stamp was lost.
        capture.memoryId?.let { return StageOutcome.Success(it) }
        repository.memoryForCapture(captureId)?.let { existing ->
            db.captureDao().setMemoryId(capture.id, existing.id, clock.millis())
            ensurePin(capture, existing.id)
            return StageOutcome.Success(existing.id)
        }

        if (capture.kind == CaptureKind.AUDIO) {
            return deadLetter(capture, attempt, "voice memo processing ships in M2")
        }

        chip.processing(capture.id)
        db.captureDao().setProcessingState(
            capture.id,
            CaptureProcessingState.PROCESSING,
            attempt + 1,
            null,
            clock.millis(),
        )

        val reply = when (val attemptResult = requestExtraction(capture)) {
            is Attempt.Terminal -> return deadLetter(capture, attempt, attemptResult.reason)
            is Attempt.Failed -> return failureOutcome(capture, attempt, attemptResult.failure)
            is Attempt.Reply -> attemptResult.reply
        }

        val structured = reply.structured
        if (structured == null) {
            return failureOutcome(
                capture,
                attempt,
                AiFailure.BadResponse("model did not return usable JSON for the extraction schema"),
            )
        }
        val result = ExtractionParser.parse(structured)
            ?: return failureOutcome(
                capture,
                attempt,
                AiFailure.BadResponse("structured output carried neither title nor summary"),
            )

        val memory = try {
            repository.createMemory(result.toDraft(capture.id))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return failureOutcome(
                capture,
                attempt,
                AiFailure.BadResponse("storing the Memory failed: ${error.message.orEmpty().take(160)}"),
            )
        }
        db.captureDao().setMemoryId(capture.id, memory.id, clock.millis())
        ensurePin(capture, memory.id)
        return StageOutcome.Success(memory.id)
    }

    suspend fun embed(captureId: String, attempt: Int): StageOutcome {
        val capture = db.captureDao().getCapture(captureId) ?: return StageOutcome.Success()
        if (capture.deleted) return StageOutcome.Success()
        val memoryId = capture.memoryId
            ?: return deadLetter(capture, attempt, "no extracted Memory to index")
        if (capture.processingState == CaptureProcessingState.DONE) return StageOutcome.Success(memoryId)

        val memory = repository.getMemory(memoryId)
            ?: return deadLetter(capture, attempt, "Memory row $memoryId is missing")

        chip.indexing(capture.id, memoryId)
        val vector = try {
            embedding.embed(embeddingText(memory))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return failureOutcome(
                capture,
                attempt,
                AiFailure.BadResponse("on-device embedding failed: ${error.message.orEmpty().take(160)}"),
            )
        }
        if (vector.size != MindverseDatabaseSchema.EMBEDDING_DIMENSION) {
            return deadLetter(
                capture,
                attempt,
                "embedding dimension mismatch: ${vector.size} != ${MindverseDatabaseSchema.EMBEDDING_DIMENSION}",
            )
        }

        try {
            repository.updateMemory(memory, newEmbedding = vector)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return failureOutcome(
                capture,
                attempt,
                AiFailure.BadResponse("writing the vector index failed: ${error.message.orEmpty().take(160)}"),
            )
        }

        db.captureDao().setProcessingState(
            capture.id,
            CaptureProcessingState.DONE,
            attempt + 1,
            null,
            clock.millis(),
        )
        chip.done(capture.id, memoryId)
        return StageOutcome.Success(memoryId)
    }

    // --- extraction request ----------------------------------------------------------

    private sealed interface Attempt<out T> {
        data class Reply<T>(val reply: T) : Attempt<T>
        data class Failed(val failure: AiFailure) : Attempt<Nothing>
        /** Non-retryable local condition (missing blob, unreadable content). */
        data class Terminal(val reason: String) : Attempt<Nothing>
    }

    private suspend fun requestExtraction(capture: CaptureEntity): Attempt<AiReply> {
        val visual = capture.mimeType?.startsWith("image/") == true ||
            capture.mimeType == PDF_MIME ||
            (capture.mimeType == null && capture.kind == CaptureKind.IMAGE)

        val outcome = if (visual) {
            val blob = readBlob(capture)
                ?: return Attempt.Terminal("capture blob is missing: ${capture.blobPath}")
            if (blob.size > MAX_VISION_BYTES) {
                return Attempt.Terminal("capture blob is too large for extraction (${blob.size} bytes)")
            }
            ai.vision(
                AiVisionRequest(
                    feature = EgressFeature.MEMORY_EXTRACTION,
                    requestId = "${capture.id}:extract",
                    captureId = capture.id,
                    systemPrompt = ExtractionPrompts.SYSTEM,
                    prompt = ExtractionPrompts.imagePrompt(capture.deepLink, capture.sourceApp),
                    images = listOf(
                        AiImage(
                            mimeType = capture.mimeType ?: IMAGE_MIME_FALLBACK,
                            bytes = blob.toByteString(),
                        ),
                    ),
                    responseSchema = ExtractionContract.json,
                    temperature = EXTRACTION_TEMPERATURE,
                ),
            )
        } else {
            val body = readText(capture)
                ?: return Attempt.Terminal("capture has no readable content")
            ai.chat(
                AiChatRequest(
                    feature = EgressFeature.MEMORY_EXTRACTION,
                    requestId = "${capture.id}:extract",
                    captureId = capture.id,
                    systemPrompt = ExtractionPrompts.SYSTEM,
                    messages = listOf(
                        AiMessage(
                            role = AiRole.USER,
                            text = ExtractionPrompts.textPrompt(body, capture.deepLink, capture.sourceApp),
                        ),
                    ),
                    responseSchema = ExtractionContract.json,
                    temperature = EXTRACTION_TEMPERATURE,
                ),
            )
        }

        return when (outcome) {
            is AiOutcome.Success -> Attempt.Reply(outcome.value)
            is AiOutcome.Failed -> Attempt.Failed(outcome.failure)
        }
    }

    private fun readBlob(capture: CaptureEntity): ByteArray? {
        val path = capture.blobPath ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return try {
            file.readBytes()
        } catch (error: IOException) {
            null
        }
    }

    /**
     * Text/URL passthrough: the stored text blob wins; otherwise the URL itself is the
     * content (M1 does not scrape pages — that would add egress outside the chokepoint).
     */
    private fun readText(capture: CaptureEntity): String? {
        val path = capture.blobPath
        if (path != null) {
            val file = File(path)
            if (file.exists()) {
                val text = runCatching { file.readText() }.getOrNull()
                if (!text.isNullOrBlank()) return text
            }
        }
        return capture.deepLink?.takeUnless { it.isBlank() }
    }

    private fun embeddingText(memory: dev.shipyu.mindverse.core.data.db.MemoryEntity): String = buildString {
        appendLine(memory.title)
        appendLine(memory.summary)
        if (memory.tags.isNotEmpty()) appendLine(memory.tags.joinToString(" "))
        memory.extractedText?.let { appendLine(it) }
    }.trim()

    // --- pin probe -------------------------------------------------------------------

    /**
     * Ticket fields with a departure time produce a Pin whose TTL ends
     * [PIN_GRACE_AFTER_DEPARTURE_MS] after departure (spec: Pins are time-sensitive and
     * expire; M3 renders/extinguishes them). Idempotent per Memory.
     */
    private suspend fun ensurePin(capture: CaptureEntity, memoryId: String) {
        val pinDao = db.pinDao()
        if (pinDao.forMemory(memoryId).isNotEmpty()) return
        val departure = db.memoryItemDao().itemsFor(memoryId)
            .firstOrNull { it.kind == MemoryItemKind.TICKET && it.timestampStartMs != null }
            ?.timestampStartMs
            ?: return
        val now = clock.millis()
        pinDao.insert(
            PinEntity(
                id = idFactory(),
                memoryId = memoryId,
                captureId = capture.id,
                kind = PinKind.TICKET_DEPARTURE,
                expiresAtMs = departure + PIN_GRACE_AFTER_DEPARTURE_MS,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    // --- failure handling ------------------------------------------------------------

    private suspend fun failureOutcome(
        capture: CaptureEntity,
        attempt: Int,
        failure: AiFailure,
    ): StageOutcome = if (failure.isTransient()) {
        retryOrDeadLetter(capture, attempt, failure.shortDescription)
    } else {
        // User-actionable (toggle off, missing key, bad config): retrying cannot help.
        deadLetter(capture, attempt, failure.shortDescription)
    }

    private suspend fun retryOrDeadLetter(
        capture: CaptureEntity,
        attempt: Int,
        reason: String,
    ): StageOutcome {
        if (attempt + 1 >= MAX_ATTEMPTS) {
            return deadLetter(capture, attempt, "gave up after $MAX_ATTEMPTS attempts: $reason")
        }
        db.captureDao().setProcessingState(
            capture.id,
            CaptureProcessingState.PENDING,
            attempt + 1,
            reason,
            clock.millis(),
        )
        return StageOutcome.Retry
    }

    private suspend fun deadLetter(
        capture: CaptureEntity,
        attempt: Int,
        reason: String,
    ): StageOutcome {
        db.captureDao().setProcessingState(
            capture.id,
            CaptureProcessingState.DEAD_LETTER,
            attempt + 1,
            reason,
            clock.millis(),
        )
        chip.failed(capture.id, reason)
        return StageOutcome.DeadLetter(reason)
    }

    companion object {
        /** Attempts (first run included) before a Capture is dead-lettered. */
        const val MAX_ATTEMPTS = 5

        /** Ticket Pin lives one day past departure, then the TTL lapses. */
        const val PIN_GRACE_AFTER_DEPARTURE_MS = 24 * 60 * 60 * 1000L

        private const val EXTRACTION_TEMPERATURE = 0.2
        private const val MAX_VISION_BYTES = 8 * 1024 * 1024
        private const val PDF_MIME = "application/pdf"
        private const val IMAGE_MIME_FALLBACK = "image/png"
    }
}

/**
 * Retry classification (ticket 04: "deterministic retry/backoff"). Transient = may succeed
 * on retry (network/5xx/rate limit/flaky model output). Terminal = the user must act
 * (egress toggle off, missing/invalid key, incomplete provider config, 4xx).
 */
internal fun AiFailure.isTransient(): Boolean = when (this) {
    is AiFailure.Http -> status == 408 || status == 429 || status in 500..599
    is AiFailure.Unreachable -> true
    is AiFailure.BadResponse -> true
    is AiFailure.DeniedByToggle, is AiFailure.MissingKey, is AiFailure.Unconfigured -> false
}
