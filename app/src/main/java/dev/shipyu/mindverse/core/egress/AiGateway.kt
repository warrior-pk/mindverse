package dev.shipyu.mindverse.core.egress

import okio.ByteString

/** Message role for chat turns. */
enum class AiRole { USER, ASSISTANT }

/** One chat turn; `text` is masked at the chokepoint before it leaves the app. */
data class AiMessage(
    val role: AiRole,
    val text: String,
)

/** Binary image part for vision requests; base64-encoded at the transport boundary. */
data class AiImage(
    val mimeType: String,
    val bytes: ByteString,
)

/**
 * Text (chat) request — streaming or single-shot, optionally structured-output. The
 * chokepoint applies masking, per-feature gating, and logging (ticket 03).
 */
data class AiChatRequest(
    /** Reason code and per-feature toggle axis for this call. */
    val feature: EgressFeature,
    /** Correlation id supplied by the caller (stable across retries); lands in the log. */
    val requestId: String,
    /** Capture the payload derives from, when known (log column). */
    val captureId: String? = null,
    val systemPrompt: String? = null,
    val messages: List<AiMessage>,
    /** Structured-output schema (neutral JSON-Schema-ish [kotlinx.serialization.json.JsonObject]); null = free text. */
    val responseSchema: kotlinx.serialization.json.JsonObject? = null,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)

/**
 * Vision request: image parts + prompt, structured-output oriented (spec: VLM
 * extraction of title/summary/category/tags/entities).
 */
data class AiVisionRequest(
    val feature: EgressFeature,
    val requestId: String,
    val captureId: String? = null,
    val systemPrompt: String? = null,
    val prompt: String,
    val images: List<AiImage>,
    val responseSchema: kotlinx.serialization.json.JsonObject? = null,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)

/** A completed reply; [structured] is present when [AiChatRequest.responseSchema] was requested. */
data class AiReply(
    val text: String,
    val structured: kotlinx.serialization.json.JsonObject?,
)

/**
 * Streaming events for [AiGateway.chatStreaming]: zero or more [Delta]s followed by
 * exactly one terminal event ([Completed] or [Failed]).
 */
sealed interface AiStreamEvent {
    data class Delta(val text: String) : AiStreamEvent
    data class Completed(val reply: AiReply) : AiStreamEvent
    data class Failed(val failure: AiFailure) : AiStreamEvent
}

/**
 * Typed failures the calling feature degrades on (ADR-0002: features must degrade
 * gracefully when a toggle is off). Chokepoint denials do NOT throw — they surface as
 * [AiOutcome.Failed]/[AiStreamEvent.Failed] with [DeniedByToggle].
 */
sealed class AiFailure {
    /** Per-feature egress toggle was off; the call never left the app (0 bytes out). */
    data class DeniedByToggle(val feature: EgressFeature) : AiFailure() {
        override val shortDescription: String = "egress disabled for ${feature.name}"
    }

    /** BYOK key absent in the Keystore-encrypted store for the selected provider. */
    data class MissingKey(val providerId: String) : AiFailure() {
        override val shortDescription: String = "no key configured for $providerId"
    }

    /** Provider configuration incomplete (e.g. empty custom base URL / model). */
    data class Unconfigured(override val shortDescription: String) : AiFailure()

    data class Unreachable(override val shortDescription: String) : AiFailure()

    data class Http(val status: Int, override val shortDescription: String) : AiFailure()

    data class BadResponse(override val shortDescription: String) : AiFailure()

    /** One-line human-readable cause recorded in the egress log (FAILED rows). */
    abstract val shortDescription: String
}

/** Chokepoint result: success value or a typed, loggable failure. */
sealed interface AiOutcome<out T> {
    data class Success<T>(val value: T) : AiOutcome<T>
    data class Failed(val failure: AiFailure) : AiOutcome<Nothing>
}

/**
 * Provider-neutral AI surface — THE chokepoint every outbound AI call must go through
 * (ADR-0002, ticket 03). Raw provider/transports live in `core/ai` as `internal` types:
 * call sites cannot reach them and cannot send a byte without passing this gateway, so
 * masking, per-feature gating, and the egress log are invariants, not conventions.
 *
 * `embed` is a local no-op stub in this issue (the on-device model arrives with tickets
 * 04/07): it never egresses and never logs.
 */
interface AiGateway {
    /** Single-shot chat (streaming=false), optionally structured-output. */
    suspend fun chat(request: AiChatRequest): AiOutcome<AiReply>

    /** Streaming chat: [AiStreamEvent.Delta]s then one terminal event. Deltas arrive masked-free (responses are not masked). */
    fun chatStreaming(request: AiChatRequest): kotlinx.coroutines.flow.Flow<AiStreamEvent>

    /** Single-shot vision, structured-output oriented. */
    suspend fun vision(request: AiVisionRequest): AiOutcome<AiReply>

    /**
     * Local no-op stub until ticket 04/07 wires the on-device MiniLM embedding:
     * returns [AiOutcome.Success] with null, never egresses, never logs.
     */
    suspend fun embed(text: String): AiOutcome<FloatArray?>
}
