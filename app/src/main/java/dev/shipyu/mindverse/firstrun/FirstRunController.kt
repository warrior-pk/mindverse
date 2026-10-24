package dev.shipyu.mindverse.firstrun

import dev.shipyu.mindverse.core.ai.AiProvider
import dev.shipyu.mindverse.core.ai.ProviderSettingsStore
import dev.shipyu.mindverse.core.data.settings.AppSettings
import dev.shipyu.mindverse.core.data.settings.EncryptedSettingsStore
import kotlinx.coroutines.flow.Flow

/**
 * First-run onboarding (ticket 08): grant-what's-needed permissions, a privacy preface,
 * and an optional BYOK key entry with a skip path — the app stays fully usable keyless
 * (spec R1-2). Onboarding completion is persisted in [AppSettings]; nothing here egresses.
 */
class FirstRunController(
    private val appSettings: AppSettings,
    private val providerSettings: ProviderSettingsStore,
    private val keyStore: EncryptedSettingsStore,
) {
    val firstRunComplete: Flow<Boolean> = appSettings.firstRunComplete

    suspend fun completeOnboarding() {
        appSettings.setFirstRunComplete()
    }

    /** BYOK step: Gemini AI Studio key (spec R4-6). */
    suspend fun saveGeminiKey(key: String) {
        providerSettings.setProvider(AiProvider.GEMINI)
        keyStore.putProviderKey(AiProvider.GEMINI.storedKeyId, key.trim().toByteArray())
    }

    /** BYOK step: OpenAI-compatible base URL + model + optional key (OpenRouter/Ollama/…). */
    suspend fun saveOpenAiCompatible(baseUrl: String, model: String, key: String) {
        providerSettings.setProvider(AiProvider.OPENAI_COMPATIBLE)
        providerSettings.setOpenAiCompatible(baseUrl, model)
        if (key.isNotBlank()) {
            keyStore.putProviderKey(AiProvider.OPENAI_COMPATIBLE.storedKeyId, key.trim().toByteArray())
        }
    }
}
