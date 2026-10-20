package moe.antimony.hoshi.features.statistics

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.epub.StatisticsArchiveSummary
import moe.antimony.hoshi.features.settings.SettingsLoadState
import org.junit.Assert.*
import moe.antimony.hoshi.ui.resolve
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsSettingsViewModelTest {
    @Test
    fun mixedHealthyAndCorruptArchiveKeepsSettingsCountAndClearAction() = verifyCorruptArchiveRecovery(1)

    @Test
    fun corruptOnlyArchiveKeepsSettingsAndClearAction() = verifyCorruptArchiveRecovery(0)

    private fun verifyCorruptArchiveRecovery(healthyCount: Int) = runTest {
        val files = java.nio.file.Files.createTempDirectory("hoshi-archive-partial").toFile()
        try {
            val archive = files.resolve("Books/statistics_archive")
            if (healthyCount > 0) {
                archive.resolve("healthy").apply { mkdirs() }.resolve("statistics.json").writeText(
                    """[{"title":"Healthy","dateKey":"2026-10-17","charactersRead":10}]""",
                )
            }
            archive.resolve("corrupt-book").apply { mkdirs() }.resolve("statistics.json").writeText("broken")
            val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
            val store = moe.antimony.hoshi.epub.BookStatisticsStore(files, dispatcher)
            val originalError = requireNotNull(runCatching { store.load(archive.resolve("corrupt-book")) }.exceptionOrNull())
            val repository = AndroidStatisticsRepository(store, dispatcher)
            val model = StatisticsSettingsViewModel(
                MutableStateFlow(ReaderSettings(statisticsResetMinutes = 315)),
                {},
                repository,
                backgroundScope,
            )
            runCurrent()
            assertTrue(model.uiState.value.loadState is SettingsLoadState.Loaded)
            assertEquals(315, model.uiState.value.settings?.statisticsResetMinutes)
            assertEquals(healthyCount, model.uiState.value.archivedBookCount)
            val loaded = model.uiState.value.loadState as SettingsLoadState.Loaded
            assertTrue(loaded.value.archiveSummary.hasArchivedData)
            assertEquals(healthyCount + 1, loaded.value.archiveSummary.totalBookCount)
            val message = requireNotNull(model.uiState.value.error).resolve(
                { _, _ -> "Some statistics are unavailable." }, { _, _, _ -> "" },
            )
            assertTrue(message.contains("corrupt-book"))
            assertTrue(message.contains(requireNotNull(originalError.message)))
            model.dismissError()
            model.clearArchive()
            runCurrent()
            assertFalse(archive.resolve("corrupt-book").exists())
            assertEquals(0, model.uiState.value.archivedBookCount)
            assertFalse((model.uiState.value.loadState as SettingsLoadState.Loaded).value.archiveSummary.hasArchivedData)
            assertTrue(model.uiState.value.loadState is SettingsLoadState.Loaded)
            assertNull(model.uiState.value.error)
        } finally {
            files.deleteRecursively()
        }
    }

    @Test
    fun delayedPreferencesWaitForStoredSettingsAndArchiveCount() = runTest {
        val firstSettings = CompletableDeferred<ReaderSettings>()
        val persisted = ReaderSettings(statisticsResetMinutes = 315)
        val model = StatisticsSettingsViewModel(
            flow { emit(firstSettings.await()) },
            {},
            StatisticsRepositoryFake().apply { archiveCount = 4 },
            backgroundScope,
        )
        runCurrent()
        assertEquals(SettingsLoadState.Loading, model.uiState.value.loadState)
        firstSettings.complete(persisted)
        runCurrent()
        val loaded = model.uiState.value.loadState as SettingsLoadState.Loaded
        assertEquals(persisted, loaded.value.settings)
        assertEquals(4, loaded.value.archivedBookCount)
    }

    @Test
    fun delayedArchiveSummaryIsRequiredBeforeControlsAreReady() = runTest {
        val count = CompletableDeferred<Int>()
        val model = StatisticsSettingsViewModel(
            MutableStateFlow(ReaderSettings()),
            {},
            object : StatisticsRepositoryFake() {
                override suspend fun loadArchiveSummary() = StatisticsArchiveSummary(count.await())
            },
            backgroundScope,
        )
        runCurrent()
        assertEquals(SettingsLoadState.Loading, model.uiState.value.loadState)
        count.complete(2)
        runCurrent()
        assertEquals(2, (model.uiState.value.loadState as SettingsLoadState.Loaded).value.archivedBookCount)
    }

    @Test
    fun failedInitialArchiveSummaryIsRetryableWithoutShowingEmptyArchive() = runTest {
        var attempts = 0
        val model = StatisticsSettingsViewModel(
            MutableStateFlow(ReaderSettings()),
            {},
            object : StatisticsRepositoryFake() {
                override suspend fun loadArchiveSummary(): StatisticsArchiveSummary {
                    attempts++
                    if (attempts == 1) throw IOException("Statistics archive access denied")
                    return StatisticsArchiveSummary(2)
                }
            },
            backgroundScope,
        )
        runCurrent()
        assertTrue(model.uiState.value.loadState is SettingsLoadState.Failed)
        val failure = (model.uiState.value.loadState as SettingsLoadState.Failed).message.resolve(
            getString = { _, _ -> "Unable to load settings." },
            getQuantityString = { _, _, _ -> "" },
        )
        assertTrue(failure.contains("Statistics archive access denied"))
        model.retryLoad()
        assertEquals(SettingsLoadState.Loading, model.uiState.value.loadState)
        runCurrent()
        assertEquals(2, (model.uiState.value.loadState as SettingsLoadState.Loaded).value.archivedBookCount)
    }

    @Test
    fun failedSettingsReadCanRetryAndReopenedInstanceStartsLoading() = runTest {
        val persisted = ReaderSettings(statisticsAutostartOnBookOpen = false)
        var attempts = 0
        val source = flow {
            attempts++
            if (attempts == 1) throw IOException()
            emit(persisted)
        }
        fun newModel() = StatisticsSettingsViewModel(
            source,
            {},
            StatisticsRepositoryFake(),
            backgroundScope,
        )
        val model = newModel()
        runCurrent()
        assertTrue(model.uiState.value.loadState is SettingsLoadState.Failed)
        assertNull(model.uiState.value.settings)
        model.retryLoad()
        assertEquals(SettingsLoadState.Loading, model.uiState.value.loadState)
        runCurrent()
        assertEquals(persisted, (model.uiState.value.loadState as SettingsLoadState.Loaded).value.settings)

        val reopened = newModel()
        assertEquals(SettingsLoadState.Loading, reopened.uiState.value.loadState)
        runCurrent()
        assertEquals(persisted, (reopened.uiState.value.loadState as SettingsLoadState.Loaded).value.settings)
    }

    @Test
    fun archiveReadFailureIsVisibleEvenWhilePreferencesAreStillLoading() = runTest {
        val pendingSettings = CompletableDeferred<ReaderSettings>()
        val model = StatisticsSettingsViewModel(
            flow { emit(pendingSettings.await()) },
            {},
            object : StatisticsRepositoryFake() {
                override suspend fun loadArchiveSummary(): StatisticsArchiveSummary = throw IOException()
            },
            backgroundScope,
        )
        runCurrent()
        assertTrue(model.uiState.value.loadState is SettingsLoadState.Failed)
    }

    @Test
    fun archiveRefreshRetainsReadyControlsAndReportsFailureSeparately() = runTest {
        val refresh = CompletableDeferred<Int>()
        var reads = 0
        val model = StatisticsSettingsViewModel(
            MutableStateFlow(ReaderSettings()),
            {},
            object : StatisticsRepositoryFake() {
                override suspend fun loadArchiveSummary(): StatisticsArchiveSummary {
                    reads++
                    return StatisticsArchiveSummary(if (reads == 1) 3 else refresh.await())
                }
            },
            backgroundScope,
        )
        runCurrent()
        val confirmed = model.uiState.value.loadState
        assertTrue(confirmed is SettingsLoadState.Loaded)
        model.reload()
        runCurrent()
        assertEquals(confirmed, model.uiState.value.loadState)
        refresh.completeExceptionally(IOException())
        runCurrent()
        assertEquals(confirmed, model.uiState.value.loadState)
        assertNotNull(model.uiState.value.error)
    }

    @Test
    fun settingsEditsUseLatestPreferencesAndClearArchiveRefreshesCount() = runTest {
        val settings = MutableStateFlow(ReaderSettings(statisticsSyncEnabled = false))
        val repository = StatisticsRepositoryFake().apply { archiveCount = 3 }
        val model = StatisticsSettingsViewModel(settings, { settings.value = it(settings.value) }, repository, backgroundScope)
        model.reload()
        runCurrent()
        assertEquals(3, model.uiState.value.archivedBookCount)
        settings.value = settings.value.copy(fontSize = 30)
        model.update { it.copy(statisticsResetMinutes = 105) }
        runCurrent()
        assertEquals(30, model.uiState.value.settings?.fontSize)
        assertEquals(105, model.uiState.value.settings?.statisticsResetMinutes)
        assertFalse(model.uiState.value.settings!!.statisticsSyncEnabled)
        model.clearArchive()
        runCurrent()
        assertEquals(0, model.uiState.value.archivedBookCount)
        assertFalse(model.uiState.value.isWorking)
    }

    @Test
    fun failedArchiveClearRetainsCountAndReportsError() = runTest {
        val settings = MutableStateFlow(ReaderSettings())
        val repository = StatisticsRepositoryFake().apply { archiveCount = 2; failWrites = true }
        val model = StatisticsSettingsViewModel(settings, {}, repository, backgroundScope)
        model.reload()
        runCurrent()
        model.clearArchive()
        runCurrent()
        assertEquals(2, model.uiState.value.archivedBookCount)
        assertNotNull(model.uiState.value.error)
        assertFalse(model.uiState.value.isWorking)
    }
}
