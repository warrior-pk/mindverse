package dev.shipyu.mindverse.core.egress

import android.util.Base64
import dev.shipyu.mindverse.core.ai.AiProvider
import dev.shipyu.mindverse.core.ai.AiTransport
import dev.shipyu.mindverse.core.ai.AiTransportException
import dev.shipyu.mindverse.core.ai.ProviderSettingsStore
import dev.shipyu.mindverse.core.ai.TransportCompletion
import dev.shipyu.mindverse.core.ai.TransportEvent
import dev.shipyu.mindverse.core.ai.TransportFailure
import dev.shipyu.mindverse.core.ai.TransportImage
import dev.shipyu.mindverse.core.ai.TransportMessage
import dev.shipyu.mindverse.core.ai.TransportRequest
import dev.shipyu.mindverse.core.ai.TransportRole
import dev.shipyu.mindverse.core.data.db.EgressLogDao
import dev.shipyu.mindverse.core.data.db.EgressLogEntity
import dev.shipyu.mindverse.core.data.db.EgressOutcome
import dev.shipyu.mindverse.core.data.settings.EncryptedSettingsStore
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * THE egress chokepoint (ADR-0002, ticket 03): the single path any outbound AI call
 * takes; DI exposes it as the public [AiGateway] interface only. For every call it:
 *  1. checks the per-feature toggle — denied calls short-circuit with
 *     [AiFailure.DeniedByToggle] before anything leaves the app (logged DENIED, 0 bytes);
 *  2. validates provider configuration (empty BYOK base URL/model → typed
 *     [AiFailure.Unconfigured], logged FAILED);
 *  3. resolves the BYOK key from the Keystore-encrypted store (missing key → typed
 *     failure, logged FAILED);
 *  4. masks outbound text through [PiiMasker] (typed placeholders; detected classes
 *     recorded — mask runs only after all gates pass, so heart-beat failure rows carry
 *     no payload classes truthfully: nothing was processed yet);
 *  5. dispatches to the selected provider transport (raw wire types stay internal in
 *     `core/ai` — call sites cannot reach them);
 *  6. writes one `egress_log` row on every terminal state — success, failure, denial,
 *     and user-cancelled streams (ADR-0002: every egress attempt is user-visible).
 *
 * Not handled here (by design): retries/backoff belong to the WorkManager pipeline
 * (ticket 04); the on-device embedding model replaces the [embed] stub in 04/07.
 */
