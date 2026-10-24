package dev.shipyu.mindverse.firstrun

import dev.shipyu.mindverse.core.ai.AiProvider
import dev.shipyu.mindverse.core.ai.ProviderSettingsStore
import dev.shipyu.mindverse.core.data.settings.AppSettings
import dev.shipyu.mindverse.core.data.settings.EncryptedSettingsStore
import dev.shipyu.mindverse.core.data.settings.KeyCipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** First-run seam (ticket 08): BYOK entry persists, skipping is allowed, completion sticks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirstRunControllerTest {

    private lateinit var providerSettings: ProviderSettingsStore
    private lateinit var keyStore: EncryptedSettingsStore
    private lateinit var appSettings: AppSettings
    private lateinit var controller: FirstRunController

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        // One DataStore instance per file (DataStore forbids doubles); a fake cipher keeps
        // the test off AndroidKeyStore (Robolectric doesn't provide it).
        providerSettings = ProviderSettingsStore.create(context)
        keyStore = EncryptedSettingsStore(context, FakeCipher())
        appSettings = AppSettings(context)
        controller = FirstRunController(appSettings, providerSettings, keyStore)
    }

    @Test
    fun `gemini key entry persists provider and encrypted key`() = runBlocking {
        controller.saveGeminiKey("  test-key-123  ")

        assertEquals(AiProvider.GEMINI, providerSettings.current().provider)
        val key = keyStore.getProviderKey(AiProvider.GEMINI.storedKeyId)
        assertEquals("test-key-123", key?.decodeToString())
    }

    @Test
    fun `openai-compatible entry persists base url model and optional key`() = runBlocking {
        controller.saveOpenAiCompatible("http://localhost:11434/v1", "llama3", "")

        val settings = providerSettings.current()
        assertEquals(AiProvider.OPENAI_COMPATIBLE, settings.provider)
        assertEquals("http://localhost:11434/v1", settings.openAiCompatibleBaseUrl)
        assertEquals("llama3", settings.openAiCompatibleModel)
    }

    @Test
    fun `skip path completes onboarding keyless`() = runBlocking {
        assertFalse(appSettings.firstRunComplete.first())
        controller.completeOnboarding()
        assertTrue(appSettings.firstRunComplete.first())
    }

    /** Reversible test cipher; persistence semantics are covered by the store's own tests. */
    private class FakeCipher : KeyCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.copyOf()
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext.copyOf()
    }
}
