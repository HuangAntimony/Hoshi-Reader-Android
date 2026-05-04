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

class ReaderSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun emitsDefaultSettingsWhenThereIsNoLegacyStore() = runBlocking {
        repository().use { repository ->
            val settings = repository.settings.first()

            assertEquals(ReaderTheme.System, settings.theme)
            assertFalse(settings.eInkMode)
            assertFalse(settings.sepiaInvertInDark)
            assertTrue(settings.verticalWriting)
            assertEquals(ReaderFontManager.defaultMinchoFont, settings.selectedFont)
            assertEquals(22, settings.fontSize)
            assertFalse(settings.hideFurigana)
            assertEquals(5, settings.horizontalPadding)
            assertEquals(0, settings.verticalPadding)
            assertFalse(settings.avoidPageBreak)
            assertFalse(settings.justifyText)
            assertFalse(settings.layoutAdvanced)
            assertEquals(1.65, settings.lineHeight, 0.000001)
            assertEquals(0.0, settings.characterSpacing, 0.0)
            assertTrue(settings.showTitle)
            assertTrue(settings.showCharacters)
            assertTrue(settings.showPercentage)
            assertTrue(settings.showProgressTop)
            assertEquals(320, settings.popupWidth)
            assertEquals(250, settings.popupHeight)
            assertFalse(settings.popupActionBar)
            assertFalse(settings.popupFullWidth)
            assertTrue(settings.popupSwipeToDismiss)
            assertEquals(30, settings.popupSwipeThreshold)
            assertFalse(settings.volumeKeysTurnPages)
            assertFalse(settings.reverseVolumeKeyDirection)
        }
    }

    @Test
    fun migratesLegacySharedPreferencesSettingsOnceAndKeepsLoadNormalization() = runBlocking {
        val legacy = FakeLegacyReaderSettingsSource(
            ReaderSettings(
                theme = ReaderTheme.Dark,
                eInkMode = true,
                selectedFont = "Hiragino Mincho ProN",
                fontSize = 29,
                lineHeight = 1.9,
                popupSwipeThreshold = 120,
                volumeKeysTurnPages = true,
            ),
        )

        repository(legacy).use { repository ->
            val migrated = repository.settings.first()

            assertEquals(ReaderTheme.Dark, migrated.theme)
            assertTrue(migrated.eInkMode)
            assertEquals(ReaderFontManager.defaultMinchoFont, migrated.selectedFont)
            assertEquals(29, migrated.fontSize)
            assertEquals(1.9, migrated.lineHeight, 0.000001)
            assertEquals(60, migrated.popupSwipeThreshold)
            assertTrue(migrated.volumeKeysTurnPages)

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
                    sepiaInvertInDark = true,
                    verticalWriting = false,
                    selectedFont = ReaderFontManager.defaultGothicFont,
                    fontSize = 24,
                    hideFurigana = true,
                    horizontalPadding = 12,
                    verticalPadding = 6,
                    avoidPageBreak = true,
                    justifyText = true,
                    layoutAdvanced = true,
                    lineHeight = 1.8,
                    characterSpacing = 0.03,
                    showTitle = false,
                    showCharacters = false,
                    showPercentage = false,
                    showProgressTop = false,
                    popupWidth = 420,
                    popupHeight = 300,
                    popupActionBar = true,
                    popupFullWidth = true,
                    popupSwipeToDismiss = false,
                    popupSwipeThreshold = 35,
                    volumeKeysTurnPages = true,
                    reverseVolumeKeyDirection = true,
                )
            }

            val saved = repository.settings.first()

            assertEquals(ReaderTheme.Sepia, saved.theme)
            assertTrue(saved.sepiaInvertInDark)
            assertFalse(saved.verticalWriting)
            assertEquals(ReaderFontManager.defaultGothicFont, saved.selectedFont)
            assertEquals(24, saved.fontSize)
            assertTrue(saved.hideFurigana)
            assertEquals(12, saved.horizontalPadding)
            assertEquals(6, saved.verticalPadding)
            assertTrue(saved.avoidPageBreak)
            assertTrue(saved.justifyText)
            assertTrue(saved.layoutAdvanced)
            assertEquals(1.8, saved.lineHeight, 0.000001)
            assertEquals(0.03, saved.characterSpacing, 0.000001)
            assertFalse(saved.showTitle)
            assertFalse(saved.showCharacters)
            assertFalse(saved.showPercentage)
            assertFalse(saved.showProgressTop)
            assertEquals(420, saved.popupWidth)
            assertEquals(300, saved.popupHeight)
            assertTrue(saved.popupActionBar)
            assertTrue(saved.popupFullWidth)
            assertFalse(saved.popupSwipeToDismiss)
            assertEquals(35, saved.popupSwipeThreshold)
            assertTrue(saved.volumeKeysTurnPages)
            assertTrue(saved.reverseVolumeKeyDirection)
        }
    }

    @Test
    fun mainActivityUsesRepositoryFlowAndImmediateLocalStateAsTransitionAdapter() {
        val source = java.io.File("src/main/java/moe/antimony/hoshi/MainActivity.kt").readText()
        val container = java.io.File("src/main/java/moe/antimony/hoshi/HoshiAppContainer.kt").readText()

        assertTrue(container.contains("readerSettingsRepository()"))
        assertTrue(source.contains("val readerSettingsRepository = appContainer.readerSettingsRepository"))
        assertTrue(source.contains("LaunchedEffect(readerSettingsRepository)"))
        assertTrue(source.contains("readerSettingsRepository.settings.collect"))
        assertTrue(source.contains("readerSettings = settings"))
        assertTrue(source.contains("readerSettingsRepository.update { settings }"))
    }

    private fun repository(
        legacySource: ReaderSettingsLegacySource? = null,
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
