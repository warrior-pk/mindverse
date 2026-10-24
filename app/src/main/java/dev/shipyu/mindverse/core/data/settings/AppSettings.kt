package dev.shipyu.mindverse.core.data.settings

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
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
 * App-level non-secret settings (ticket 08): first-run completion and the update-check
 * debounce stamp (spec R6-3: cold-start checks at most once per 24h). Plain DataStore —
 * nothing here is sensitive (keys live in the Keystore-encrypted store).
 */
class AppSettings(
    context: Context,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        context.preferencesDataStoreFile(FILE_NAME)
    }

    val firstRunComplete: Flow<Boolean> = dataStore.data
        .catch { throwable -> emitIfStorageLost(throwable) }
        .map { it[KEY_FIRST_RUN_COMPLETE] ?: false }

    val lastUpdateCheckAt: Flow<Long> = dataStore.data
        .catch { throwable -> emitIfStorageLost(throwable) }
        .map { it[KEY_LAST_UPDATE_CHECK] ?: 0L }

    suspend fun setFirstRunComplete() {
        dataStore.edit { it[KEY_FIRST_RUN_COMPLETE] = true }
    }

    suspend fun markUpdateChecked(now: Long) {
        dataStore.edit { it[KEY_LAST_UPDATE_CHECK] = now }
    }

    suspend fun isFirstRunComplete(): Boolean = firstRunComplete.first()

    suspend fun lastUpdateCheck(): Long = lastUpdateCheckAt.first()

    private suspend fun kotlinx.coroutines.flow.FlowCollector<Preferences>.emitIfStorageLost(throwable: Throwable) {
        if (throwable is IOException || throwable is CorruptionException) {
            emit(emptyPreferences())
        } else {
            throw throwable
        }
    }

    companion object {
        private const val FILE_NAME = "app_settings.preferences_pb"
        private val KEY_FIRST_RUN_COMPLETE = booleanPreferencesKey("first_run_complete")
        private val KEY_LAST_UPDATE_CHECK = longPreferencesKey("last_update_check_at")
    }
}
