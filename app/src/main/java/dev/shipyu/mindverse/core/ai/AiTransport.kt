package dev.shipyu.mindverse.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** Wire role for chat turns; system prompt travels in [TransportRequest.systemPrompt]. */
internal enum class TransportRole { USER, ASSISTANT }

internal data class TransportImage(
    val mimeType: String,
    /** Base64 (no wrapping) of the image bytes. */
    val base64Data: String,
)

internal data class TransportMessage(
    val role: TransportRole,
    val text: String,
    val images: List<TransportImage>,
)

/**
 * Normalized wire request the transports translate into provider envelopes. Callers see
 * only [dev.shipyu.mindverse.core.egress.AiGateway] types — this class is internal so the
 * "compile only via the chokepoint" invariant holds.
 */
internal data class TransportRequest(
    /** Model identifier as selected in provider settings (e.g. "gemini-flash-latest"). */
    val model: String,
    /**
     * Endpoint base override; null = the transport's provider default. The OpenAI-compatible
     * transport requires it (user-supplied custom base URL, spec R4-6); Gemini ignores it.
     */
    val baseUrl: String? = null,
    val systemPrompt: String?,
    val messages: List<TransportMessage>,
    /** Neutral JSON-Schema-ish structured-output schema; null = free text. */
    val jsonSchema: JsonObject?,
    val temperature: Double?,
    val maxOutputTokens: Int?,
)

internal data class TransportCompletion(
    val text: String,
    /**
     * Serialized outbound request body size in bytes — the honest egress `bytes_out`
     * (envelope included, i.e. everything the provider received).
     */
    val bytesOut: Int,
)

internal sealed interface TransportEvent {
    data class Delta(val text: String) : TransportEvent
    data class Completed(val completion: TransportCompletion) : TransportEvent
}

/** Typed wire failure; the chokepoint maps these onto its log + [AiFailure] surface. */
internal class AiTransportException internal constructor(
    val failure: TransportFailure,
    val bytesOut: Int,
    val where: String,
) : Exception()

internal sealed class TransportFailure {
    data class Http(val status: Int, val bodySummary: String?) : TransportFailure()

    data class Unreachable(val cause: Exception) : TransportFailure()

    data class Malformed(val detail: String) : TransportFailure()
}

/**
 * SPI for the two provider transports (Gemini + OpenAI-compatible). `internal` by
 * design: nobody outside the module can import wire types; every public path funnels
 * through core.egress's [dev.shipyu.mindverse.core.egress.AiGateway].
 */
internal interface AiTransport {
    /** Stable label for the egress log's provider column. */
    val providerId: String

    suspend fun chat(request: TransportRequest, apiKey: String): TransportCompletion

    /** Deltas flow; the last event is always [TransportEvent.Completed] (aggregated). */
    fun chatStreaming(request: TransportRequest, apiKey: String): Flow<TransportEvent>
}
