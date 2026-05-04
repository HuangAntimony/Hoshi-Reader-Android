package moe.antimony.hoshi.features.sasayaki

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

class SasayakiSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun emitsDefaultSettingsWhenThereIsNoLegacyStore() = runBlocking {
        repository().use { repository ->
            assertEquals(SasayakiSettings(), repository.settings.first())
        }
    }

    @Test
    fun migratesLegacySharedPreferencesSettingsOnce() = runBlocking {
        val legacySettings = SasayakiSettings(
            enabled = true,
            showReaderToggle = true,
            copyAudiobookToPrivateStorage = true,
            autoScroll = false,
            autoPause = false,
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
            copyAudiobookToPrivateStorage = true,
            autoScroll = false,
            autoPause = false,
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
    fun sasayakiCallSitesUseRepositoryFlowTransitionPath() {
        val settingsView = java.io.File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiSettingsView.kt").readText()
        val readerWebView = java.io.File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val bookshelfView = java.io.File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()
        val bookshelfRepository =
            java.io.File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfRepository.kt").readText()
        val container = java.io.File("src/main/java/moe/antimony/hoshi/HoshiAppContainer.kt").readText()

        assertTrue(container.contains("sasayakiSettingsRepository()"))
        assertTrue(settingsView.contains("val repository = appContainer.sasayakiSettingsRepository"))
        assertTrue(settingsView.contains("repository.settings.collect"))
        assertTrue(settingsView.contains("repository.update { next }"))
        assertTrue(readerWebView.contains("val sasayakiSettingsRepository = appContainer.sasayakiSettingsRepository"))
        assertTrue(readerWebView.contains("sasayakiSettingsRepository.settings.collect"))
        assertTrue(bookshelfView.contains("val sasayakiSettingsRepository = appContainer.sasayakiSettingsRepository"))
        assertTrue(bookshelfView.contains("sasayakiSettingsRepository.settings.collect"))
        assertTrue(bookshelfView.contains("booksViewModel.setSasayakiEnabled(settings.enabled)"))
        assertFalse(bookshelfRepository.contains("SasayakiSettingsStore"))
        assertFalse(bookshelfRepository.contains("sasayakiSettingsStore.load()"))
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
