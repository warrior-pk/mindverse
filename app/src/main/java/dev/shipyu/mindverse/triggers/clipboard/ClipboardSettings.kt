package dev.shipyu.mindverse.triggers.clipboard

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Clipboard auto-add preference: OFF by default (privacy-first — the clipboard
 * often holds passwords). Only the user can turn it on, from the Privacy screen.
 * Reads happen foreground-only (Brain open), so the system paste notice stays visible.
 */
class ClipboardSettings(
    context: Context,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        context.preferencesDataStoreFile(FILE_NAME)
    }

    val enabled: Flow<Boolean> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException || throwable is CorruptionException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { it[KEY_ENABLED] ?: false }

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { it[KEY_ENABLED] = value }
    }

    suspend fun isEnabled(): Boolean = enabled.first()

    companion object {
        private const val FILE_NAME = "clipboard.preferences_pb"
        private val KEY_ENABLED = booleanPreferencesKey("clipboard_auto_add")
    }
}
