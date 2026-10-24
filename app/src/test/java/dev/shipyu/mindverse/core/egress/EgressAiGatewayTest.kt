package dev.shipyu.mindverse.core.egress

import dev.shipyu.mindverse.core.ai.AiProvider
import dev.shipyu.mindverse.core.ai.AiTransport
import dev.shipyu.mindverse.core.ai.AiTransportException
import dev.shipyu.mindverse.core.ai.ProviderSettingsStore
import dev.shipyu.mindverse.core.ai.TransportCompletion
import dev.shipyu.mindverse.core.ai.TransportEvent
import dev.shipyu.mindverse.core.ai.TransportFailure
import dev.shipyu.mindverse.core.ai.TransportRequest
import dev.shipyu.mindverse.core.data.TestDatabaseFactory
import dev.shipyu.mindverse.core.data.db.EgressOutcome
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.data.settings.EncryptedSettingsStore
import dev.shipyu.mindverse.core.data.settings.KeyCipher
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.of
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Reversible test cipher (pattern from EncryptedSettingsStoreTest). */
private class FakeCipher : KeyCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray = byteArrayOf(0x5A) + plaintext
    override fun decrypt(ciphertext: ByteArray): ByteArray =
        ciphertext.takeIf { it.size > 1 && it[0] == 0x5A.toByte() }
            ?.drop(1)
            ?.toByteArray()
            ?: throw IllegalArgumentException("not ours")
}

/**
 * Wire-boundary fake for [AiTransport] (system boundary — the only mockable place per
 * the mocking guideline): records the last wire request/key, returns canned completions
 * or throws typed wire failures, and replays canned streaming events.
 */
private class FakeTransport : AiTransport {
    override val providerId = "gemini"
    var lastRequest: TransportRequest? = null
    var lastKey: String? = null
    var completion: TransportCompletion = TransportCompletion("plain reply", 42)
    var chatThrow: AiTransportException? = null
    var streamEvents: List<TransportEvent> = emptyList()
    var streamThrow: AiTransportException? = null

    override suspend fun chat(request: TransportRequest, apiKey: String): TransportCompletion {
        lastRequest = request
        lastKey = apiKey
        chatThrow?.let { throw it }
        return completion
    }

    override fun chatStreaming(request: TransportRequest, apiKey: String): Flow<TransportEvent> = flow {
        lastRequest = request
        lastKey = apiKey
        streamEvents.forEach { emit(it) }
        streamThrow?.let { throw it }
    }
}

private class FixedClock(var currentMillis: Long = 1_700_000_000_000L) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(currentMillis)
}

