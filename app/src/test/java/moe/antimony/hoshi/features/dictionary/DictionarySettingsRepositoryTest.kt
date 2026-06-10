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
            val settings = repository.settings.first()
            assertEquals(DictionarySettings(), settings)
            assertFalse(settings.lowRamDictionaryImport)
        }
    }

    @Test
    fun migratesLegacySharedPreferencesSettingsOnceAndKeepsNormalization() = runBlocking {
        val legacy = FakeLegacyDictionarySettingsSource(
            DictionarySettings(
                autoUpdateDictionaries = false,
                dictionaryUpdateInterval = DictionaryUpdateInterval.Monthly,
                lastDictionaryUpdateEpochMillis = 1_800_000_000_000L,
                dictionaryTabDefault = true,
                scanNonJapaneseText = false,
                maxResults = 100,
                scanLength = 0,
                collapseMode = DictionaryCollapseMode.CollapseAll,
                expandFirstDictionary = true,
                collapsedDictionaries = setOf("JMdict"),
                compactGlossaries = false,
                showExpressionTags = true,
                harmonicFrequency = true,
                deduplicatePitchAccents = true,
                compactPitchAccents = false,
                lowRamDictionaryImport = true,
                customCSS = ".term { color: red; }",
            ),
        )

        repository(legacy).use { repository ->
            val migrated = repository.settings.first()

            assertFalse(migrated.autoUpdateDictionaries)
            assertEquals(DictionaryUpdateInterval.Monthly, migrated.dictionaryUpdateInterval)
            assertEquals(1_800_000_000_000L, migrated.lastDictionaryUpdateEpochMillis)
            assertTrue(migrated.dictionaryTabDefault)
            assertFalse(migrated.scanNonJapaneseText)
            assertEquals(50, migrated.maxResults)
            assertEquals(1, migrated.scanLength)
            assertEquals(DictionaryCollapseMode.CollapseAll, migrated.collapseMode)
            assertTrue(migrated.expandFirstDictionary)
            assertEquals(setOf("JMdict"), migrated.collapsedDictionaries)
            assertFalse(migrated.compactGlossaries)
            assertTrue(migrated.showExpressionTags)
            assertTrue(migrated.harmonicFrequency)
            assertTrue(migrated.deduplicatePitchAccents)
            assertFalse(migrated.compactPitchAccents)
            assertTrue(migrated.lowRamDictionaryImport)
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
                    autoUpdateDictionaries = false,
                    dictionaryUpdateInterval = DictionaryUpdateInterval.Daily,
                    lastDictionaryUpdateEpochMillis = 1_900_000_000_000L,
                    dictionaryTabDefault = true,
                    scanNonJapaneseText = false,
                    maxResults = 0,
                    scanLength = 100,
                    collapseMode = DictionaryCollapseMode.Custom,
                    expandFirstDictionary = true,
                    collapsedDictionaries = setOf("JMdict"),
                    compactGlossaries = false,
                    showExpressionTags = true,
                    harmonicFrequency = true,
                    deduplicatePitchAccents = true,
                    compactPitchAccents = false,
                    lowRamDictionaryImport = true,
                    customCSS = ".tag { display: none; }",
                )
            }

            val saved = repository.settings.first()

            assertFalse(saved.autoUpdateDictionaries)
            assertEquals(DictionaryUpdateInterval.Daily, saved.dictionaryUpdateInterval)
            assertEquals(1_900_000_000_000L, saved.lastDictionaryUpdateEpochMillis)
            assertTrue(saved.dictionaryTabDefault)
            assertFalse(saved.scanNonJapaneseText)
            assertEquals(1, saved.maxResults)
            assertEquals(64, saved.scanLength)
            assertEquals(DictionaryCollapseMode.Custom, saved.collapseMode)
            assertTrue(saved.expandFirstDictionary)
            assertEquals(setOf("JMdict"), saved.collapsedDictionaries)
            assertFalse(saved.compactGlossaries)
            assertTrue(saved.showExpressionTags)
            assertTrue(saved.harmonicFrequency)
            assertTrue(saved.deduplicatePitchAccents)
            assertFalse(saved.compactPitchAccents)
            assertTrue(saved.lowRamDictionaryImport)
            assertEquals(".tag { display: none; }", saved.customCSS)
        }
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
