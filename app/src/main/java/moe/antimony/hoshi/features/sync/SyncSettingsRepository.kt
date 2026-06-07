package moe.antimony.hoshi.features.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.syncSettingsDataStore by preferencesDataStore(name = SyncSettingsRepository.DataStoreName)

fun Context.syncSettingsRepository(drive: DriveSyncDataSource): SyncSettingsRepository =
    SyncSettingsRepository(syncSettingsDataStore, drive)

class SyncSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val drive: DriveSyncDataSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val settings: Flow<SyncSettings> = dataStore.data
        .map { preferences -> preferences.toSyncSettings() }

    suspend fun update(transform: (SyncSettings) -> SyncSettings) {
        dataStore.edit { preferences ->
            val current = preferences.toSyncSettings()
            preferences.writeSyncSettings(transform(current))
        }
    }

    suspend fun clearGoogleDriveCache() = withContext(ioDispatcher) {
        drive.clearCache()
    }

    private fun Preferences.toSyncSettings(): SyncSettings =
        SyncSettings(
            enabled = this[KEY_ENABLED] ?: false,
            mode = SyncMode.fromRawValue(this[KEY_MODE]),
            autoSyncEnabled = this[KEY_AUTO_SYNC_ENABLED] ?: false,
            authProvider = SyncAuthProvider.DeviceCode,
            uploadBooks = this[KEY_UPLOAD_BOOKS] ?: true,
        )

    private fun MutablePreferences.writeSyncSettings(settings: SyncSettings) {
        this[KEY_ENABLED] = settings.enabled
        this[KEY_MODE] = settings.mode.rawValue
        this[KEY_AUTO_SYNC_ENABLED] = settings.autoSyncEnabled
        this[KEY_AUTH_PROVIDER] = settings.authProvider.name
        this[KEY_UPLOAD_BOOKS] = settings.uploadBooks
    }

    companion object {
        const val DataStoreName = "sync-settings"

        private val KEY_ENABLED = booleanPreferencesKey("syncEnabled")
        private val KEY_MODE = stringPreferencesKey("syncMode")
        private val KEY_AUTO_SYNC_ENABLED = booleanPreferencesKey("autoSyncEnabled")
        private val KEY_AUTH_PROVIDER = stringPreferencesKey("syncAuthProvider")
        private val KEY_UPLOAD_BOOKS = booleanPreferencesKey("uploadBooks")
    }
}