/**
 * Chokepoint seam (ticket 03 seam 1): toggles gate before any wire call, outbound text
 * is masked, keys resolve from the encrypted store, one log row lands per terminal
 * state (success / failure / denial), and embed is the local no-op stub.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EgressAiGatewayTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: MindverseDatabase
    private lateinit var transport: FakeTransport
    private lateinit var keyStore: EncryptedSettingsStore
    private lateinit var toggles: EgressToggles
    private lateinit var gateway: EgressAiGateway
    private var instanceCount = 0

    @Before
    fun setUp() {
        db = TestDatabaseFactory.inMemoryDatabase(context)
        transport = FakeTransport()
        keyStore = EncryptedSettingsStore(
            context,
            FakeCipher(),
            settingsFileName = "gw_keys_${nextInstance()}",
        )
        toggles = EgressToggles.create(context, settingsFileName = "gw_toggles_${nextInstance()}")
        gateway = EgressAiGateway(
            transports = mapOf(AiProvider.GEMINI to transport),
            providerSettings = ProviderSettingsStore.create(
                context,
                settingsFileName = "gw_provider_${nextInstance()}",
            ),
            keyStore = keyStore,
            toggles = toggles,
            logDao = db.egressLogDao(),
            clock = FixedClock(),
            idFactory = { "row-1" },
        )
    }

    private fun nextInstance(): Int = ++instanceCount

    private fun seedKey() {
        runBlocking { keyStore.putProviderKey(AiProvider.GEMINI.storedKeyId, "AIza-test".toByteArray()) }
    }

    @Test
    fun deniedToggleShortCircuitsWithTypedFailureAndLogsDeniedRow() = runBlocking {
        seedKey()
        toggles.setEnabled(EgressFeature.ASK, enabled = false)

        val outcome = gateway.chat(chatRequest())

        assertTrue(outcome is AiOutcome.Failed)
        assertTrue((outcome as AiOutcome.Failed).failure is AiFailure.DeniedByToggle)
        val row = db.egressLogDao().recent(10).single()
        assertEquals(EgressOutcome.DENIED, row.outcome)
        assertEquals(0, row.bytesOut)
        assertEquals(EgressFeature.ASK.name, row.feature)
        assertTrue("no wire call may happen on denial", transport.lastRequest == null)
    }

    @Test
    fun successfulChatMasksOutboundTextAndLogsSuccess() = runBlocking {
        seedKey()

        val outcome = gateway.chat(
            AiChatRequest(
                feature = EgressFeature.MEMORY_EXTRACTION,
                requestId = "req-7",
                captureId = "cap-1",
                systemPrompt = "you are a helper",
                messages = listOf(AiMessage(AiRole.USER, "contact +91 98765 43210 now")),
            ),
        )

        assertTrue(outcome is AiOutcome.Success)
        // Wire saw masked text, not the raw phone number.
        val wire = transport.lastRequest!!
        assertEquals("contact <phone> now", wire.messages.single().text)
        assertEquals("AIza-test", transport.lastKey)
        val row = db.egressLogDao().recent(10).single()
        assertEquals(EgressOutcome.SUCCESS, row.outcome)
        assertEquals(42, row.bytesOut)
        assertEquals("gemini", row.provider)
        assertEquals(listOf(PayloadClass.PHONE.name), row.payloadClasses)
        assertEquals("req-7", row.requestId)
        assertEquals("cap-1", row.captureId)
        assertEquals(EgressFeature.MEMORY_EXTRACTION.name, row.feature)
    }

    @Test
    fun providerWireFailureLogsFailedRowAndReturnsTypedFailure() = runBlocking {
        seedKey()
        transport.chatThrow = AiTransportException(
            failure = TransportFailure.Http(429, "rate limited"),
            bytesOut = 777,
            where = "gemini",
        )

        val outcome = gateway.chat(chatRequest())

        assertTrue(outcome is AiOutcome.Failed)
        assertTrue((outcome as AiOutcome.Failed).failure is AiFailure.Http)
        assertEquals(429, (outcome.failure as AiFailure.Http).status)
        val row = db.egressLogDao().recent(10).single()
        assertEquals(EgressOutcome.FAILED, row.outcome)
        assertEquals(777, row.bytesOut)
        assertTrue(row.error.orEmpty().contains("HTTP 429"))
    }

    @Test
    fun missingKeyLogsFailedRow() = runBlocking {
        // no key stored
        val outcome = gateway.chat(chatRequest())

        assertTrue(outcome is AiOutcome.Failed)
        assertTrue((outcome as AiOutcome.Failed).failure is AiFailure.MissingKey)
        val row = db.egressLogDao().recent(10).single()
        assertEquals(EgressOutcome.FAILED, row.outcome)
        assertEquals(0, row.bytesOut)
        assertTrue(row.error.orEmpty().contains("no key configured"))
        assertTrue("no wire call without a key", transport.lastRequest == null)
    }

    @Test
    fun structuredOutputParsesJsonFromFencedReply() = runBlocking {
        seedKey()
        transport.completion = TransportCompletion(
            text = "```json\n{\"title\":\"Trip\"}\n```",
            bytesOut = 10,
        )

        val outcome = gateway.chat(chatRequest(schema = sampleSchema()))

        assertTrue(outcome is AiOutcome.Success)
        val reply = (outcome as AiOutcome.Success).value
        assertEquals("Trip", reply.structured?.get("title")?.toString()?.removeSurrounding("\""))
        assertEquals(EgressOutcome.SUCCESS, db.egressLogDao().recent(10).single().outcome)
    }

    @Test
    fun structuredOutputFailureLogsBadResponseRow() = runBlocking {
        seedKey()
        transport.completion = TransportCompletion("no json here", 10)

        val outcome = gateway.chat(chatRequest(schema = sampleSchema()))

        assertTrue(outcome is AiOutcome.Failed)
        assertTrue((outcome as AiOutcome.Failed).failure is AiFailure.BadResponse)
        val row = db.egressLogDao().recent(10).single()
        assertEquals(EgressOutcome.FAILED, row.outcome)
        assertTrue(row.error.orEmpty().contains("structured output"))
    }

    @Test
    fun streamingEmitsDeltasThenCompletedAndLogsSuccess() = runBlocking {
        seedKey()
        transport.streamEvents = listOf(
            TransportEvent.Delta("hel"),
            TransportEvent.Delta("lo"),
            TransportEvent.Completed(TransportCompletion("hello", 30)),
        )

        val events = gateway.chatStreaming(chatRequest()).toList()

        assertEquals(listOf("hel", "lo"), events.take(2).map { (it as AiStreamEvent.Delta).text })
        assertTrue(events.last() is AiStreamEvent.Completed)
        assertEquals(EgressOutcome.SUCCESS, db.egressLogDao().recent(10).single().outcome)
    }

    @Test
    fun streamingWireFailureEmitsTypedFailedEventAndLogsRow() = runBlocking {
        seedKey()
        transport.streamEvents = listOf(TransportEvent.Delta("partial"))
        transport.streamThrow = AiTransportException(
            failure = TransportFailure.Unreachable(IOException("connection reset")),
            bytesOut = 12,
            where = "gemini",
        )

        val events = gateway.chatStreaming(chatRequest()).toList()

        assertTrue(events.first() is AiStreamEvent.Delta)
        val terminal = events.last()
        assertTrue(terminal is AiStreamEvent.Failed)
        assertTrue((terminal as AiStreamEvent.Failed).failure is AiFailure.Unreachable)
        val row = db.egressLogDao().recent(10).single()
        assertEquals(EgressOutcome.FAILED, row.outcome)
        assertEquals(12, row.bytesOut)
    }

    @Test
    fun embedIsLocalNoOpNeverLogging() = runBlocking {
        val outcome = gateway.embed("anything")

        assertTrue(outcome is AiOutcome.Success)
        assertEquals(null, (outcome as AiOutcome.Success).value)
        assertEquals(0, db.egressLogDao().countAll())
        assertTrue("embed must not egress", transport.lastRequest == null)
    }

    @Test
    fun visionMasksPromptAndEncodesImageBytes() = runBlocking {
        seedKey()
        transport.completion = TransportCompletion("{\"title\":\"Vision ok\"}", 500)

        val outcome = gateway.vision(
            AiVisionRequest(
                feature = EgressFeature.MEMORY_EXTRACTION,
                requestId = "req-9",
                prompt = "call +91 98765 43210 about this",
                images = listOf(AiImage(mimeType = "image/png", bytes = of(1, 2, 3))),
                responseSchema = sampleSchema(),
            ),
        )

        val wire = transport.lastRequest!!
        assertEquals("call <phone> about this", wire.messages.single().text)
        val image = wire.messages.single().images.single()
        assertEquals("image/png", image.mimeType)
        assertEquals("AQID", image.base64Data)
        assertTrue(outcome is AiOutcome.Success)
        assertEquals(EgressOutcome.SUCCESS, db.egressLogDao().recent(10).single().outcome)
    }

    // --- harness -----------------------------------------------------------------------

    private fun chatRequest(schema: JsonObject? = null) = AiChatRequest(
        feature = EgressFeature.ASK,
        requestId = "req-1",
        captureId = null,
        messages = listOf(AiMessage(AiRole.USER, "hello there")),
        responseSchema = schema,
    )

    private fun sampleSchema() = buildJsonObject {
        put("type", "OBJECT")
        put("description", "test schema")
    }
}
