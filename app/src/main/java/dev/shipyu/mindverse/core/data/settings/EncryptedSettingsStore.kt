package dev.shipyu.mindverse.core.data.settings

import android.content.Context
import android.util.Base64
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

/**
 * Keystore-encrypted settings persistence for provider keys (spec technical defaults;
 * ticket 02). Keys are sealed with the injected [KeyCipher] before they ever touch
 * storage; the store persists only opaque ciphertext (base64 of cipher-framed bytes).
 *
 * Key material crosses this API as bytes and is never returned in logs. Provider ids are
 * stable tokens, e.g. "gemini", "openai_compatible" (BYOK surface, spec R4-6).
 */
class EncryptedSettingsStore(
    context: Context,
    private val cipher: KeyCipher,
    settingsFileName: String = SETTINGS_FILE_NAME,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = {
            context.preferencesDataStoreFile(settingsFileName)
        })

    suspend fun putProviderKey(providerId: String, keyBytes: ByteArray) {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        val sealed = cipher.encrypt(keyBytes)
        val encoded = Base64.encodeToString(sealed, BASE64_FLAGS)
        dataStore.edit { prefs ->
            prefs[providerKeyPref(providerId)] = encoded
        }
    }

    /** Returns the key bytes, or null when absent/unreadable (corrupt store never crashes callers). */
    suspend fun getProviderKey(providerId: String): ByteArray? {
        val prefs = readPrefs()
        val encoded = prefs[providerKeyPref(providerId)] ?: return null
        return runCatching {
            cipher.decrypt(Base64.decode(encoded, BASE64_FLAGS))
        }.getOrNull()
    }

    suspend fun removeProviderKey(providerId: String) {
        dataStore.edit { prefs ->
            prefs.remove(providerKeyPref(providerId))
        }
    }

    private suspend fun readPrefs(): Preferences =
        try {
            dataStore.data.first()
        } catch (corruption: CorruptionException) {
            emptyPreferences()
        } catch (io: IOException) {
            emptyPreferences()
        }

    private fun providerKeyPref(providerId: String) = stringPreferencesKey("provider_key.$providerId")

    companion object {
        const val SETTINGS_FILE_NAME = "mindverse_secure_settings"
        private const val BASE64_FLAGS = Base64.NO_WRAP
    }
}
