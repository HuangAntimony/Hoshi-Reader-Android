package moe.antimony.hoshi.features.audio

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class AudioSource(
    val name: String = "",
    val url: String,
    val isEnabled: Boolean = true,
    val isDefault: Boolean = false,
)

enum class AudioPlaybackMode(val rawValue: String, val displayName: String) {
    Interrupt("interrupt", "Interrupt"),
    Duck("duck", "Lower Volume"),
    Mix("mix", "Keep Volume");

    companion object {
        fun fromRawValue(value: String?): AudioPlaybackMode =
            entries.firstOrNull { it.rawValue == value } ?: Interrupt
    }
}

data class AudioSettings(
    val audioSources: List<AudioSource> = listOf(DefaultAudioSource),
    val enableLocalAudio: Boolean = false,
    val enableAutoplay: Boolean = false,
    val playbackMode: AudioPlaybackMode = AudioPlaybackMode.Interrupt,
) {
    val enabledAudioSourceUrls: List<String>
        get() = audioSources.filter { it.isEnabled }.map { it.url }

    fun withLocalAudioEnabled(enabled: Boolean): AudioSettings {
        val withoutLocal = audioSources.filterNot { it.isBuiltInLocalAudioSource }
        return copy(
            enableLocalAudio = enabled,
            audioSources = if (enabled) listOf(LocalAudioSource) + withoutLocal else withoutLocal,
        )
    }

    fun withAudioSourceEnabled(source: AudioSource, enabled: Boolean): AudioSettings {
        if (source.isBuiltInLocalAudioSource) return withLocalAudioEnabled(enabled)
        return copy(
            audioSources = audioSources.map { item ->
                if (item.url == source.url) item.copy(isEnabled = enabled) else item
            },
        )
    }

    fun addSource(source: AudioSource): AudioSettings {
        if (source.url.isBlank() || source.name.isBlank()) return this
        if (audioSources.any { it.url == source.url }) return this
        return copy(audioSources = audioSources + source)
    }

    companion object {
        const val LocalAudioPath = "Audio/android.db"
        const val LocalAudioSourceConfigPath = "Audio/android_sources.json"
        const val LocalAudioUrl = "http://localhost:8765/localaudio/get/?term={term}&reading={reading}"
        const val InternalLocalAudioUrl = "hoshi-local-audio-source://get/?term={term}&reading={reading}"

        val LocalAudioSource = AudioSource(
            name = "Local",
            url = LocalAudioUrl,
            isEnabled = true,
        )

        val DefaultAudioSource = AudioSource(
            name = "Default",
            url = "https://hoshi-reader.manhhaoo-do.workers.dev/?term={term}&reading={reading}",
            isEnabled = true,
            isDefault = true,
        )
    }
}

internal val AudioSource.isBuiltInLocalAudioSource: Boolean
    get() = name == AudioSettings.LocalAudioSource.name && url == AudioSettings.LocalAudioSource.url

private fun AudioSettings.normalizedAudioSettings(): AudioSettings {
    val withoutLocal = audioSources.filterNot { it.isBuiltInLocalAudioSource }
    return copy(
        audioSources = if (enableLocalAudio) {
            listOf(AudioSettings.LocalAudioSource) + withoutLocal
        } else {
            withoutLocal.ifEmpty { listOf(AudioSettings.DefaultAudioSource) }
        },
    )
}

interface AudioSettingsLegacySource {
    fun load(): AudioSettings

    fun clearObsoleteLocalAudioDatabaseUri() = Unit
}

class AudioSettingsStore(context: Context) : AudioSettingsLegacySource {
    private val preferences = context.getSharedPreferences("audio-settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): AudioSettings {
        val sources = preferences.getString(KEY_AUDIO_SOURCES, null)
            ?.let { encoded ->
                runCatching { json.decodeFromString(ListSerializer(AudioSource.serializer()), encoded) }.getOrNull()
            }
            ?: listOf(AudioSettings.DefaultAudioSource)
        return AudioSettings(
            audioSources = sources,
            enableLocalAudio = preferences.getBoolean(KEY_ENABLE_LOCAL_AUDIO, false),
            enableAutoplay = preferences.getBoolean(KEY_AUDIO_ENABLE_AUTOPLAY, false),
            playbackMode = AudioPlaybackMode.fromRawValue(preferences.getString(KEY_AUDIO_PLAYBACK_MODE, null)),
        ).let { settings ->
            if (settings.enableLocalAudio && settings.audioSources.none { it.isBuiltInLocalAudioSource }) {
                settings.withLocalAudioEnabled(true)
            } else if (!settings.enableLocalAudio && settings.audioSources.any { it.isBuiltInLocalAudioSource }) {
                settings.withLocalAudioEnabled(false)
            } else {
                settings
            }
        }
    }

    override fun clearObsoleteLocalAudioDatabaseUri() {
        preferences.edit()
            .remove(KEY_LOCAL_AUDIO_DATABASE_URI)
            .apply()
    }

