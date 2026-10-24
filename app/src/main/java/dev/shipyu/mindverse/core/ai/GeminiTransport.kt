package dev.shipyu.mindverse.core.ai

import dev.shipyu.mindverse.core.ai.GeminiSchemaSupport.caps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient

/**
 * Google Gemini (AI Studio BYOK key, spec R4-6) transport. Key travels in the
 * `x-goog-api-key` header (never in the URL, so it can't leak into logs). Structured
 * output = `responseMimeType: application/json` + `responseSchema` (OpenAPI-style,
 * auto-uppercased from the neutral JSON-Schema-ish dialect by [GeminiSchemaSupport]).
 *
 * Streaming: `:streamGenerateContent?alt=sse`, one JSON object per `data:` line.
 */
internal class GeminiTransport(
    private val httpClient: OkHttpClient,
    /** Injectable for tests (MockWebServer); production uses the AI Studio endpoint. */
    private val baseUrl: String = DEFAULT_BASE_URL,
) : AiTransport {
    override val providerId = "gemini"

    override suspend fun chat(request: TransportRequest, apiKey: String): TransportCompletion =
        withContext(Dispatchers.IO) {
            val payload = envelope(request).toString().toByteArray(Charsets.UTF_8)
            val response = TransportHttp.post(
                client = httpClient,
                url = "$baseUrl/v1beta/models/${request.model}:generateContent",
                headers = listOf("x-goog-api-key" to apiKey),
                payload = payload,
                providerId = providerId,
            )
            response.use {
                TransportHttp.checkSuccess(it, payload.size, providerId)
                val text = TransportHttp.wire(providerId, payload.size) { responseText(it.body?.string().orEmpty()) }
                TransportCompletion(text, payload.size)
            }
        }

    override fun chatStreaming(request: TransportRequest, apiKey: String): Flow<TransportEvent> =
        channelFlow {
            withContext(Dispatchers.IO) {
                val payload = envelope(request).toString().toByteArray(Charsets.UTF_8)
                val response = TransportHttp.post(
                    client = httpClient,
                    url = "$baseUrl/v1beta/models/${request.model}:streamGenerateContent?alt=sse",
                    headers = listOf("x-goog-api-key" to apiKey),
                    payload = payload,
                    providerId = providerId,
                )
                response.use {
                    TransportHttp.checkSuccess(it, payload.size, providerId)
                    val aggregate = TransportHttp.readSse(
                        response = it,
                        bytesOut = payload.size,
                        providerId = providerId,
                        decodeDelta = ::deltaText,
                        onData = { delta -> send(TransportEvent.Delta(delta)) },
                    )
                    send(TransportEvent.Completed(TransportCompletion(aggregate, payload.size)))
                }
            }
        }

    // --- internals ---------------------------------------------------------------------

    private fun envelope(request: TransportRequest): JsonObject = buildJsonObject {
        putJsonArray("contents") {
            request.messages.forEach { message ->
                add(
                    buildJsonObject {
                        put("role", if (message.role == TransportRole.USER) "user" else "model")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", message.text) })
                            message.images.forEach { image ->
                                add(
                                    buildJsonObject {
                                        put("inline_data", buildJsonObject {
                                            put("mime_type", image.mimeType)
                                            put("data", image.base64Data)
                                        })
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
        request.systemPrompt?.let { system ->
            put("systemInstruction", buildJsonObject {
                putJsonArray("parts") { add(buildJsonObject { put("text", system) }) }
            })
        }
        put("generationConfig", buildJsonObject {
            request.temperature?.let { put("temperature", it) }
            request.maxOutputTokens?.let { put("maxOutputTokens", it) }
            request.jsonSchema?.let { schema ->
                put("responseMimeType", "application/json")
                put("responseSchema", caps(schema))
            }
        })
    }

    /**
     * Candidates → text: concatenates `parts[].text` of the first candidate (skipping
     * non-text parts such as function calls). Also used per SSE event for deltas.
     */
    private fun responseText(body: String): String {
        val candidate = firstCandidate(body) ?: return ""
        val parts = candidate.parts ?: return ""
        return parts.mapNotNull { part ->
            (part as? JsonObject)?.get("text")
                ?.let { element -> (element as? JsonPrimitive)?.contentOrNull }
        }.joinToString("")
    }

    private fun deltaText(dataLine: String): String = responseText(dataLine)

    private fun firstCandidate(body: String): JsonObject? {
        val root = Json.parseToJsonElement(body).jsonObject
        return (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
    }

    private val JsonObject.parts: JsonArray?
        get() = ((this["content"] as? JsonObject)?.get("parts")) as? JsonArray

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
    }
}

/**
 * Neutral JSON-Schema-ish dialect → Gemini's OpenAPI-style `responseSchema`
 * (uppercase type names; `additionalProperties` dropped — Gemini rejects it).
 */
internal object GeminiSchemaSupport {
    fun caps(schema: JsonObject): JsonObject {
        val mapped = schema.mapNotNull { (key, value) ->
            when {
                key == "additionalProperties" -> null
                value is JsonObject -> key to caps(value)
                value is JsonArray -> key to JsonArray(
                    value.map { element -> (element as? JsonObject)?.let(::caps) ?: element },
                )
                value is JsonPrimitive && key == "type" && value.isString ->
                    key to JsonPrimitive(value.content.uppercase())
                else -> key to value
            }
        }
        return JsonObject(mapped.toMap())
    }
}
