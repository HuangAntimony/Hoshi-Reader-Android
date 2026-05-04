package moe.antimony.hoshi.features.dictionary

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

class DictionarySettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun emitsDefaultSettingsWhenThereIsNoLegacyStore() = runBlocking {
        repository().use { repository ->
            assertEquals(DictionarySettings(), repository.settings.first())
        }
    }

    @Test
    fun migratesLegacySharedPreferencesSettingsOnceAndKeepsNormalization() = runBlocking {
        val legacy = FakeLegacyDictionarySettingsSource(
            DictionarySettings(
                dictionaryTabDefault = true,
                maxResults = 100,
                scanLength = 0,
                collapseDictionaries = true,
                compactGlossaries = false,
                showExpressionTags = true,
                harmonicFrequency = true,
                deduplicatePitchAccents = true,
                compactPitchAccents = false,
                customCSS = ".term { color: red; }",
            ),
        )

        repository(legacy).use { repository ->
            val migrated = repository.settings.first()

            assertTrue(migrated.dictionaryTabDefault)
            assertEquals(50, migrated.maxResults)
            assertEquals(1, migrated.scanLength)
            assertTrue(migrated.collapseDictionaries)
            assertFalse(migrated.compactGlossaries)
            assertTrue(migrated.showExpressionTags)
            assertTrue(migrated.harmonicFrequency)
            assertTrue(migrated.deduplicatePitchAccents)
            assertFalse(migrated.compactPitchAccents)
            assertEquals(".term { color: red; }", migrated.customCSS)

            repository.update { it.copy(maxResults = 12) }
            assertEquals(12, repository.settings.first().maxResults)
            assertEquals(1, legacy.loadCount)
        }
    }

    @Test
    fun updatePersistsNormalizedSettings() = runBlocking {
        repository().use { repository ->
            repository.update {
                it.copy(
                    dictionaryTabDefault = true,
                    maxResults = 0,
                    scanLength = 100,
                    collapseDictionaries = true,
                    compactGlossaries = false,
                    showExpressionTags = true,
                    harmonicFrequency = true,
                    deduplicatePitchAccents = true,
                    compactPitchAccents = false,
                    customCSS = ".tag { display: none; }",
                )
            }

            val saved = repository.settings.first()

            assertTrue(saved.dictionaryTabDefault)
            assertEquals(1, saved.maxResults)
            assertEquals(64, saved.scanLength)
            assertTrue(saved.collapseDictionaries)
            assertFalse(saved.compactGlossaries)
            assertTrue(saved.showExpressionTags)
            assertTrue(saved.harmonicFrequency)
            assertTrue(saved.deduplicatePitchAccents)
            assertFalse(saved.compactPitchAccents)
            assertEquals(".tag { display: none; }", saved.customCSS)
        }
    }

    @Test
    fun dictionaryCallSitesUseRepositoryFlowTransitionPath() {
        val appShell = java.io.File("src/main/java/moe/antimony/hoshi/navigation/AppShell.kt").readText()
        val dictionaryView = java.io.File("src/main/java/moe/antimony/hoshi/features/dictionary/DictionaryView.kt").readText()
        val dictionarySearch = java.io.File("src/main/java/moe/antimony/hoshi/features/dictionary/DictionarySearchView.kt").readText()
        val readerWebView = java.io.File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val viewModel = java.io.File("src/main/java/moe/antimony/hoshi/features/dictionary/DictionaryViewModel.kt").readText()
        val container = java.io.File("src/main/java/moe/antimony/hoshi/HoshiAppContainer.kt").readText()

        assertTrue(container.contains("dictionarySettingsRepository()"))
        assertTrue(appShell.contains("val dictionarySettingsRepository = appContainer.dictionarySettingsRepository"))
        assertTrue(appShell.contains("dictionarySettingsRepository.settings.collect"))
        assertTrue(appShell.contains("dictionarySettingsLoaded"))
        assertTrue(appShell.contains("dictionaryDefaultRouteApplied"))
        assertTrue(dictionaryView.contains("appContainer.dictionaryViewModelRepository(context.contentResolver)"))
        assertTrue(dictionarySearch.contains("val dictionarySettingsRepository = appContainer.dictionarySettingsRepository"))
        assertTrue(dictionarySearch.contains("dictionarySettingsRepository.settings.collect"))
        assertTrue(readerWebView.contains("val dictionarySettingsRepository = appContainer.dictionarySettingsRepository"))
        assertTrue(readerWebView.contains("dictionarySettingsRepository.settings.collect"))
        assertTrue(viewModel.contains("repository.settings.collect"))
        assertTrue(viewModel.contains("repository.updateSettings"))
    }

    private fun repository(
        legacySource: DictionarySettingsLegacySource? = null,
    ): RepositoryHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("dictionary-settings.preferences_pb") },
        )
        return RepositoryHandle(
            repository = DictionarySettingsRepository(
                dataStore = dataStore,
                legacySource = legacySource,
            ),
            scope = scope,
        )
    }

    private class RepositoryHandle(
        private val repository: DictionarySettingsRepository,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        val settings: Flow<DictionarySettings>
            get() = repository.settings

        suspend fun update(transform: (DictionarySettings) -> DictionarySettings) {
            repository.update(transform)
        }

        override fun close() {
            scope.cancel()
        }
    }

    private class FakeLegacyDictionarySettingsSource(
        private val settings: DictionarySettings,
    ) : DictionarySettingsLegacySource {
        var loadCount = 0
            private set

        override fun load(): DictionarySettings {
            loadCount += 1
            return settings
        }
    }
}