    fun save(settings: AudioSettings) {
        preferences.edit()
            .putString(KEY_AUDIO_SOURCES, json.encodeToString(ListSerializer(AudioSource.serializer()), settings.audioSources))
            .putBoolean(KEY_ENABLE_LOCAL_AUDIO, settings.enableLocalAudio)
            .remove(KEY_LOCAL_AUDIO_DATABASE_URI)
            .putBoolean(KEY_AUDIO_ENABLE_AUTOPLAY, settings.enableAutoplay)
            .putString(KEY_AUDIO_PLAYBACK_MODE, settings.playbackMode.rawValue)
            .apply()
    }

    private companion object {
        const val KEY_AUDIO_SOURCES = "audioSources"
        const val KEY_ENABLE_LOCAL_AUDIO = "enableLocalAudio"
        const val KEY_LOCAL_AUDIO_DATABASE_URI = "localAudioDatabaseUri"
        const val KEY_AUDIO_ENABLE_AUTOPLAY = "audioEnableAutoplay"
        const val KEY_AUDIO_PLAYBACK_MODE = "audioPlaybackMode"
    }
}

private val Context.audioSettingsDataStore by preferencesDataStore(name = AudioSettingsRepository.DataStoreName)

fun Context.audioSettingsRepository(): AudioSettingsRepository =
    AudioSettingsRepository(
        dataStore = audioSettingsDataStore,
        legacySource = AudioSettingsStore(this),
    )

class AudioSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val legacySource: AudioSettingsLegacySource? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<AudioSettings> = dataStore.data
        .onStart { migrateLegacySettingsIfNeeded() }
        .map { preferences -> preferences.toAudioSettings() }

    suspend fun update(transform: (AudioSettings) -> AudioSettings) {
        migrateLegacySettingsIfNeeded()
        dataStore.edit { preferences ->
            val current = preferences.toAudioSettings()
            preferences.writeAudioSettings(transform(current).normalizedAudioSettings())
            preferences[KEY_MIGRATED_FROM_SHARED_PREFERENCES] = true
        }
    }

    private suspend fun migrateLegacySettingsIfNeeded() {
        var didMigrate = false
        dataStore.edit { preferences ->
            if (preferences[KEY_MIGRATED_FROM_SHARED_PREFERENCES] == true) return@edit
            preferences.writeAudioSettings(legacySource?.load()?.normalizedAudioSettings() ?: AudioSettings())
            preferences[KEY_MIGRATED_FROM_SHARED_PREFERENCES] = true
            didMigrate = true
        }
        if (didMigrate) {
            legacySource?.clearObsoleteLocalAudioDatabaseUri()
        }
    }

    private fun Preferences.toAudioSettings(): AudioSettings {
        val sources = this[KEY_AUDIO_SOURCES]
            ?.let { encoded ->
                runCatching { json.decodeFromString(ListSerializer(AudioSource.serializer()), encoded) }.getOrNull()
            }
            ?: listOf(AudioSettings.DefaultAudioSource)
        return AudioSettings(
            audioSources = sources,
            enableLocalAudio = this[KEY_ENABLE_LOCAL_AUDIO] ?: false,
            enableAutoplay = this[KEY_AUDIO_ENABLE_AUTOPLAY] ?: false,
            playbackMode = AudioPlaybackMode.fromRawValue(this[KEY_AUDIO_PLAYBACK_MODE]),
        ).normalizedAudioSettings()
    }

    private fun MutablePreferences.writeAudioSettings(settings: AudioSettings) {
        val normalized = settings.normalizedAudioSettings()
        this[KEY_AUDIO_SOURCES] = json.encodeToString(
            ListSerializer(AudioSource.serializer()),
            normalized.audioSources,
        )
        this[KEY_ENABLE_LOCAL_AUDIO] = normalized.enableLocalAudio
        remove(KEY_LOCAL_AUDIO_DATABASE_URI)
        this[KEY_AUDIO_ENABLE_AUTOPLAY] = normalized.enableAutoplay
        this[KEY_AUDIO_PLAYBACK_MODE] = normalized.playbackMode.rawValue
    }

    companion object {
        const val DataStoreName = "audio-settings"

        private val KEY_MIGRATED_FROM_SHARED_PREFERENCES =
            booleanPreferencesKey("audioSettingsMigratedFromSharedPreferences")
        private val KEY_AUDIO_SOURCES = stringPreferencesKey("audioSources")
        private val KEY_ENABLE_LOCAL_AUDIO = booleanPreferencesKey("enableLocalAudio")
        private val KEY_LOCAL_AUDIO_DATABASE_URI = stringPreferencesKey("localAudioDatabaseUri")
        private val KEY_AUDIO_ENABLE_AUTOPLAY = booleanPreferencesKey("audioEnableAutoplay")
        private val KEY_AUDIO_PLAYBACK_MODE = stringPreferencesKey("audioPlaybackMode")
    }
}
