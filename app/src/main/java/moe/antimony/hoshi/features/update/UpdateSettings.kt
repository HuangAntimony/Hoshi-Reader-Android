package moe.antimony.hoshi.features.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class UpdateSettings(
    val autoDownloadUpdates: Boolean = true,
)

private val Context.updateSettingsDataStore by preferencesDataStore(name = "update-settings")

fun Context.updateSettingsRepository(): UpdateSettingsRepository =
    UpdateSettingsRepository(updateSettingsDataStore)

class UpdateSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<UpdateSettings> = dataStore.data.map { preferences ->
        UpdateSettings(
            autoDownloadUpdates = preferences[KEY_AUTO_DOWNLOAD_UPDATES] ?: true,
        )
    }

    suspend fun update(transform: (UpdateSettings) -> UpdateSettings) {
        dataStore.edit { preferences ->
            val current = UpdateSettings(
                autoDownloadUpdates = preferences[KEY_AUTO_DOWNLOAD_UPDATES] ?: true,
            )
            val next = transform(current)
            preferences[KEY_AUTO_DOWNLOAD_UPDATES] = next.autoDownloadUpdates
        }
    }

    companion object {
        private val KEY_AUTO_DOWNLOAD_UPDATES = booleanPreferencesKey("autoDownloadUpdates")
    }
}
