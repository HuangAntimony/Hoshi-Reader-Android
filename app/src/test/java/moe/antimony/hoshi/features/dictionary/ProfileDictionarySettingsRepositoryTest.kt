package moe.antimony.hoshi.features.dictionary

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.profiles.ProfileRepository
import moe.antimony.hoshi.testing.CountingCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileDictionarySettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun profileJsonReadsAndWritesUseInjectedIoDispatcher() = runBlocking {
        CountingCoroutineDispatcher().use { ioDispatcher ->
            val profileRepository = ProfileRepository(
                filesDir = tempFolder.newFolder("files"),
                ioDispatcher = ioDispatcher,
            )
            val repository = repository(
                profileRepository = profileRepository,
                ioDispatcher = ioDispatcher,
            )
            val beforeProfileAccess = ioDispatcher.dispatchCount

            repository.update { it.copy(customCSS = ".profile { color: red; }") }
            assertEquals(".profile { color: red; }", repository.settings.first().customCSS)

            assertTrue(ioDispatcher.dispatchCount >= beforeProfileAccess + 2)
        }
    }

    @Test
    fun profileDictionarySettingsAreScopedWhileOperationalSettingsStayGlobal() = runBlocking {
        val profileRepository = ProfileRepository(tempFolder.newFolder("files"))
        val repository = repository(profileRepository)

        repository.update {
            it.copy(
                autoUpdateDictionaries = false,
                dictionaryUpdateInterval = DictionaryUpdateInterval.Daily,
                lastDictionaryUpdateEpochMillis = 1_900_000_000_000L,
                dictionaryTabDefault = true,
                scanNonJapaneseText = false,
                maxResults = 12,
                scanLength = 20,
                searchTextSize = 26,
                collapseMode = DictionaryCollapseMode.Custom,
                expandFirstDictionary = true,
                collapsedDictionaries = setOf("JMdict"),
                compactGlossaries = false,
                showExpressionTags = true,
                harmonicFrequency = true,
                deduplicatePitchAccents = true,
                compactPitchAccents = false,
                lowRamDictionaryImport = true,
                customCSS = ".jp { color: red; }",
            )
        }
        val english = profileRepository.createProfile("English", "en")
        profileRepository.activateGlobal(english.id)

        val copiedEnglishSettings = repository.settings.first()
        assertTrue(copiedEnglishSettings.dictionaryTabDefault)
        assertFalse(copiedEnglishSettings.scanNonJapaneseText)
        assertEquals(12, copiedEnglishSettings.maxResults)
        assertEquals(20, copiedEnglishSettings.scanLength)
        assertEquals(26, copiedEnglishSettings.searchTextSize)
        assertEquals(DictionaryCollapseMode.Custom, copiedEnglishSettings.collapseMode)
        assertTrue(copiedEnglishSettings.expandFirstDictionary)
        assertEquals(setOf("JMdict"), copiedEnglishSettings.collapsedDictionaries)
        assertFalse(copiedEnglishSettings.compactGlossaries)
        assertTrue(copiedEnglishSettings.showExpressionTags)
        assertTrue(copiedEnglishSettings.harmonicFrequency)
        assertTrue(copiedEnglishSettings.deduplicatePitchAccents)
        assertFalse(copiedEnglishSettings.compactPitchAccents)
        assertEquals(".jp { color: red; }", copiedEnglishSettings.customCSS)

        repository.update {
            it.copy(
                autoUpdateDictionaries = true,
                dictionaryUpdateInterval = DictionaryUpdateInterval.Monthly,
                lastDictionaryUpdateEpochMillis = 1_910_000_000_000L,
                dictionaryTabDefault = false,
                scanNonJapaneseText = true,
                maxResults = 9,
                scanLength = 10,
                searchTextSize = 31,
                collapseMode = DictionaryCollapseMode.CollapseAll,
                expandFirstDictionary = false,
                collapsedDictionaries = setOf("Oxford"),
                compactGlossaries = true,
                showExpressionTags = false,
                harmonicFrequency = false,
                deduplicatePitchAccents = false,
                compactPitchAccents = true,
                lowRamDictionaryImport = false,
                customCSS = ".en { color: blue; }",
            )
        }
        profileRepository.activateGlobal(profileRepository.state.value.defaultProfileId)

        val japaneseSettings = repository.settings.first()
        assertTrue(japaneseSettings.autoUpdateDictionaries)
        assertEquals(DictionaryUpdateInterval.Monthly, japaneseSettings.dictionaryUpdateInterval)
        assertEquals(1_910_000_000_000L, japaneseSettings.lastDictionaryUpdateEpochMillis)
        assertFalse(japaneseSettings.lowRamDictionaryImport)
        assertTrue(japaneseSettings.dictionaryTabDefault)
        assertFalse(japaneseSettings.scanNonJapaneseText)
        assertEquals(12, japaneseSettings.maxResults)
        assertEquals(20, japaneseSettings.scanLength)
        assertEquals(26, japaneseSettings.searchTextSize)
        assertEquals(DictionaryCollapseMode.Custom, japaneseSettings.collapseMode)
        assertTrue(japaneseSettings.expandFirstDictionary)
        assertEquals(setOf("JMdict"), japaneseSettings.collapsedDictionaries)
        assertFalse(japaneseSettings.compactGlossaries)
        assertTrue(japaneseSettings.showExpressionTags)
        assertTrue(japaneseSettings.harmonicFrequency)
        assertTrue(japaneseSettings.deduplicatePitchAccents)
        assertFalse(japaneseSettings.compactPitchAccents)
        assertEquals(".jp { color: red; }", japaneseSettings.customCSS)

        profileRepository.activateGlobal(english.id)
        val englishSettings = repository.settings.first()
        assertFalse(englishSettings.dictionaryTabDefault)
        assertTrue(englishSettings.scanNonJapaneseText)
        assertEquals(9, englishSettings.maxResults)
        assertEquals(10, englishSettings.scanLength)
        assertEquals(31, englishSettings.searchTextSize)
        assertEquals(DictionaryCollapseMode.CollapseAll, englishSettings.collapseMode)
        assertFalse(englishSettings.expandFirstDictionary)
        assertEquals(setOf("Oxford"), englishSettings.collapsedDictionaries)
        assertTrue(englishSettings.compactGlossaries)
        assertFalse(englishSettings.showExpressionTags)
        assertFalse(englishSettings.harmonicFrequency)
        assertFalse(englishSettings.deduplicatePitchAccents)
        assertTrue(englishSettings.compactPitchAccents)
        assertEquals(".en { color: blue; }", englishSettings.customCSS)
    }

    @Test
    fun migratesProfileDictionarySettingsFromDataStore() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val profileRepository = ProfileRepository(filesDir)
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("dictionary-settings-migration.preferences_pb") },
        )
        try {
            DictionarySettingsRepository(dataStore).update {
                it.copy(
                    dictionaryTabDefault = true,
                    scanNonJapaneseText = false,
                    maxResults = 100,
                    scanLength = 0,
                    searchTextSize = 100,
                    collapseMode = DictionaryCollapseMode.Custom,
                    expandFirstDictionary = true,
                    collapsedDictionaries = setOf("DataStore"),
                    compactGlossaries = false,
                    showExpressionTags = true,
                    harmonicFrequency = true,
                    deduplicatePitchAccents = true,
                    compactPitchAccents = false,
                    customCSS = ".legacy { color: green; }",
                )
            }
            filesDir.resolve("Profiles/${profileRepository.state.value.defaultProfileId}/dictionary_collapsed.json")
                .writeProfileText("""["CollapsedFile"]""")

            val repository = DictionarySettingsRepository(
                dataStore = dataStore,
                profileRepository = profileRepository,
            )

            val migrated = repository.settings.first()
            assertTrue(profileRepository.dictionarySettingsFile().isFile)
            assertTrue(migrated.dictionaryTabDefault)
            assertFalse(migrated.scanNonJapaneseText)
            assertEquals(50, migrated.maxResults)
            assertEquals(1, migrated.scanLength)
            assertEquals(48, migrated.searchTextSize)
            assertEquals(DictionaryCollapseMode.Custom, migrated.collapseMode)
            assertTrue(migrated.expandFirstDictionary)
            assertEquals(setOf("DataStore"), migrated.collapsedDictionaries)
            assertFalse(migrated.compactGlossaries)
            assertTrue(migrated.showExpressionTags)
            assertTrue(migrated.harmonicFrequency)
            assertTrue(migrated.deduplicatePitchAccents)
            assertFalse(migrated.compactPitchAccents)
            assertEquals(".legacy { color: green; }", migrated.customCSS)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun profileJsonWithoutSearchTextSizeUsesIosDefault() = runBlocking {
        val profileRepository = ProfileRepository(tempFolder.newFolder("files"))
        profileRepository.dictionarySettingsFile().writeProfileText(
            """{"customCSS":".legacy { color: green; }"}""",
        )
        val repository = repository(profileRepository)

        val settings = repository.settings.first()

        assertEquals(22, settings.searchTextSize)
        assertEquals(".legacy { color: green; }", settings.customCSS)
    }

    private fun repository(
        profileRepository: ProfileRepository,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    ): DictionarySettingsRepository {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("dictionary-settings.preferences_pb") },
        )
        return DictionarySettingsRepository(
            dataStore = dataStore,
            profileRepository = profileRepository,
            ioDispatcher = ioDispatcher,
        ).also {
            tempFolder.root.deleteOnExit()
            Runtime.getRuntime().addShutdownHook(Thread { scope.cancel() })
        }
    }

    private fun java.io.File.writeProfileText(value: String) {
        parentFile?.mkdirs()
        writeText(value)
    }
}
