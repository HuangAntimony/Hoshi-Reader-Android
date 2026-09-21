package moe.antimony.hoshi.features.audio

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioDefaultMigrationTest {
    @get:Rule val folder = TemporaryFolder()
    private val old = AudioSource("Default", "https://hoshi-reader.manhhaoo-do.workers.dev/?term={term}&reading={reading}", isDefault = true)
    private val custom = AudioSource("Mine", "https://example.com/?term={term}", isEnabled = false)
    private val urls = listOf("jpod101", "language-pod-101", "jisho").map {
        "hoshi-builtin-audio-source://$it/?term={term}&reading={reading}"
    }

    @Test fun freshDefaultsExposeThreeEnabledSourcesInOrder() {
        assertEquals(urls, AudioSettings().enabledAudioSourceUrls)
        assertTrue(AudioSettings().audioSources.all { it.isDefault })
    }

    @Test fun upgradesPersistedDefaultInPlaceAndKeepsDisabledState() = runBlocking {
        checkMigration(listOf(custom, old.copy(isEnabled = false)), local = true) { settings ->
            assertEquals(AudioSettings.LocalAudioSource, settings.audioSources[0])
            assertEquals(custom, settings.audioSources[1])
            assertEquals(urls, settings.audioSources.drop(2).map { it.url })
            assertTrue(settings.audioSources.drop(2).none { it.isEnabled })
            assertTrue(settings.enableAutoplay)
            assertEquals(AudioPlaybackMode.Duck, settings.playbackMode)
        }
    }

    @Test fun upgradesEnabledDefaultAndPreservesFollowingCustomSource() = runBlocking {
        checkMigration(listOf(old, custom)) { settings ->
            assertEquals(urls, settings.enabledAudioSourceUrls)
            assertEquals(custom, settings.audioSources.last())
        }
    }

    @Test fun preservesCustomOldProxyAndDoesNotInsertMissingDefaults() = runBlocking {
        val sources = listOf(custom, old.copy(name = "My proxy", isDefault = false))
        checkMigration(sources) { assertEquals(sources, it.audioSources) }
    }

    @Test fun preservesExistingNewSourcePositionAndStateWithoutDuplicates() = runBlocking {
        val jisho = AudioSource("Jisho", urls[2], isEnabled = false, isDefault = true)
        checkMigration(listOf(jisho, custom, old, old)) {
            assertEquals(listOf(jisho.url, custom.url, urls[0], urls[1]), it.audioSources.map { source -> source.url })
            assertEquals(jisho, it.audioSources.first())
        }
    }

    @Test fun migratesSharedPreferencesBeforePublishingSettings() = runBlocking {
        checkMigration(null, legacy = AudioSettings(listOf(old.copy(isEnabled = false), custom))) {
            assertEquals(urls + custom.url, it.audioSources.map { source -> source.url })
            assertTrue(it.audioSources.none { source -> source.isEnabled })
        }
    }

    @Test fun corruptPersistedSourcesRecoverToNewDefaults() = runBlocking {
        checkMigration(null, corrupt = true) { assertEquals(urls, it.enabledAudioSourceUrls) }
    }

    @Test fun emptyPersistedSourcesRecoverToDefaults() = runBlocking {
        checkMigration(emptyList()) { assertEquals(urls, it.enabledAudioSourceUrls) }
    }

    @Test fun migrationSurvivesClosingAndReopeningDataStore() = runBlocking {
        val file = folder.newFile("restart.preferences_pb")
        val firstJob = Job()
        val firstStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + firstJob)) { file }
        firstStore.edit {
            it[booleanPreferencesKey("audioSettingsMigratedFromSharedPreferences")] = true
            it[stringPreferencesKey("audioSources")] = Json.encodeToString(listOf(old, custom))
        }
        val firstRepository = AudioSettingsRepository(firstStore)
        // update() must migrate even when nobody has collected settings yet.
        firstRepository.update { it.copy(audioSources = it.audioSources.reversed(), enableAutoplay = true) }
        val beforeRestart = firstRepository.settings.first()
        assertEquals(listOf(custom.url) + urls.reversed(), beforeRestart.audioSources.map { it.url })
        firstJob.cancelAndJoin()
        val nextJob = Job()
        try {
            val nextStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + nextJob)) { file }
            assertEquals(beforeRestart, AudioSettingsRepository(nextStore).settings.first())
        } finally { nextJob.cancelAndJoin() }
    }

    private suspend fun checkMigration(
        sources: List<AudioSource>?,
        local: Boolean = false,
        legacy: AudioSettings? = null,
        corrupt: Boolean = false,
        verify: (AudioSettings) -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("audio.preferences_pb") }
            if (legacy == null) store.edit {
                it[booleanPreferencesKey("audioSettingsMigratedFromSharedPreferences")] = true
                if (sources != null || corrupt) it[stringPreferencesKey("audioSources")] = if (corrupt) "{" else Json.encodeToString(sources!!)
                it[booleanPreferencesKey("enableLocalAudio")] = local
                it[booleanPreferencesKey("audioEnableAutoplay")] = true
                it[stringPreferencesKey("audioPlaybackMode")] = "duck"
            }
            val legacySource = legacy?.let { value -> object : AudioSettingsLegacySource {
                override fun load() = value
            } }
            val repository = AudioSettingsRepository(store, legacySource)
            val migrated = repository.settings.first()
            verify(migrated)
            assertEquals(migrated, repository.settings.first())
            assertEquals(migrated, AudioSettingsRepository(store).settings.first())
            val persisted = Json.decodeFromString<List<AudioSource>>(store.data.first()[stringPreferencesKey("audioSources")]!!)
            assertEquals(migrated.audioSources, persisted)
            repository.update { it.copy(audioSources = it.audioSources.reversed()) }
            assertEquals(migrated.audioSources.reversed().filterNot { it == AudioSettings.LocalAudioSource },
                repository.settings.first().audioSources.filterNot { it == AudioSettings.LocalAudioSource })
        } finally { scope.cancel() }
    }
}
