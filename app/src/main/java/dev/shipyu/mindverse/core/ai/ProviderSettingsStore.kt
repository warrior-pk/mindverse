package dev.shipyu.mindverse.core.ai

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The provider a BYOK key belongs to (spec R4-6: Gemini AI Studio key + OpenAI-compatible
 * custom base URL). [storedKeyId] is the [dev.shipyu.mindverse.core.data.settings.EncryptedSettingsStore]
 * key slot.
 */
enum class AiProvider(val storedKeyId: String) {
    GEMINI("gemini"),
    OPENAI_COMPATIBLE("openai_compatible"),
}

/**
 * Provider/model selection (BYOK surface, spec R4-6). Values are user preferences, not
 * secrets: plain DataStore; keys stay in the Keystore-encrypted store.
 */
data class AiProviderSettings(
    val provider: AiProvider = AiProvider.GEMINI,
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    /**
     * Custom OpenAI-compatible base URL (OpenRouter/DeepSeek/Ollama/LM Studio), e.g.
     * `https://openrouter.ai/api/v1` or `http://localhost:11434/v1`. Empty until the
     * user configures the BYOK surface; calls fail with typed
     * [dev.shipyu.mindverse.core.egress.AiFailure.Unconfigured] meanwhile.
     */
    val openAiCompatibleBaseUrl: String = "",
    val openAiCompatibleModel: String = "",
) {
    companion object {
        /**
         * Alias refreshed by Google; avoids pinning a dated model id in a fixed default.
         */
        const val DEFAULT_GEMINI_MODEL = "gemini-flash-latest"
    }
}

/** Plain-settings persistence for provider selection (ticket 03). */
class ProviderSettingsStore private constructor(
    private val dataStore: DataStore<Preferences>,
) {
    fun settings(): Flow<AiProviderSettings> =
        dataStore.data
            .catch { throwable ->
                if (throwable is IOException || throwable is CorruptionException) {
                    emit(emptyPreferences())
                } else {
                    throw throwable
                }
            }
            .map { prefs -> fromPrefs(prefs) }

    suspend fun current(): AiProviderSettings = settings().first()

    suspend fun setProvider(provider: AiProvider) = update { it.copy(provider = provider) }

    suspend fun setGeminiModel(model: String) = update { it.copy(geminiModel = model) }

    suspend fun setOpenAiCompatible(baseUrl: String, model: String) =
        update { it.copy(openAiCompatibleBaseUrl = baseUrl.trim(), openAiCompatibleModel = model.trim()) }

    private suspend fun update(transform: (AiProviderSettings) -> AiProviderSettings) {
        dataStore.edit { prefs ->
            val next = transform(fromPrefs(prefs))
            prefs[KEY_PROVIDER] = next.provider.name
            prefs[KEY_GEMINI_MODEL] = next.geminiModel
            prefs[KEY_OPENAI_BASE_URL] = next.openAiCompatibleBaseUrl
            prefs[KEY_OPENAI_MODEL] = next.openAiCompatibleModel
        }
    }

    private fun fromPrefs(prefs: Preferences): AiProviderSettings = AiProviderSettings(
        provider = prefs[KEY_PROVIDER]?.let { name ->
            AiProvider.entries.firstOrNull { it.name == name }
        } ?: AiProvider.GEMINI,
        geminiModel = prefs[KEY_GEMINI_MODEL] ?: AiProviderSettings.DEFAULT_GEMINI_MODEL,
        openAiCompatibleBaseUrl = prefs[KEY_OPENAI_BASE_URL] ?: "",
        openAiCompatibleModel = prefs[KEY_OPENAI_MODEL] ?: "",
    )

    companion object {
        const val SETTINGS_FILE_NAME = "mindverse_ai_settings"

        private val KEY_PROVIDER = stringPreferencesKey("provider")
        private val KEY_GEMINI_MODEL = stringPreferencesKey("gemini_model")
        private val KEY_OPENAI_BASE_URL = stringPreferencesKey("openai_compatible_base_url")
        private val KEY_OPENAI_MODEL = stringPreferencesKey("openai_compatible_model")

        fun create(
            context: Context,
            settingsFileName: String = SETTINGS_FILE_NAME,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ): ProviderSettingsStore = ProviderSettingsStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { context.preferencesDataStoreFile(settingsFileName) },
            ),
        )
    }
}
