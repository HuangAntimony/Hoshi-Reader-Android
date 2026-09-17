package moe.antimony.hoshi.features.reader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import moe.antimony.hoshi.profiles.ProfileRepository
import moe.antimony.hoshi.testing.CountingCoroutineDispatcher

class ReaderSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun statisticsSyncDefaultsOnWithoutStartingTrackingOrChangingDisplayPreferences() = runBlocking {
        repository().use { repository ->
            val settings = repository.settings.first()
            assertTrue(settings.statisticsSyncEnabled)
            assertFalse(settings.statisticsAutostartOnBookOpen)
            assertFalse(settings.statisticsAutostartOnPageTurn)
            assertFalse(settings.showStatisticsToggle)
            assertFalse(settings.showReadingSpeed)
            assertFalse(settings.showReadingTime)
        }
    }

    @Test
    fun obsoleteDisabledStatisticsPreferencesDoNotChangeOtherPreferences() = runBlocking {
        repository().use { repository ->
            repository.editPreferences {
                this[booleanPreferencesKey("readerSettingsMigratedFromSharedPreferences")] = true
                this[booleanPreferencesKey("enableStatistics")] = false
                this[booleanPreferencesKey("showStatisticsTab")] = false
                this[booleanPreferencesKey("statisticsEnableSync")] = false
                this[booleanPreferencesKey("readerShowReadingSpeed")] = true
            }
            val before = repository.settings.first()
            assertFalse(before.statisticsSyncEnabled)
            assertTrue(before.showReadingSpeed)
            assertFalse(before.showReadingTime)
            repository.update { it.copy(statisticsResetMinutes = 270) }
            val after = repository.settings.first()
            assertEquals(270, after.statisticsResetMinutes)
            assertFalse(after.statisticsSyncEnabled)
            assertTrue(after.showReadingSpeed)
            assertFalse(after.showReadingTime)
        }
    }

    @Test
    fun storedStatisticsSyncOptOutSurvivesUnrelatedSettingsUpdates() = runBlocking {
        repository().use { repository ->
            repository.update { it.copy(statisticsSyncEnabled = false) }
            repository.update { it.copy(fontSize = 28) }
            assertFalse(repository.settings.first().statisticsSyncEnabled)
        }
    }

    @Test
    fun profileAppearanceReadsAndWritesUseInjectedIoDispatcher() = runBlocking {
        CountingCoroutineDispatcher().use { ioDispatcher ->
            val profileRepository = ProfileRepository(
                filesDir = tempFolder.newFolder("files"),
                ioDispatcher = ioDispatcher,
            )
            repository(
                profileRepository = profileRepository,
                ioDispatcher = ioDispatcher,
            ).use { repository ->
                val beforeProfileAccess = ioDispatcher.dispatchCount

                repository.update { it.copy(fontSize = 28) }
                assertEquals(28, repository.settings.first().fontSize)

                assertTrue(ioDispatcher.dispatchCount >= beforeProfileAccess + 2)
            }
        }
    }

    @Test
    fun emitsDefaultSettingsWhenThereIsNoLegacyStore() = runBlocking {
        repository().use { repository ->
            val settings = repository.settings.first()

            assertEquals(ReaderTheme.System, settings.theme)
            assertFalse(settings.eInkMode)
            assertFalse(settings.systemLightSepia)
            assertFalse(settings.sepiaInvertInDark)
            assertEquals(ReaderInterfaceTheme.System, settings.uiTheme)
            assertEquals(0xFFFFFFFFL, settings.customBackgroundColor)
            assertEquals(0xFF000000L, settings.customTextColor)
            assertEquals(0xFF999999L, settings.customInfoColor)
            assertTrue(settings.verticalWriting)
            assertEquals(ReaderFontManager.defaultMinchoFont, settings.selectedFont)
            assertEquals(null, settings.selectedFontFamilyId)
            assertEquals(null, settings.selectedFontVariantId)
            assertTrue(settings.fontVariantSelections.isEmpty())
            assertEquals(22, settings.fontSize)
            assertEquals(FuriganaMode.Off, settings.furiganaMode)
            assertEquals(ReaderViewMode.Paginated, settings.viewMode)
            assertFalse(settings.continuousMode)
            assertEquals(45, settings.visualNovelRevealSpeed)
            assertEquals(VisualNovelScreenMode.Block, settings.visualNovelScreenMode)
            assertEquals(1, settings.visualNovelSentencesPerScreen)
            assertFalse(settings.visualNovelPreserveDialogueBubbles)
            assertFalse(settings.visualNovelClickAdvance)
            assertFalse(settings.visualNovelMergeCrossScreenSasayakiCues)
            assertFalse(settings.blurImages)
            assertFalse(settings.statisticsAutostartOnBookOpen)
            assertFalse(settings.statisticsAutostartOnPageTurn)
            assertEquals(0, settings.statisticsResetMinutes)
            assertFalse(settings.showStatisticsToggle)
            assertFalse(settings.showReadingSpeed)
            assertFalse(settings.showReadingTime)
            assertEquals(20, settings.chapterSwipeDistance)
            assertEquals(72, settings.pageSwipeThresholdPx)
            assertEquals(5, settings.horizontalPadding)
            assertEquals(0, settings.verticalPadding)
            assertEquals(30, settings.topSafeAreaDp)
            assertEquals(18, settings.bottomSafeAreaDp)
            assertFalse(settings.avoidPageBreak)
            assertFalse(settings.justifyText)
            assertFalse(settings.layoutAdvanced)
            assertEquals(1.65, settings.lineHeight, 0.000001)
            assertEquals(0.0, settings.characterSpacing, 0.0)
            assertEquals(0.0, settings.paragraphSpacing, 0.0)
            assertTrue(settings.showTitle)
            assertTrue(settings.showProgress)
            assertFalse(settings.showChapterProgress)
            assertTrue(settings.showCharacters)
            assertTrue(settings.showPercentage)
            assertTrue(settings.alwaysShowProgress)
            assertTrue(settings.showProgressTop)
            assertTrue(settings.showReaderBackButton)
            assertEquals(500, settings.popupWidth)
            assertEquals(500, settings.popupHeight)
            assertEquals(1.0, settings.popupScale, 0.000001)
            assertFalse(settings.popupActionBar)
            assertFalse(settings.popupFullWidth)
            assertTrue(settings.popupSwipeToDismiss)
            assertEquals(30, settings.popupSwipeThreshold)
            assertFalse(settings.volumeKeysTurnPages)
            assertFalse(settings.volumeKeysNavigatePopupTerms)
            assertFalse(settings.volumeKeysSeekSasayaki)
            assertFalse(settings.reverseVolumeKeyDirection)
            assertFalse(settings.keepScreenOnWhileReading)
            assertFalse(settings.lockCurrentOrientation)
            assertFalse(settings.openLastReadBookOnLaunch)
        }
    }

    @Test
    fun legacyDataStoreAutostartModesMigrateWithoutChangingBehavior() = runBlocking {
        val cases = listOf(
            null to (false to false),
            "Off" to (false to false),
            "On" to (true to false),
            "Page Turn" to (false to true),
            "Unexpected" to (false to false),
        )

        cases.forEachIndexed { index, (rawValue, expected) ->
            repository(fileName = "reader-settings-$index.preferences_pb").use { repository ->
                repository.editPreferences {
                    this[booleanPreferencesKey("readerSettingsMigratedFromSharedPreferences")] = true
                    rawValue?.let { this[stringPreferencesKey("statisticsAutostartMode")] = it }
                }

                val migrated = repository.settings.first()
                val stored = repository.preferences()

                assertEquals(expected.first, migrated.statisticsAutostartOnBookOpen)
                assertEquals(expected.second, migrated.statisticsAutostartOnPageTurn)
                assertEquals(expected.first, stored[booleanPreferencesKey("statisticsAutostartOnBookOpen")])
                assertEquals(expected.second, stored[booleanPreferencesKey("statisticsAutostartOnPageTurn")])
                assertNull(stored[stringPreferencesKey("statisticsAutostartMode")])
            }
        }
    }

    @Test
    fun existingAutostartTriggerWinsWhileMissingTriggerMigratesFromLegacyMode() = runBlocking {
        repository().use { repository ->
            repository.editPreferences {
                this[booleanPreferencesKey("readerSettingsMigratedFromSharedPreferences")] = true
                this[stringPreferencesKey("statisticsAutostartMode")] = "Page Turn"
                this[booleanPreferencesKey("statisticsAutostartOnBookOpen")] = true
            }

            val migrated = repository.settings.first()
            val stored = repository.preferences()

            assertTrue(migrated.statisticsAutostartOnBookOpen)
            assertTrue(migrated.statisticsAutostartOnPageTurn)
            assertEquals(true, stored[booleanPreferencesKey("statisticsAutostartOnBookOpen")])
            assertEquals(true, stored[booleanPreferencesKey("statisticsAutostartOnPageTurn")])
            assertNull(stored[stringPreferencesKey("statisticsAutostartMode")])
        }
    }

    @Test
    fun persistsEveryStatisticsAutostartTriggerCombination() = runBlocking {
        repository().use { repository ->
            val combinations = listOf(
                false to false,
                true to false,
                false to true,
                true to true,
            )

            combinations.forEach { (onBookOpen, onPageTurn) ->
                repository.update {
                    it.copy(
                        statisticsAutostartOnBookOpen = onBookOpen,
                        statisticsAutostartOnPageTurn = onPageTurn,
                    )
                }

                val saved = repository.settings.first()
                assertEquals(onBookOpen, saved.statisticsAutostartOnBookOpen)
                assertEquals(onPageTurn, saved.statisticsAutostartOnPageTurn)
            }
        }
    }

    @Test
    fun migratesLegacySharedPreferencesSettingsOnceAndKeepsLoadNormalization() = runBlocking {
        val legacy = FakeLegacyReaderSettingsSource(
            ReaderSettings(
                theme = ReaderTheme.Dark,
                eInkMode = true,
                uiTheme = ReaderInterfaceTheme.Dark,
                customBackgroundColor = 0xFF112233,
                customTextColor = 0xFF445566,
                customInfoColor = 0xFF778899,
                selectedFont = ReaderFontManager.defaultMinchoFont,
                fontSize = 29,
                viewMode = ReaderViewMode.Continuous,
                chapterSwipeDistance = 120,
                pageSwipeThresholdPx = 500,
                topSafeAreaDp = 100,
                bottomSafeAreaDp = 100,
                lineHeight = 1.9,
                paragraphSpacing = 2.2,
                popupSwipeThreshold = 120,
                volumeKeysTurnPages = true,
                volumeKeysNavigatePopupTerms = true,
                volumeKeysSeekSasayaki = true,
                keepScreenOnWhileReading = true,
                lockCurrentOrientation = true,
                openLastReadBookOnLaunch = true,
            ),
        )

        repository(legacy).use { repository ->
            val migrated = repository.settings.first()

            assertEquals(ReaderTheme.Dark, migrated.theme)
            assertTrue(migrated.eInkMode)
            assertEquals(ReaderInterfaceTheme.Dark, migrated.uiTheme)
            assertEquals(0xFF112233, migrated.customBackgroundColor)
            assertEquals(0xFF445566, migrated.customTextColor)
            assertEquals(0xFF778899, migrated.customInfoColor)
            assertEquals(ReaderFontManager.defaultMinchoFont, migrated.selectedFont)
            assertEquals(29, migrated.fontSize)
            assertEquals(ReaderViewMode.Continuous, migrated.viewMode)
            assertTrue(migrated.continuousMode)
            assertEquals(60, migrated.chapterSwipeDistance)
            assertEquals(360, migrated.pageSwipeThresholdPx)
            assertEquals(72, migrated.topSafeAreaDp)
            assertEquals(72, migrated.bottomSafeAreaDp)
            assertEquals(1.9, migrated.lineHeight, 0.000001)
            assertEquals(2.2, migrated.paragraphSpacing, 0.000001)
            assertEquals(60, migrated.popupSwipeThreshold)
            assertTrue(migrated.volumeKeysTurnPages)
            assertTrue(migrated.volumeKeysNavigatePopupTerms)
            assertTrue(migrated.volumeKeysSeekSasayaki)
            assertTrue(migrated.keepScreenOnWhileReading)
            assertTrue(migrated.lockCurrentOrientation)
            assertTrue(migrated.openLastReadBookOnLaunch)

            repository.update { it.copy(fontSize = 31) }
            assertEquals(31, repository.settings.first().fontSize)
            assertEquals(1, legacy.loadCount)
        }
    }

    @Test
    fun updatePersistsSettingsAndPreservesReaderStoreTypes() = runBlocking {
        repository().use { repository ->
            repository.update { current ->
                current.copy(
                    theme = ReaderTheme.Sepia,
                    uiTheme = ReaderInterfaceTheme.Dark,
                    systemLightSepia = true,
                    sepiaInvertInDark = true,
                    customBackgroundColor = 0xFF102030,
                    customTextColor = 0xFF405060,
                    customInfoColor = 0xFF708090,
                    verticalWriting = false,
                    selectedFont = ReaderFontManager.defaultGothicFont,
                    selectedFontFamilyId = ReaderFontManager.systemGothicFamilyId,
                    selectedFontVariantId = "wght-600-normal",
                    fontVariantSelections = mapOf(
                        ReaderFontManager.systemGothicFamilyId to "wght-600-normal",
                        "recommended:kleeone" to "wght-400-normal",
                    ),
                    fontSize = 24,
                    furiganaMode = FuriganaMode.Hidden,
                    viewMode = ReaderViewMode.VisualNovel,
                    visualNovelRevealSpeed = 80,
                    visualNovelScreenMode = VisualNovelScreenMode.Sentences,
                    visualNovelSentencesPerScreen = 3,
                    visualNovelPreserveDialogueBubbles = true,
                    visualNovelClickAdvance = false,
                    visualNovelMergeCrossScreenSasayakiCues = true,
                    blurImages = true,
                    statisticsAutostartOnBookOpen = true,
                    statisticsAutostartOnPageTurn = true,
                    showStatisticsToggle = true,
                    showReadingSpeed = true,
                    showReadingTime = true,
                    chapterSwipeDistance = 35,
                    pageSwipeThresholdPx = 108,
                    horizontalPadding = 12,
                    verticalPadding = 6,
                    topSafeAreaDp = 40,
                    bottomSafeAreaDp = 40,
                    avoidPageBreak = true,
                    justifyText = true,
                    layoutAdvanced = true,
                    lineHeight = 1.8,
                    characterSpacing = 0.03,
                    paragraphSpacing = 1.7,
                    showTitle = false,
                    showProgress = false,
                    showChapterProgress = true,
                    showCharacters = false,
                    showPercentage = false,
                    alwaysShowProgress = false,
                    showProgressTop = false,
                    showReaderBackButton = false,
                    popupWidth = 420,
                    popupHeight = 300,
                    popupScale = 1.25,
                    popupActionBar = true,
                    popupFullWidth = true,
                    popupSwipeToDismiss = false,
                    popupSwipeThreshold = 35,
                    volumeKeysTurnPages = true,
                    volumeKeysNavigatePopupTerms = true,
                    volumeKeysSeekSasayaki = true,
                    reverseVolumeKeyDirection = true,
                    keepScreenOnWhileReading = true,
                    lockCurrentOrientation = true,
                    openLastReadBookOnLaunch = true,
                )
            }

            val saved = repository.settings.first()

            assertEquals(ReaderTheme.Sepia, saved.theme)
            assertEquals(ReaderInterfaceTheme.Dark, saved.uiTheme)
            assertTrue(saved.systemLightSepia)
            assertTrue(saved.sepiaInvertInDark)
            assertEquals(0xFF102030, saved.customBackgroundColor)
            assertEquals(0xFF405060, saved.customTextColor)
            assertEquals(0xFF708090, saved.customInfoColor)
            assertFalse(saved.verticalWriting)
            assertEquals(ReaderFontManager.defaultGothicFont, saved.selectedFont)
            assertEquals(ReaderFontManager.systemGothicFamilyId, saved.selectedFontFamilyId)
            assertEquals("wght-600-normal", saved.selectedFontVariantId)
            assertEquals("wght-400-normal", saved.fontVariantSelections["recommended:kleeone"])
            assertEquals(24, saved.fontSize)
            assertEquals(FuriganaMode.Hidden, saved.furiganaMode)
            assertEquals(ReaderViewMode.VisualNovel, saved.viewMode)
            assertFalse(saved.continuousMode)
            assertEquals(80, saved.visualNovelRevealSpeed)
            assertEquals(VisualNovelScreenMode.Sentences, saved.visualNovelScreenMode)
            assertEquals(3, saved.visualNovelSentencesPerScreen)
            assertTrue(saved.visualNovelPreserveDialogueBubbles)
            assertFalse(saved.visualNovelClickAdvance)
            assertTrue(saved.visualNovelMergeCrossScreenSasayakiCues)
            assertTrue(saved.blurImages)
            assertTrue(saved.statisticsAutostartOnBookOpen)
            assertTrue(saved.statisticsAutostartOnPageTurn)
            assertTrue(saved.showStatisticsToggle)
            assertTrue(saved.showReadingSpeed)
            assertTrue(saved.showReadingTime)
            assertEquals(35, saved.chapterSwipeDistance)
            assertEquals(108, saved.pageSwipeThresholdPx)
            assertEquals(12, saved.horizontalPadding)
            assertEquals(6, saved.verticalPadding)
            assertEquals(40, saved.topSafeAreaDp)
            assertEquals(40, saved.bottomSafeAreaDp)
            assertTrue(saved.avoidPageBreak)
            assertTrue(saved.justifyText)
            assertTrue(saved.layoutAdvanced)
            assertEquals(1.8, saved.lineHeight, 0.000001)
            assertEquals(0.03, saved.characterSpacing, 0.000001)
            assertEquals(1.7, saved.paragraphSpacing, 0.000001)
            assertFalse(saved.showTitle)
            assertFalse(saved.showProgress)
            assertTrue(saved.showChapterProgress)
            assertFalse(saved.showCharacters)
            assertFalse(saved.showPercentage)
            assertFalse(saved.alwaysShowProgress)
            assertFalse(saved.showProgressTop)
            assertFalse(saved.showReaderBackButton)
            assertEquals(420, saved.popupWidth)
            assertEquals(300, saved.popupHeight)
            assertEquals(1.25, saved.popupScale, 0.000001)
            assertTrue(saved.popupActionBar)
            assertTrue(saved.popupFullWidth)
            assertFalse(saved.popupSwipeToDismiss)
            assertEquals(35, saved.popupSwipeThreshold)
            assertTrue(saved.volumeKeysTurnPages)
            assertTrue(saved.volumeKeysNavigatePopupTerms)
            assertTrue(saved.volumeKeysSeekSasayaki)
            assertTrue(saved.reverseVolumeKeyDirection)
            assertTrue(saved.keepScreenOnWhileReading)
            assertTrue(saved.lockCurrentOrientation)
            assertTrue(saved.openLastReadBookOnLaunch)
        }
    }

    @Test
    fun popupScalePersistsUpToTwoPointZero() = runBlocking {
        repository().use { repository ->
            repository.update { it.copy(popupScale = 2.0) }

            val saved = repository.settings.first()

            assertEquals(2.0, saved.popupScale, 0.000001)
        }
    }

    @Test
    fun statisticsResetMinutesPersistAsMinuteOfDay() = runBlocking {
        repository().use { repository ->
            repository.update { it.copy(statisticsResetMinutes = 105) }

            assertEquals(105, repository.settings.first().statisticsResetMinutes)
        }
    }

    @Test
    fun profileModeScopesAppearanceFieldsButKeepsBehaviorFieldsGlobal() = runBlocking {
        val profileRepository = ProfileRepository(tempFolder.newFolder("files"))
        repository(profileRepository = profileRepository).use { repository ->
            repository.update {
                it.copy(
                    theme = ReaderTheme.Dark,
                    fontSize = 30,
                    furiganaMode = FuriganaMode.Toggle,
                    popupWidth = 440,
                    pageSwipeThresholdPx = 96,
                    topSafeAreaDp = 46,
                    bottomSafeAreaDp = 44,
                    visualNovelMergeCrossScreenSasayakiCues = true,
                    volumeKeysTurnPages = true,
                    volumeKeysNavigatePopupTerms = true,
                    lockCurrentOrientation = true,
                    openLastReadBookOnLaunch = true,
                )
            }

            val english = profileRepository.createProfile("English", "en")
            profileRepository.activateGlobal(english.id)
            val inherited = repository.settings.first()
            assertEquals(ReaderTheme.Dark, inherited.theme)
            assertEquals(30, inherited.fontSize)
            assertEquals(FuriganaMode.Toggle, inherited.furiganaMode)
            assertEquals(440, inherited.popupWidth)
            assertEquals(96, inherited.pageSwipeThresholdPx)
            assertEquals(46, inherited.topSafeAreaDp)
            assertEquals(44, inherited.bottomSafeAreaDp)
            assertTrue(inherited.visualNovelMergeCrossScreenSasayakiCues)
            assertTrue(inherited.volumeKeysTurnPages)
            assertTrue(inherited.volumeKeysNavigatePopupTerms)
            assertTrue(inherited.lockCurrentOrientation)
            assertTrue(inherited.openLastReadBookOnLaunch)

            repository.update {
                it.copy(
                    theme = ReaderTheme.Light,
                    fontSize = 18,
                    furiganaMode = FuriganaMode.Dimmed,
                    popupWidth = 280,
                    pageSwipeThresholdPx = 120,
                    topSafeAreaDp = 58,
                    bottomSafeAreaDp = 60,
                    visualNovelMergeCrossScreenSasayakiCues = false,
                    volumeKeysTurnPages = false,
                    volumeKeysNavigatePopupTerms = false,
                    lockCurrentOrientation = false,
                    openLastReadBookOnLaunch = false,
                )
            }

            profileRepository.activateGlobal(profileRepository.state.value.defaultProfileId)
            val japanese = repository.settings.first()
            assertEquals(ReaderTheme.Dark, japanese.theme)
            assertEquals(30, japanese.fontSize)
            assertEquals(FuriganaMode.Toggle, japanese.furiganaMode)
            assertEquals(440, japanese.popupWidth)
            assertEquals(96, japanese.pageSwipeThresholdPx)
            assertEquals(46, japanese.topSafeAreaDp)
            assertEquals(44, japanese.bottomSafeAreaDp)
            assertTrue(japanese.visualNovelMergeCrossScreenSasayakiCues)
            assertFalse(japanese.volumeKeysTurnPages)
            assertFalse(japanese.volumeKeysNavigatePopupTerms)
            assertFalse(japanese.lockCurrentOrientation)
            assertFalse(japanese.openLastReadBookOnLaunch)
        }
    }

    @Test
    fun legacyFuriganaMigratesAndExplicitModeWins() = runBlocking {
        repository().use { repository ->
            repository.editPreferences {
                this[booleanPreferencesKey("readerSettingsMigratedFromSharedPreferences")] = true
                this[booleanPreferencesKey("readerHideFurigana")] = true
            }
            assertEquals(FuriganaMode.Hidden, repository.settings.first().furiganaMode)
            repository.update { it.copy(furiganaMode = FuriganaMode.Toggle) }
            assertEquals(FuriganaMode.Toggle, repository.settings.first().furiganaMode)
            repository.editPreferences {
                this[booleanPreferencesKey("readerHideFurigana")] = true
            }
            assertEquals(FuriganaMode.Toggle, repository.settings.first().furiganaMode)
            for (mode in FuriganaMode.entries) {
                repository.update { it.copy(furiganaMode = mode) }
                assertEquals(mode, repository.settings.first().furiganaMode)
            }
        }
    }

    @Test
    fun legacyProfileFuriganaMigratesAndPersistsNewMode() = runBlocking {
        val profiles = ProfileRepository(tempFolder.newFolder("furigana-profiles"))
        val file = profiles.readerSettingsFile()
        file.parentFile?.mkdirs()
        file.writeText("""{"hideFurigana":true}""")
        repository(profileRepository = profiles).use { repository ->
            assertEquals(FuriganaMode.Hidden, repository.settings.first().furiganaMode)
            repository.update { it.copy(furiganaMode = FuriganaMode.Dimmed) }
        }
        repository(profileRepository = profiles, fileName = "furigana-reopened.preferences_pb").use { repository ->
            assertEquals(FuriganaMode.Dimmed, repository.settings.first().furiganaMode)
        }
    }

    private fun repository(
        legacySource: ReaderSettingsLegacySource? = null,
        profileRepository: ProfileRepository? = null,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
        fileName: String = "reader-settings.preferences_pb",
    ): RepositoryHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile(fileName) },
        )
        return RepositoryHandle(
            repository = ReaderSettingsRepository(
                dataStore = dataStore,
                legacySource = legacySource,
                profileRepository = profileRepository,
                ioDispatcher = ioDispatcher,
            ),
            dataStore = dataStore,
            scope = scope,
        )
    }

    private class RepositoryHandle(
        private val repository: ReaderSettingsRepository,
        private val dataStore: DataStore<Preferences>,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        val settings: Flow<ReaderSettings>
            get() = repository.settings

        suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
            repository.update(transform)
        }

        suspend fun editPreferences(transform: suspend MutablePreferences.() -> Unit) {
            dataStore.edit { preferences -> preferences.transform() }
        }

        suspend fun preferences(): Preferences = dataStore.data.first()

        override fun close() {
            scope.cancel()
        }
    }

    private class FakeLegacyReaderSettingsSource(
        private val settings: ReaderSettings,
    ) : ReaderSettingsLegacySource {
        var loadCount = 0
            private set

        override fun load(): ReaderSettings {
            loadCount += 1
            return settings
        }
    }
}