internal class EgressAiGateway(
    private val transports: Map<AiProvider, AiTransport>,
    private val providerSettings: ProviderSettingsStore,
    private val keyStore: EncryptedSettingsStore,
    private val toggles: EgressToggles,
    private val logDao: EgressLogDao,
    private val clock: Clock,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : AiGateway {

    /** Stateless; one instance for the process. */
    private val masker = PiiMasker()

    override suspend fun chat(request: AiChatRequest): AiOutcome<AiReply> {
        val ready = when (val gate = gate(request.feature, request.requestId, request.captureId)) {
            is Gate.Blocked -> return AiOutcome.Failed(gate.failure)
            is Gate.Ready -> gate
        }
        val classes = LinkedHashSet<PayloadClass>()
        val wireRequest = chatWire(request, ready, classes)
        val key = resolveKey(ready.provider)
            ?: return missingKeyOutcome(request.toCallRef(), ready)
        return dispatch(request.toCallRef(), ready, wireRequest, key, classes)
    }

    override suspend fun vision(request: AiVisionRequest): AiOutcome<AiReply> {
        val ready = when (val gate = gate(request.feature, request.requestId, request.captureId)) {
            is Gate.Blocked -> return AiOutcome.Failed(gate.failure)
            is Gate.Ready -> gate
        }
        val classes = LinkedHashSet<PayloadClass>()
        val wireRequest = visionWire(request, ready, classes)
        val key = resolveKey(ready.provider)
            ?: return missingKeyOutcome(request.toCallRef(), ready)
        return dispatch(request.toCallRef(), ready, wireRequest, key, classes)
    }

    override fun chatStreaming(request: AiChatRequest): Flow<AiStreamEvent> = flow {
        val ready = when (val gate = gate(request.feature, request.requestId, request.captureId)) {
            is Gate.Blocked -> {
                emit(AiStreamEvent.Failed(gate.failure))
                return@flow
            }
            is Gate.Ready -> gate
        }
        val classes = LinkedHashSet<PayloadClass>()
        val wireRequest = chatWire(request, ready, classes)
        val key = resolveKey(ready.provider)
        if (key == null) {
            val failure = AiFailure.MissingKey(ready.provider.storedKeyId)
            logRow(request.toCallRef(), ready.transport, emptySet(), bytesOut = 0, EgressOutcome.FAILED, failure.shortDescription)
            emit(AiStreamEvent.Failed(failure))
            return@flow
        }

        emitAll(
            ready.transport.chatStreaming(wireRequest, key)
                .map { event ->
                    when (event) {
                        is TransportEvent.Delta -> AiStreamEvent.Delta(event.text)
                        is TransportEvent.Completed -> streamTerminal(
                            event.completion,
                            request.toCallRef(),
                            request.responseSchema,
                            ready.transport,
                            classes,
                        )
                    }
                }
                .catch { throwable ->
                    if (throwable is CancellationException) {
                        // ADR-0002: partial bytes may already have left the app, so the
                        // attempt must be user-visible. The wire payload size is
                        // transport-owned, so bytes_out is 0 (unknown) with an explicit
                        // cancellation note rather than a made-up figure.
                        logRow(
                            request.toCallRef(),
                            ready.transport,
                            classes,
                            bytesOut = 0,
                            EgressOutcome.FAILED,
                            error = "cancelled by user before completion (bytes out unknown)",
                        )
                        throw throwable
                    }
                    val wire = throwable as? AiTransportException ?: unknownTransportException(throwable)
                    logRow(request.toCallRef(), ready.transport, classes, wire.bytesOut, EgressOutcome.FAILED, describe(wire))
                    emit(AiStreamEvent.Failed(wire.failure.toAiFailure()))
                },
        )
    }

    /**
     * Local no-op stub (ticket 03): the on-device embedding model arrives with tickets
     * 04/07. Never egresses, never logs.
     */
    override suspend fun embed(text: String): AiOutcome<FloatArray?> = AiOutcome.Success(null)

    // --- chokepoint internals ---------------------------------------------------------

    private sealed interface Gate {
        data class Ready(
            val transport: AiTransport,
            val provider: AiProvider,
            val model: String,
            val baseUrl: String?,
        ) : Gate

        data class Blocked(val failure: AiFailure) : Gate
    }

    /** The log-facing identity of one call: correlation + reason + capture linkage. */
    private data class CallRef(
        val requestId: String,
        val captureId: String?,
        val feature: EgressFeature,
    )

    private fun AiChatRequest.toCallRef() = CallRef(requestId, captureId, feature)

    private fun AiVisionRequest.toCallRef() = CallRef(requestId, captureId, feature)

    private fun chatWire(
        request: AiChatRequest,
        ready: Gate.Ready,
        classes: MutableSet<PayloadClass>,
    ): TransportRequest = TransportRequest(
        model = ready.model,
        baseUrl = ready.baseUrl,
        systemPrompt = maskNullable(request.systemPrompt, classes),
        messages = request.messages.map { message ->
            TransportMessage(
                role = message.role.toTransport(),
                text = mask(message.text, classes),
                images = emptyList(),
            )
        },
        jsonSchema = request.responseSchema,
        temperature = request.temperature,
        maxOutputTokens = request.maxOutputTokens,
    )

    private fun visionWire(
        request: AiVisionRequest,
        ready: Gate.Ready,
        classes: MutableSet<PayloadClass>,
    ): TransportRequest = TransportRequest(
        model = ready.model,
        baseUrl = ready.baseUrl,
        systemPrompt = maskNullable(request.systemPrompt, classes),
        messages = listOf(
            TransportMessage(
                role = TransportRole.USER,
                text = mask(request.prompt, classes),
                images = request.images.map { image ->
                    TransportImage(
                        mimeType = image.mimeType,
                        base64Data = Base64.encodeToString(image.bytes.toByteArray(), Base64.NO_WRAP),
                    )
                },
            ),
        ),
        jsonSchema = request.responseSchema,
        temperature = request.temperature,
        maxOutputTokens = request.maxOutputTokens,
    )

    /**
     * Gate sequence: toggle → configuration. Both blocked states log a row (DENIED /
     * FAILED, 0 bytes out) before returning the typed failure — the Privacy screen shows
     * every attempted call, even ones that never left the app.
     */
    private suspend fun gate(
        feature: EgressFeature,
        requestId: String,
        captureId: String?,
    ): Gate {
        val ref = CallRef(requestId, captureId, feature)
        val settings = providerSettings.current()
        val provider = settings.provider
        val transport = transports[provider]
            ?: error("no transport wired for provider ${provider.name}")

        if (toggles.states().first()[feature] == false) {
            logRow(ref, transport, emptySet(), bytesOut = 0, EgressOutcome.DENIED, error = null)
            return Gate.Blocked(AiFailure.DeniedByToggle(feature))
        }

        val configured = when (provider) {
            AiProvider.GEMINI -> settings.geminiModel.isNotBlank()
            AiProvider.OPENAI_COMPATIBLE ->
                settings.openAiCompatibleBaseUrl.isNotBlank() && settings.openAiCompatibleModel.isNotBlank()
        }
        if (!configured) {
            val failure = AiFailure.Unconfigured("provider ${provider.name} is not configured (model/base URL)")
            logRow(ref, transport, emptySet(), bytesOut = 0, EgressOutcome.FAILED, failure.shortDescription)
            return Gate.Blocked(failure)
        }
        val (model, baseUrl) = when (provider) {
            AiProvider.GEMINI -> settings.geminiModel to null
            AiProvider.OPENAI_COMPATIBLE -> settings.openAiCompatibleModel to settings.openAiCompatibleBaseUrl
        }
        return Gate.Ready(transport, provider, model, baseUrl)
    }

    private suspend fun resolveKey(provider: AiProvider): String? =
        keyStore.getProviderKey(provider.storedKeyId)
            ?.decodeToString()
            ?.trim()
            .takeUnless { it.isNullOrEmpty() }

    private fun AiRole.toTransport(): TransportRole = when (this) {
        AiRole.USER -> TransportRole.USER
        AiRole.ASSISTANT -> TransportRole.ASSISTANT
    }

    /** Mask one text segment; unions detected classes into [classes]. */
    private fun mask(text: String, classes: MutableSet<PayloadClass>): String {
        val masked = masker.mask(text)
        classes.addAll(masked.classes)
        return masked.text
    }

    private fun maskNullable(text: String?, classes: MutableSet<PayloadClass>): String? =
        text?.let { mask(it, classes) }

    private suspend fun missingKeyOutcome(
        ref: CallRef,
        ready: Gate.Ready,
    ): AiOutcome<AiReply> {
        val failure = AiFailure.MissingKey(ready.provider.storedKeyId)
        logRow(ref, ready.transport, emptySet(), bytesOut = 0, EgressOutcome.FAILED, failure.shortDescription)
        return AiOutcome.Failed(failure)
    }

    private suspend fun dispatch(
        ref: CallRef,
        ready: Gate.Ready,
        wireRequest: TransportRequest,
        key: String,
        classes: Set<PayloadClass>,
    ): AiOutcome<AiReply> {
        val completion = try {
            ready.transport.chat(wireRequest, key)
        } catch (wire: AiTransportException) {
            logRow(ref, ready.transport, classes, wire.bytesOut, EgressOutcome.FAILED, describe(wire))
            return AiOutcome.Failed(wire.failure.toAiFailure())
        }
        return finish(completion, wireRequest.jsonSchema, ref, ready.transport, classes)
    }

    /**
     * Shared terminal path (non-streaming AND streaming): structured-output parse +
     * exactly one log row (SUCCESS, or FAILED with the structured-output error).
     */
    private suspend fun finish(
        completion: TransportCompletion,
        schema: JsonObject?,
        ref: CallRef,
        transport: AiTransport,
        classes: Set<PayloadClass>,
    ): AiOutcome<AiReply> {
        val structured = schema?.let { StructuredOutput.extract(completion.text) }
        if (schema != null && structured == null) {
            logRow(ref, transport, classes, completion.bytesOut, EgressOutcome.FAILED, STRUCTURED_OUTPUT_ERROR)
            return AiOutcome.Failed(
                AiFailure.BadResponse("model did not return usable JSON for the requested schema"),
            )
        }
        logRow(ref, transport, classes, completion.bytesOut, EgressOutcome.SUCCESS, error = null)
        return AiOutcome.Success(AiReply(completion.text, structured))
    }

    private suspend fun streamTerminal(
        completion: TransportCompletion,
        ref: CallRef,
        schema: JsonObject?,
        transport: AiTransport,
        classes: Set<PayloadClass>,
    ): AiStreamEvent = when (val outcome = finish(completion, schema, ref, transport, classes)) {
        is AiOutcome.Success -> AiStreamEvent.Completed(outcome.value)
        is AiOutcome.Failed -> AiStreamEvent.Failed(outcome.failure)
    }

    private fun unknownTransportException(throwable: Throwable): AiTransportException =
        AiTransportException(
            failure = TransportFailure.Unreachable(
                throwable as? Exception ?: IllegalStateException(throwable.message),
            ),
            bytesOut = 0,
            where = "chokepoint",
        )

    private fun describe(wire: AiTransportException): String = when (val failure = wire.failure) {
        is TransportFailure.Http -> "HTTP ${failure.status}: ${failure.bodySummary.orEmpty().take(120)}"
        is TransportFailure.Unreachable -> "unreachable: ${failure.cause.message.orEmpty().take(160)}"
        is TransportFailure.Malformed -> "malformed response: ${failure.detail.take(160)}"
    }

    private fun TransportFailure.toAiFailure(): AiFailure = when (this) {
        is TransportFailure.Http -> AiFailure.Http(status, bodySummary.orEmpty().take(200))
        is TransportFailure.Unreachable -> AiFailure.Unreachable(cause.message.orEmpty().take(200))
        is TransportFailure.Malformed -> AiFailure.BadResponse(detail.take(200))
    }

    private suspend fun logRow(
        ref: CallRef,
        transport: AiTransport,
        payloadClasses: Set<PayloadClass>,
        bytesOut: Int,
        outcome: EgressOutcome,
        error: String?,
    ) {
        logDao.insert(
            EgressLogEntity(
                id = idFactory(),
                requestId = ref.requestId,
                captureId = ref.captureId,
                feature = ref.feature.name,
                payloadClasses = payloadClasses.map { it.name },
                provider = transport.providerId,
                bytesOut = bytesOut,
                at = clock.millis(),
                outcome = outcome,
                error = error,
            ),
        )
    }

    /**
     * Lenient structured-output extractor (unit-tested): trims, strips markdown code
     * fences, then takes the first balanced, string-aware `{...}` block. Used by both
     * the non-streaming and streaming paths.
     */
    internal object StructuredOutput {
        fun extract(text: String): JsonObject? {
            if (text.isBlank()) return null
            var body = text.trim()
            if (body.startsWith("```")) {
                body = body.removePrefix("```").substringAfter('\n', missingDelimiterValue = "")
                body = body.removeSuffix("```").trim()
            }
            val start = body.indexOf('{')
            if (start < 0) return null
            val end = balancedEnd(body, start) ?: return null
            return runCatching { Json.parseToJsonElement(body.substring(start, end + 1)).jsonObject }
                .getOrNull()
        }

        private fun balancedEnd(text: String, start: Int): Int? {
            var depth = 0
            var inString = false
            var escaped = false
            for (index in start until text.length) {
                val ch = text[index]
                when {
                    escaped -> escaped = false
                    ch == '\\' && inString -> escaped = true
                    ch == '"' -> inString = !inString
                    !inString && ch == '{' -> depth++
                    !inString && ch == '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
            return null
        }
    }

    private companion object {
        const val STRUCTURED_OUTPUT_ERROR = "structured output missing/unparseable"
    }
}
