package moe.antimony.hoshi.features.audio

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun emitsDefaultSettingsWhenThereIsNoLegacyStore() = runBlocking {
        repository().use { repository ->
            assertEquals(AudioSettings(), repository.settings.first())
        }
    }

    @Test
    fun migratesLegacySharedPreferencesSettingsOnceAndNormalizesLocalSource() = runBlocking {
        val legacy = FakeLegacyAudioSettingsSource(
            AudioSettings(
                audioSources = listOf(AudioSettings.DefaultAudioSource),
                enableLocalAudio = true,
                enableAutoplay = true,
                playbackMode = AudioPlaybackMode.Duck,
            ),
        )

        repository(legacy).use { repository ->
            val migrated = repository.settings.first()

            assertTrue(migrated.enableLocalAudio)
            assertTrue(migrated.enableAutoplay)
            assertEquals(AudioPlaybackMode.Duck, migrated.playbackMode)
            assertEquals(AudioSettings.LocalAudioSource, migrated.audioSources.first())
            assertEquals(1, migrated.audioSources.count { it.url == AudioSettings.LocalAudioSource.url })

            repository.update { it.copy(enableAutoplay = false) }
            assertFalse(repository.settings.first().enableAutoplay)
            assertEquals(1, legacy.loadCount)
            assertEquals(1, legacy.clearObsoleteKeyCount)
        }
    }

    @Test
    fun updatePersistsSourcesAndRemovesLocalSourceWhenDisabled() = runBlocking {
        val custom = AudioSource(
            name = "Custom",
            url = "https://audio.example/?term={term}",
            isEnabled = false,
        )

        repository().use { repository ->
            repository.update {
                AudioSettings(
                    audioSources = listOf(AudioSettings.LocalAudioSource, AudioSettings.DefaultAudioSource, custom),
                    enableLocalAudio = false,
                    enableAutoplay = true,
                    playbackMode = AudioPlaybackMode.Mix,
                )
            }

            val saved = repository.settings.first()

            assertFalse(saved.enableLocalAudio)
            assertFalse(saved.audioSources.any { it.url == AudioSettings.LocalAudioSource.url })
            assertEquals(listOf(AudioSettings.DefaultAudioSource, custom), saved.audioSources)
            assertTrue(saved.enableAutoplay)
            assertEquals(AudioPlaybackMode.Mix, saved.playbackMode)
        }
    }

    @Test
    fun invalidPlaybackModeFallsBackToInterruptInDataStoreContract() {
        assertEquals(AudioPlaybackMode.Interrupt, AudioPlaybackMode.fromRawValue("unknown"))
        assertEquals(AudioPlaybackMode.Interrupt, AudioPlaybackMode.fromRawValue(null))
    }

    @Test
    fun audioCallSitesUseRepositoryFlowTransitionPath() {
        val audioView = java.io.File("src/main/java/moe/antimony/hoshi/features/audio/AudioView.kt").readText()
        val dictionarySearch = java.io.File("src/main/java/moe/antimony/hoshi/features/dictionary/DictionarySearchView.kt").readText()
        val dictionarySearchViewModel =
            java.io.File("src/main/java/moe/antimony/hoshi/features/dictionary/DictionarySearchViewModel.kt").readText()
        val readerWebView = java.io.File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val container = java.io.File("src/main/java/moe/antimony/hoshi/HoshiAppContainer.kt").readText()

        assertTrue(container.contains("audioSettingsRepository()"))
        assertTrue(audioView.contains("val audioSettingsRepository = appContainer.audioSettingsRepository"))
        assertTrue(audioView.contains("audioSettingsRepository.settings.collect"))
        assertTrue(audioView.contains("audioSettingsRepository.update { next }"))
        assertTrue(dictionarySearch.contains("appContainer.dictionarySearchRepository()"))
        assertTrue(dictionarySearchViewModel.contains("override val audioSettings: Flow<AudioSettings>"))
        assertTrue(dictionarySearchViewModel.contains("audioSettingsRepository.settings"))
        assertTrue(dictionarySearchViewModel.contains("repository.audioSettings.collect"))
        assertTrue(readerWebView.contains("val audioSettingsRepository = appContainer.audioSettingsRepository"))
        assertTrue(readerWebView.contains("audioSettingsRepository.settings.collect"))
    }

    private fun repository(
        legacySource: AudioSettingsLegacySource? = null,
    ): RepositoryHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("audio-settings.preferences_pb") },
        )
        return RepositoryHandle(
            repository = AudioSettingsRepository(
                dataStore = dataStore,
                legacySource = legacySource,
            ),
            scope = scope,
        )
    }

    private class RepositoryHandle(
        private val repository: AudioSettingsRepository,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        val settings: Flow<AudioSettings>
            get() = repository.settings

        suspend fun update(transform: (AudioSettings) -> AudioSettings) {
            repository.update(transform)
        }

        override fun close() {
            scope.cancel()
        }
    }

    private class FakeLegacyAudioSettingsSource(
        private val settings: AudioSettings,
    ) : AudioSettingsLegacySource {
        var loadCount = 0
            private set
        var clearObsoleteKeyCount = 0
            private set

        override fun load(): AudioSettings {
            loadCount += 1
            return settings
        }

        override fun clearObsoleteLocalAudioDatabaseUri() {
            clearObsoleteKeyCount += 1
        }
    }
}
