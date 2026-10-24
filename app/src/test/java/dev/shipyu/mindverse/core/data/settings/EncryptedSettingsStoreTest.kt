package dev.shipyu.mindverse.core.data.settings

import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Reversible test double: frames as [0x5A][len-low][len-high][payload] and flips payload bits. */
private class FakeXorCipher(key: Byte = 0x33) : KeyCipher {
    private val mask = key

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val out = ByteArray(plaintext.size + 3)
        out[0] = 0x5A
        out[1] = (plaintext.size and 0xFF).toByte()
        out[2] = ((plaintext.size shr 8) and 0xFF).toByte()
        for (i in plaintext.indices) out[3 + i] = (plaintext[i].toInt() xor mask.toInt()).toByte()
        return out
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size >= 3 && ciphertext[0] == 0x5A.toByte()) { "not ours" }
        val length = (ciphertext[1].toInt() and 0xFF) or ((ciphertext[2].toInt() and 0xFF) shl 8)
        val out = ByteArray(length)
        for (i in 0 until length) out[i] = (ciphertext[3 + i].toInt() xor mask.toInt()).toByte()
        return out
    }
}

/** Writes an invalid frame; decrypt always throws — simulates unreadable at-rest bytes. */
private class CorruptingCipher : KeyCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray = byteArrayOf(0x00, 0x01, 0x02)

    override fun decrypt(ciphertext: ByteArray): ByteArray =
        throw IllegalStateException("unreadable ciphertext")
}

/**
 * EncryptedSettingsStore seam: keys round-trip through the cipher; storage only ever
 * holds ciphertext (the at-rest property); removal clears; corrupt ciphertext degrades
 * to null instead of crashing callers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncryptedSettingsStoreTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun providerKeyRoundTrips() {
        runBlocking {
            val store = EncryptedSettingsStore(context, FakeXorCipher(), settingsFileName = "test_settings_1")
            val geminiKey = "AIzaSy-test-key-byok".toByteArray(Charsets.UTF_8)

            store.putProviderKey("gemini", geminiKey)
            assertArrayEquals(geminiKey, store.getProviderKey("gemini"))
            assertNull(store.getProviderKey("openai_compatible"))
        }
    }

    @Test
    fun storedValueIsCipheredNotPlaintext() {
        runBlocking {
            val store = EncryptedSettingsStore(context, FakeXorCipher(), settingsFileName = "test_settings_2")
            val secret = "AIzaSy-secret-byok-value".toByteArray(Charsets.UTF_8)
            store.putProviderKey("gemini", secret)

            val prefsFile = context.preferencesDataStoreFile("test_settings_2")
            assertTrue(prefsFile.exists())
            val bytes = prefsFile.readBytes()
            assertTrue(bytes.isNotEmpty())
            val decoded = bytes.toString(Charsets.ISO_8859_1)
            // Plaintext secret must not appear anywhere in the file.
            assertFalse("plaintext key found in settings file", decoded.contains("AIzaSy-secret-byok-value"))
            // Ciphered form (base64 of the sealed frame) must appear.
            val ciphered = java.util.Base64.getEncoder().encodeToString(FakeXorCipher().encrypt(secret))
            assertTrue("ciphered key missing from settings file", decoded.contains(ciphered))
        }
    }

    @Test
    fun removeProviderKeyClearsIt() {
        runBlocking {
            val store = EncryptedSettingsStore(context, FakeXorCipher(), settingsFileName = "test_settings_3")
            store.putProviderKey("gemini", "key1".toByteArray())
            assertNotNull(store.getProviderKey("gemini"))

            store.removeProviderKey("gemini")
            assertNull(store.getProviderKey("gemini"))
        }
    }

    @Test
    fun corruptCipherTextDegradesToNull() {
        runBlocking {
            // A broken cipher writes an invalid frame; the store must degrade to null
            // instead of crashing callers (never trust at-rest bytes).
            val store = EncryptedSettingsStore(context, CorruptingCipher(), settingsFileName = "test_settings_4")
            store.putProviderKey("gemini", "key".toByteArray())
            assertNull(store.getProviderKey("gemini"))
        }
    }

    @Test
    fun blankProviderIdIsRejected() {
        runBlocking {
            val store = EncryptedSettingsStore(context, FakeXorCipher(), settingsFileName = "test_settings_5")
            var threw = false
            try {
                store.putProviderKey(" ", "k".toByteArray())
            } catch (expected: IllegalArgumentException) {
                threw = true
            }
            assertTrue(threw)
        }
    }
}
