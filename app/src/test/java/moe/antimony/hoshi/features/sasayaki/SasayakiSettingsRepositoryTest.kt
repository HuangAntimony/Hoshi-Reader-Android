package moe.antimony.hoshi.features.sasayaki

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SasayakiSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun emitsDefaultSettingsWhenThereIsNoLegacyStore() = runBlocking {
        repository().use { repository ->
            val settings = repository.settings.first()

            assertEquals(
                SasayakiSettings(
                    enabled = true,
                    showReaderToggle = true,
                    showReaderBottomPlaybackControls = true,
                    readerSkipButtonAction = SasayakiReaderSkipButtonAction.Cue,
                    reverseVerticalReaderSkipButtons = false,
                    autoScroll = true,
                    autoPause = true,
                    imageHoldSeconds = 1f,
                ),
                settings,
            )
            assertEquals(1f, settings.imageHoldSeconds, 0f)
        }
    }

    @Test
    fun migratesLegacySharedPreferencesSettingsOnce() = runBlocking {
        val legacySettings = SasayakiSettings(
            enabled = true,
            showReaderToggle = true,
            showReaderBottomPlaybackControls = false,
            readerSkipButtonAction = SasayakiReaderSkipButtonAction.Seconds15,
            reverseVerticalReaderSkipButtons = true,
            copyAudiobookToPrivateStorage = true,
            autoScroll = false,
            autoPause = false,
            imageHoldSeconds = 2.5f,
            lightTextColor = 0xFF111111,
            lightBackgroundColor = 0x22123456,
            darkTextColor = 0xFFEEEEEE,
            darkBackgroundColor = 0x88456789,
        )
        val legacy = FakeLegacySasayakiSettingsSource(legacySettings)

        repository(legacy).use { repository ->
            assertEquals(legacySettings, repository.settings.first())

            repository.update { it.copy(autoPause = true) }
            val updated = repository.settings.first()

            assertTrue(updated.autoPause)
            assertEquals(legacySettings.copy(autoPause = true), updated)
            assertEquals(1, legacy.loadCount)
        }
    }

    @Test
    fun updatePersistsEverySettingField() = runBlocking {
        val next = SasayakiSettings(
            enabled = true,
            showReaderToggle = true,
            showReaderBottomPlaybackControls = true,
            readerSkipButtonAction = SasayakiReaderSkipButtonAction.Seconds30,
            reverseVerticalReaderSkipButtons = true,
            copyAudiobookToPrivateStorage = true,
            autoScroll = false,
            autoPause = false,
            imageHoldSeconds = 4.5f,
            transcriptionPreset = SasayakiTranscriptionPreset.Fast,
            lightTextColor = 0xFF010203,
            lightBackgroundColor = 0x44040506,
            darkTextColor = 0xFF070809,
            darkBackgroundColor = 0xAA0A0B0C,
        )

        repository().use { repository ->
            repository.update { next }

            assertEquals(next, repository.settings.first())
        }
    }

    @Test
    fun imageHoldSecondsUseHalfSecondRangeAndMillis() {
        assertEquals(9, SasayakiImageHoldSliderSteps)
        assertEquals(0f, normalizeSasayakiImageHoldSeconds(-0.1f), 0f)
        assertEquals(0f, normalizeSasayakiImageHoldSeconds(0.24f), 0f)
        assertEquals(0.5f, normalizeSasayakiImageHoldSeconds(0.25f), 0f)
        assertEquals(2.5f, normalizeSasayakiImageHoldSeconds(2.49f), 0f)
        assertEquals(5f, normalizeSasayakiImageHoldSeconds(5.9f), 0f)
        assertEquals(0L, sasayakiImageHoldMillis(0f))
        assertEquals(500L, sasayakiImageHoldMillis(0.5f))
        assertEquals(1500L, sasayakiImageHoldMillis(1.5f))
        assertEquals(5000L, sasayakiImageHoldMillis(5f))
    }

    @Test
    fun updateNormalizesImageHoldSecondsBeforePersisting() = runBlocking {
        repository().use { repository ->
            repository.update { it.copy(imageHoldSeconds = 6.2f) }

            assertEquals(5f, repository.settings.first().imageHoldSeconds, 0f)

            repository.update { it.copy(imageHoldSeconds = 3.26f) }

            assertEquals(3.5f, repository.settings.first().imageHoldSeconds, 0f)
        }
    }

    @Test
    fun transcriptionPresetSurvivesReopeningAndUnknownValuesUseBalanced() = runBlocking {
        val file = tempFolder.root.resolve("presets.preferences_pb")
        val firstJob = Job()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + firstJob), produceFile = { file })
            val repository = SasayakiSettingsRepository(store)
            assertEquals(SasayakiTranscriptionPreset.Balanced, repository.settings.first().transcriptionPreset)
            repository.update { it.copy(transcriptionPreset = SasayakiTranscriptionPreset.Light) }
        } finally { firstJob.cancelAndJoin() }
        val secondJob = Job()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + secondJob), produceFile = { file })
            val repository = SasayakiSettingsRepository(store)
            assertEquals(SasayakiTranscriptionPreset.Light, repository.settings.first().transcriptionPreset)
            store.edit { it[stringPreferencesKey("sasayakiTranscriptionPreset")] = "unsupported" }
            assertEquals(SasayakiTranscriptionPreset.Balanced, repository.settings.first().transcriptionPreset)
        } finally { secondJob.cancelAndJoin() }
    }

    private fun repository(
        legacySource: SasayakiSettingsLegacySource? = null,
    ): RepositoryHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("sasayaki-settings.preferences_pb") },
        )
        return RepositoryHandle(
            repository = SasayakiSettingsRepository(
                dataStore = dataStore,
                legacySource = legacySource,
            ),
            scope = scope,
        )
    }

    private class RepositoryHandle(
        private val repository: SasayakiSettingsRepository,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        val settings: Flow<SasayakiSettings>
            get() = repository.settings

        suspend fun update(transform: (SasayakiSettings) -> SasayakiSettings) {
            repository.update(transform)
        }

        override fun close() {
            scope.cancel()
        }
    }

    private class FakeLegacySasayakiSettingsSource(
        private val settings: SasayakiSettings,
    ) : SasayakiSettingsLegacySource {
        var loadCount = 0
            private set

        override fun load(): SasayakiSettings {
            loadCount += 1
            return settings
        }
    }
}
