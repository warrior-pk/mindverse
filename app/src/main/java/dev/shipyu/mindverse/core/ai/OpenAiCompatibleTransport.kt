package dev.shipyu.mindverse.core.ai

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
 * OpenAI-compatible chat-completions transport with a user-supplied base URL (spec R4-6:
 * OpenRouter / DeepSeek / Ollama / LM Studio). Auth = `Authorization: Bearer <key>`.
 * Structured output = `response_format: json_schema` (schema-guided, not strict-mode, so
 * servers that accept the field but not strict mode still behave).
 *
 * Streaming = SSE `delta.content` deltas, terminated by `data: [DONE]`.
 */
internal class OpenAiCompatibleTransport(
    private val httpClient: OkHttpClient,
) : AiTransport {
    override val providerId = "openai_compatible"

    override suspend fun chat(request: TransportRequest, apiKey: String): TransportCompletion =
        withContext(Dispatchers.IO) {
            val payload = envelope(request, stream = false).toString().toByteArray(Charsets.UTF_8)
            val response = TransportHttp.post(
                client = httpClient,
                url = endpointUrl(request),
                headers = listOf("Authorization" to "Bearer $apiKey"),
                payload = payload,
                providerId = providerId,
            )
            response.use {
                TransportHttp.checkSuccess(it, payload.size, providerId)
                val content = TransportHttp.wire(providerId, payload.size) { contentText(it.body?.string().orEmpty()) }
                TransportCompletion(content, payload.size)
            }
        }

    override fun chatStreaming(request: TransportRequest, apiKey: String): Flow<TransportEvent> =
        channelFlow {
            withContext(Dispatchers.IO) {
                val payload = envelope(request, stream = true).toString().toByteArray(Charsets.UTF_8)
                val response = TransportHttp.post(
                    client = httpClient,
                    url = endpointUrl(request),
                    headers = listOf("Authorization" to "Bearer $apiKey"),
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

    private fun endpointUrl(request: TransportRequest): String {
        // TransportRequest.baseUrl carries the user's custom base (e.g. http://localhost:11434/v1).
        val base = checkNotNull(request.baseUrl) { "openai-compatible transport requires a base URL" }
        return base.trimEnd('/') + "/chat/completions"
    }

    private fun envelope(request: TransportRequest, stream: Boolean): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", stream)
        putJsonArray("messages") {
            request.systemPrompt?.let { system ->
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
            }
            request.messages.forEach { message ->
                add(buildJsonObject {
                    put("role", message.role.name.lowercase())
                    if (message.images.isEmpty()) {
                        put("content", message.text)
                    } else {
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", message.text)
                            })
                            message.images.forEach { image ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", "data:${image.mimeType};base64,${image.base64Data}")
                                    })
                                })
                            }
                        }
                    }
                })
            }
        }
        request.temperature?.let { put("temperature", it) }
        request.maxOutputTokens?.let { put("max_tokens", it) }
        request.jsonSchema?.let { schema ->
            put("response_format", buildJsonObject {
                put("type", "json_schema")
                put("json_schema", buildJsonObject {
                    put("name", "mindverse_output")
                    put("schema", schema)
                })
            })
        }
    }

    private fun contentText(body: String): String {
        val message = firstChoice(body)?.get("message") as? JsonObject
            ?: throw AiTransportException(TransportFailure.Malformed("no choices/message in response"), 0, providerId)
        return message["content"]?.let { element -> (element as? JsonPrimitive)?.contentOrNull }
            ?: throw AiTransportException(TransportFailure.Malformed("no message content"), 0, providerId)
    }

    private fun deltaText(dataLine: String): String {
        val delta = firstChoice(dataLine)?.get("delta") as? JsonObject ?: return ""
        return delta["content"]?.let { element -> (element as? JsonPrimitive)?.contentOrNull }.orEmpty()
    }

    private fun firstChoice(body: String): JsonObject? {
        val root = Json.parseToJsonElement(body).jsonObject
        return (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
    }
}
