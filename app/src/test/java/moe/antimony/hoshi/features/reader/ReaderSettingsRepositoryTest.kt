package moe.antimony.hoshi.features.reader

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
import moe.antimony.hoshi.profiles.ProfileRepository
import moe.antimony.hoshi.testing.CountingCoroutineDispatcher

class ReaderSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

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
            assertEquals(22, settings.fontSize)
            assertFalse(settings.hideFurigana)
            assertEquals(ReaderViewMode.Paginated, settings.viewMode)
            assertFalse(settings.continuousMode)
            assertEquals(45, settings.visualNovelRevealSpeed)
            assertEquals(VisualNovelScreenMode.Block, settings.visualNovelScreenMode)
            assertEquals(1, settings.visualNovelSentencesPerScreen)
            assertFalse(settings.visualNovelPreserveDialogueBubbles)
            assertFalse(settings.visualNovelClickAdvance)
            assertFalse(settings.visualNovelMergeCrossScreenSasayakiCues)
            assertFalse(settings.blurImages)
            assertFalse(settings.enableStatistics)
            assertTrue(settings.showStatisticsTab)
            assertEquals(StatisticsAutostartMode.Off, settings.statisticsAutostartMode)
            assertFalse(settings.showStatisticsToggle)
            assertFalse(settings.showReadingSpeed)
            assertFalse(settings.showReadingTime)
            assertEquals(20, settings.chapterSwipeDistance)
            assertEquals(5, settings.horizontalPadding)
            assertEquals(0, settings.verticalPadding)
            assertEquals(18, settings.bottomSafeAreaDp)
            assertFalse(settings.avoidPageBreak)
            assertFalse(settings.justifyText)
            assertFalse(settings.layoutAdvanced)
            assertEquals(1.65, settings.lineHeight, 0.000001)
            assertEquals(0.0, settings.characterSpacing, 0.0)
            assertEquals(0.0, settings.paragraphSpacing, 0.0)
            assertTrue(settings.showTitle)
            assertTrue(settings.showCharacters)
            assertTrue(settings.showPercentage)
            assertTrue(settings.alwaysShowProgress)
            assertTrue(settings.showProgressTop)
            assertTrue(settings.showReaderBackButton)
            assertEquals(320, settings.popupWidth)
            assertEquals(250, settings.popupHeight)
            assertEquals(1.0, settings.popupScale, 0.000001)
            assertFalse(settings.popupActionBar)
            assertFalse(settings.popupFullWidth)
            assertTrue(settings.popupSwipeToDismiss)
            assertEquals(30, settings.popupSwipeThreshold)
            assertFalse(settings.volumeKeysTurnPages)
            assertFalse(settings.volumeKeysSeekSasayaki)
            assertFalse(settings.reverseVolumeKeyDirection)
            assertFalse(settings.keepScreenOnWhileReading)
            assertFalse(settings.lockCurrentOrientation)
            assertFalse(settings.openLastReadBookOnLaunch)
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
                bottomSafeAreaDp = 100,
                lineHeight = 1.9,
                paragraphSpacing = 2.2,
                popupSwipeThreshold = 120,
                volumeKeysTurnPages = true,
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
            assertEquals(72, migrated.bottomSafeAreaDp)
            assertEquals(1.9, migrated.lineHeight, 0.000001)
            assertEquals(2.2, migrated.paragraphSpacing, 0.000001)
            assertEquals(60, migrated.popupSwipeThreshold)
            assertTrue(migrated.volumeKeysTurnPages)
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
                    fontSize = 24,
                    hideFurigana = true,
                    viewMode = ReaderViewMode.VisualNovel,
                    visualNovelRevealSpeed = 80,
                    visualNovelScreenMode = VisualNovelScreenMode.Sentences,
                    visualNovelSentencesPerScreen = 3,
                    visualNovelPreserveDialogueBubbles = true,
                    visualNovelClickAdvance = false,
                    visualNovelMergeCrossScreenSasayakiCues = true,
                    blurImages = true,
                    enableStatistics = true,
                    showStatisticsTab = false,
                    statisticsAutostartMode = StatisticsAutostartMode.PageTurn,
                    showStatisticsToggle = true,
                    showReadingSpeed = true,
                    showReadingTime = true,
                    chapterSwipeDistance = 35,
                    horizontalPadding = 12,
                    verticalPadding = 6,
                    bottomSafeAreaDp = 40,
                    avoidPageBreak = true,
                    justifyText = true,
                    layoutAdvanced = true,
                    lineHeight = 1.8,
                    characterSpacing = 0.03,
                    paragraphSpacing = 1.7,
                    showTitle = false,
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
            assertEquals(24, saved.fontSize)
            assertTrue(saved.hideFurigana)
            assertEquals(ReaderViewMode.VisualNovel, saved.viewMode)
            assertFalse(saved.continuousMode)
            assertEquals(80, saved.visualNovelRevealSpeed)
            assertEquals(VisualNovelScreenMode.Sentences, saved.visualNovelScreenMode)
            assertEquals(3, saved.visualNovelSentencesPerScreen)
            assertTrue(saved.visualNovelPreserveDialogueBubbles)
            assertFalse(saved.visualNovelClickAdvance)
            assertTrue(saved.visualNovelMergeCrossScreenSasayakiCues)
            assertTrue(saved.blurImages)
            assertTrue(saved.enableStatistics)
            assertFalse(saved.showStatisticsTab)
            assertEquals(StatisticsAutostartMode.PageTurn, saved.statisticsAutostartMode)
            assertTrue(saved.showStatisticsToggle)
            assertTrue(saved.showReadingSpeed)
            assertTrue(saved.showReadingTime)
            assertEquals(35, saved.chapterSwipeDistance)
            assertEquals(12, saved.horizontalPadding)
            assertEquals(6, saved.verticalPadding)
            assertEquals(40, saved.bottomSafeAreaDp)
            assertTrue(saved.avoidPageBreak)
            assertTrue(saved.justifyText)
            assertTrue(saved.layoutAdvanced)
            assertEquals(1.8, saved.lineHeight, 0.000001)
            assertEquals(0.03, saved.characterSpacing, 0.000001)
            assertEquals(1.7, saved.paragraphSpacing, 0.000001)
            assertFalse(saved.showTitle)
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
    fun falseToTrueStatisticsRepositoryUpdateEnablesDisplayControls() = runBlocking {
        repository().use { repository ->
            repository.update {
                it.copy(
                    enableStatistics = true,
                    showStatisticsToggle = false,
                    showReadingSpeed = false,
                    showReadingTime = false,
                )
            }

            val saved = repository.settings.first()

            assertTrue(saved.enableStatistics)
            assertTrue(saved.showStatisticsToggle)
            assertTrue(saved.showReadingSpeed)
            assertTrue(saved.showReadingTime)
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
                    popupWidth = 440,
                    bottomSafeAreaDp = 44,
                    visualNovelMergeCrossScreenSasayakiCues = true,
                    showStatisticsTab = false,
                    volumeKeysTurnPages = true,
                    lockCurrentOrientation = true,
                    openLastReadBookOnLaunch = true,
                )
            }

            val english = profileRepository.createProfile("English", "en")
            profileRepository.activateGlobal(english.id)
            val inherited = repository.settings.first()
            assertEquals(ReaderTheme.Dark, inherited.theme)
            assertEquals(30, inherited.fontSize)
            assertEquals(440, inherited.popupWidth)
            assertEquals(44, inherited.bottomSafeAreaDp)
            assertTrue(inherited.visualNovelMergeCrossScreenSasayakiCues)
            assertFalse(inherited.showStatisticsTab)
            assertTrue(inherited.volumeKeysTurnPages)
            assertTrue(inherited.lockCurrentOrientation)
            assertTrue(inherited.openLastReadBookOnLaunch)

            repository.update {
                it.copy(
                    theme = ReaderTheme.Light,
                    fontSize = 18,
                    popupWidth = 280,
                    bottomSafeAreaDp = 60,
                    visualNovelMergeCrossScreenSasayakiCues = false,
                    showStatisticsTab = true,
                    volumeKeysTurnPages = false,
                    lockCurrentOrientation = false,
                    openLastReadBookOnLaunch = false,
                )
            }

            profileRepository.activateGlobal(profileRepository.state.value.defaultProfileId)
            val japanese = repository.settings.first()
            assertEquals(ReaderTheme.Dark, japanese.theme)
            assertEquals(30, japanese.fontSize)
            assertEquals(440, japanese.popupWidth)
            assertEquals(44, japanese.bottomSafeAreaDp)
            assertTrue(japanese.visualNovelMergeCrossScreenSasayakiCues)
            assertTrue(japanese.showStatisticsTab)
            assertFalse(japanese.volumeKeysTurnPages)
            assertFalse(japanese.lockCurrentOrientation)
            assertFalse(japanese.openLastReadBookOnLaunch)
        }
    }

    private fun repository(
        legacySource: ReaderSettingsLegacySource? = null,
        profileRepository: ProfileRepository? = null,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    ): RepositoryHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("reader-settings.preferences_pb") },
        )
        return RepositoryHandle(
            repository = ReaderSettingsRepository(
                dataStore = dataStore,
                legacySource = legacySource,
                profileRepository = profileRepository,
                ioDispatcher = ioDispatcher,
            ),
            scope = scope,
        )
    }

    private class RepositoryHandle(
        private val repository: ReaderSettingsRepository,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        val settings: Flow<ReaderSettings>
            get() = repository.settings

        suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
            repository.update(transform)
        }

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
