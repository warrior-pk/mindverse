package dev.shipyu.mindverse.core.egress

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Per-feature egress toggles (ticket 03; spec R5-1). Checked at the chokepoint at call
 * time; a disabled feature short-circuits with [AiFailure.DeniedByToggle].
 *
 * Defaults are enabled — only the user's own toggles switch egress OFF, matching the
 * kill-switch philosophy (spec R6-2: OFF-only) with local toggles being user intent.
 * Values are user preferences, not secrets: plain DataStore (keys live in the
 * Keystore-encrypted store, ticket 02).
 */
class EgressToggles private constructor(
    private val dataStore: DataStore<Preferences>,
) {
    fun states(): Flow<Map<EgressFeature, Boolean>> =
        dataStore.data
            .catch { throwable ->
                // Only swallow storage corruption/IO loss, like ProviderSettingsStore —
                // never programmer errors (they should crash loudly, not reset toggles).
                if (throwable is IOException || throwable is CorruptionException) {
                    emit(emptyPreferences())
                } else {
                    throw throwable
                }
            }
            .map { prefs ->
                EgressFeature.entries.associateWith { feature ->
                    prefs[booleanPreferencesKey(feature.name)] ?: true
                }
            }

    suspend fun setEnabled(feature: EgressFeature, enabled: Boolean) {
        dataStore.edit { prefs -> prefs[booleanPreferencesKey(feature.name)] = enabled }
    }

    companion object {
        const val SETTINGS_FILE_NAME = "mindverse_privacy_settings"

        fun create(
            context: Context,
            settingsFileName: String = SETTINGS_FILE_NAME,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ): EgressToggles = EgressToggles(
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { context.preferencesDataStoreFile(settingsFileName) },
            ),
        )
    }
}
