package dev.shipyu.mindverse.triggers.screenshot

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
 * Screenshot-watcher preferences (ticket 05, spec R4-4): the watcher is prompt-first;
 * auto-ingest defaults OFF and only the user can turn it on.
 */
class WatcherSettings(
    context: Context,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        context.preferencesDataStoreFile(FILE_NAME)
    }

    val autoIngest: Flow<Boolean> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException || throwable is CorruptionException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { it[KEY_AUTO_INGEST] ?: false }

    suspend fun setAutoIngest(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_INGEST] = enabled }
    }

    suspend fun isAutoIngestEnabled(): Boolean = autoIngest.first()

    companion object {
        private const val FILE_NAME = "watcher.preferences_pb"
        private val KEY_AUTO_INGEST = booleanPreferencesKey("screenshot_auto_ingest")
    }
}
