package moe.antimony.hoshi.features.statistics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.features.reader.ReaderSettings
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsSettingsViewModelTest {
    @Test
    fun settingsEditsUseLatestPreferencesAndClearArchiveRefreshesCount() = runTest {
        val settings = MutableStateFlow(ReaderSettings(statisticsSyncEnabled = false))
        val repository = StatisticsRepositoryFake().apply { archiveCount = 3 }
        val model = StatisticsSettingsViewModel(settings, MutableStateFlow(true), { settings.value = it(settings.value) }, repository, backgroundScope)
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
        val model = StatisticsSettingsViewModel(settings, MutableStateFlow(false), {}, repository, backgroundScope)
        model.reload()
        runCurrent()
        model.clearArchive()
        runCurrent()
        assertEquals(2, model.uiState.value.archivedBookCount)
        assertNotNull(model.uiState.value.error)
        assertFalse(model.uiState.value.isWorking)
    }
}
