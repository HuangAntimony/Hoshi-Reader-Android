package moe.antimony.hoshi.features.anki

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface AnkiSettingsRepository {
    val settings: Flow<AnkiSettings>
    suspend fun update(transform: (AnkiSettings) -> AnkiSettings)
}

class DataStoreAnkiSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : AnkiSettingsRepository {
    override val settings: Flow<AnkiSettings> = dataStore.data.map { preferences ->
        preferences[KEY_SETTINGS]?.let { raw ->
            runCatching { json.decodeFromString<AnkiSettings>(raw) }.getOrNull()
        } ?: AnkiSettings()
    }

    override suspend fun update(transform: (AnkiSettings) -> AnkiSettings) {
        dataStore.edit { preferences ->
            val current = preferences[KEY_SETTINGS]?.let { raw ->
                runCatching { json.decodeFromString<AnkiSettings>(raw) }.getOrNull()
            } ?: AnkiSettings()
            preferences[KEY_SETTINGS] = json.encodeToString(transform(current))
        }
    }

    private companion object {
        val KEY_SETTINGS = stringPreferencesKey("ankiSettings")
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

private val Context.ankiDataStore by preferencesDataStore(name = "anki_settings")

fun Context.ankiSettingsRepository(): AnkiSettingsRepository =
    DataStoreAnkiSettingsRepository(ankiDataStore)
