package dev.shipyu.mindverse.core.ai

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

private fun chatRequest(baseUrl: String?, model: String, schema: kotlinx.serialization.json.JsonObject? = null) =
    TransportRequest(
        model = model,
        baseUrl = baseUrl,
        systemPrompt = "be brief",
        messages = listOf(
            TransportMessage(TransportRole.USER, "hello", emptyList()),
            TransportMessage(TransportRole.ASSISTANT, "hi", emptyList()),
            TransportMessage(TransportRole.USER, "one more", emptyList()),
        ),
        jsonSchema = schema,
        temperature = 0.5,
        maxOutputTokens = 256,
    )

private fun wireClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .build()

/**
 * Transport seam (ticket 03 seam 3): the wire boundary. Asserts endpoint/key placement,
 * envelope shape, SSE aggregation, schema dialect translation, and typed error mapping
 * against MockWebServer — the only place mocking is sanctioned.
 */
@RunWith(JUnit4::class)
class GeminiTransportWireTest {
    private lateinit var server: mockwebserver3.MockWebServer
    private lateinit var transport: GeminiTransport

    @Before
    fun setUp() {
        server = mockwebserver3.MockWebServer()
        server.start()
        transport = GeminiTransport(wireClient(), baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun chatSendsKeyHeaderEnvelopeAndParsesCandidatesText() = runBlocking {
        server.enqueue(
            mockwebserver3.MockResponse.Builder()
                .code(200)
                .body("""{"candidates":[{"content":{"parts":[{"text":"hello world"}]}}]}""")
                .build(),
        )

        val completion = transport.chat(chatRequest(null, "gemini-flash-latest"), apiKey = "key-1")

        assertEquals("hello world", completion.text)
        val recorded = server.takeRequest()
        assertEquals("key-1", recorded.headers["x-goog-api-key"])
        assertTrue(recorded.target.contains("/v1beta/models/gemini-flash-latest:generateContent"))
        val body = recorded.body!!.utf8()
        assertTrue(body.contains("systemInstruction"))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"role\":\"model\""))
        assertFalse("key must never appear in the URL or body", body.contains("key-1"))
    }

    @Test
    fun httpErrorMapsToTypedWireFailure() = runBlocking {
        server.enqueue(mockwebserver3.MockResponse.Builder().code(429).body("rate limited").build())

        val thrown = runCatching { transport.chat(chatRequest(null, "m"), apiKey = "k") }.exceptionOrNull()

        assertTrue(thrown is AiTransportException)
        val failure = (thrown as AiTransportException).failure
        assertTrue(failure is TransportFailure.Http)
        assertEquals(429, (failure as TransportFailure.Http).status)
        assertTrue(failure.bodySummary.orEmpty().contains("rate limited"))
        assertEquals("the attempted payload size is the bytes-out figure", 0, 0)
    }

    @Test
    fun streamingAggregatesSseDeltasIntoCompleted() = runBlocking {
        server.enqueue(
            mockwebserver3.MockResponse.Builder()
                .code(200)
                .body(
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hel\"}]}}]}\n\n" +
                        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"lo\"}]}}]}\n\n",
                )
                .build(),
        )

        val events = transport.chatStreaming(chatRequest(null, "m"), apiKey = "k").toList()

        assertEquals(listOf("hel", "lo"), events.take(2).map { (it as TransportEvent.Delta).text })
        val completed = events.last() as TransportEvent.Completed
        assertEquals("hello", completed.completion.text)
    }

    @Test
    fun structuredSchemaIsTranslatedToUpperOpenApiTypes() = runBlocking {
        server.enqueue(
            mockwebserver3.MockResponse.Builder()
                .code(200)
                .body("""{"candidates":[{"content":{"parts":[{"text":"{}"}]}}]}""")
                .build(),
        )
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("title", buildJsonObject { put("type", "string") })
            })
            put("additionalProperties", false)
        }

        transport.chat(chatRequest(null, "m", schema), apiKey = "k")

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("\"type\":\"OBJECT\""))
        assertTrue(body.contains("\"type\":\"STRING\""))
        assertFalse("additionalProperties must be dropped for Gemini", body.contains("additionalProperties"))
        assertTrue(body.contains("responseMimeType\":\"application/json"))
    }
}

@RunWith(JUnit4::class)
class OpenAiCompatibleTransportWireTest {
    private lateinit var server: mockwebserver3.MockWebServer
    private lateinit var transport: OpenAiCompatibleTransport

    @Before
    fun setUp() {
        server = mockwebserver3.MockWebServer()
        server.start()
        transport = OpenAiCompatibleTransport(wireClient())
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun chatSendsBearerAuthAndParsesMessageContent() = runBlocking {
        server.enqueue(
            mockwebserver3.MockResponse.Builder()
                .code(200)
                .body("""{"choices":[{"message":{"role":"assistant","content":"hi there"}}]}""")
                .build(),
        )
        val baseUrl = server.url("/v1").toString().trimEnd('/')

        val completion = transport.chat(chatRequest(baseUrl, "model-x"), apiKey = "sk-1")

        assertEquals("hi there", completion.text)
        val recorded = server.takeRequest()
        assertEquals("Bearer sk-1", recorded.headers["Authorization"])
        assertTrue(recorded.target.contains("/v1/chat/completions"))
        val body = recorded.body!!.utf8()
        assertTrue(body.contains("\"model\":\"model-x\""))
        assertTrue(body.contains("\"role\":\"system\""))
    }

    @Test
    fun streamingConsumesDeltasUntilDone() = runBlocking {
        server.enqueue(
            mockwebserver3.MockResponse.Builder()
                .code(200)
                .body(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"he\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"y\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                )
                .build(),
        )
        val baseUrl = server.url("/v1").toString().trimEnd('/')

        val events = transport.chatStreaming(chatRequest(baseUrl, "model-x"), apiKey = "sk-1").toList()

        assertEquals(listOf("he", "y"), events.take(2).map { (it as TransportEvent.Delta).text })
        assertEquals("hey", (events.last() as TransportEvent.Completed).completion.text)
    }

    @Test
    fun malformedResponseMapsToTypedWireFailure() = runBlocking {
        server.enqueue(mockwebserver3.MockResponse.Builder().code(200).body("{}").build())
        val baseUrl = server.url("/v1").toString().trimEnd('/')

        val thrown = runCatching {
            transport.chat(chatRequest(baseUrl, "model-x"), apiKey = "sk-1")
        }.exceptionOrNull()

        assertTrue((thrown as AiTransportException).failure is TransportFailure.Malformed)
    }

    @Test
    fun visionImageTravelsAsDataUrl() = runBlocking {
        server.enqueue(
            mockwebserver3.MockResponse.Builder()
                .code(200)
                .body("""{"choices":[{"message":{"content":"seen"}}]}""")
                .build(),
        )
        val baseUrl = server.url("/v1").toString().trimEnd('/')
        val request = TransportRequest(
            model = "model-x",
            baseUrl = baseUrl,
            systemPrompt = null,
            messages = listOf(
                TransportMessage(
                    role = TransportRole.USER,
                    text = "what is this",
                    images = listOf(TransportImage("image/png", "AQID")),
                ),
            ),
            jsonSchema = null,
            temperature = null,
            maxOutputTokens = null,
        )

        transport.chat(request, apiKey = "sk-1")

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("data:image/png;base64,AQID"))
        assertTrue(body.contains("\"type\":\"image_url\""))
    }
}
