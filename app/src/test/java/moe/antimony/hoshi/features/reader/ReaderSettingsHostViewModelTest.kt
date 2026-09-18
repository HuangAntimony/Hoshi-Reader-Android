package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSettingsHostViewModelTest {
    @Test fun staleCallbackCannotRevertAnUnrelatedConfirmedChange() = runTest {
        val stored = MutableStateFlow(ReaderSettings())
        val model = ReaderSettingsHostViewModel(stored, { edit -> stored.value = edit(stored.value) }, backgroundScope)
        runCurrent()
        model.update { it.copy(volumeKeysTurnPages = true) }
        runCurrent()
        model.update { it.copy(keepScreenOnWhileReading = true) }
        runCurrent()
        assertTrue(stored.value.volumeKeysTurnPages)
        assertTrue(stored.value.keepScreenOnWhileReading)
    }

    @Test fun rapidIncrementsAccumulateWhileStorageIsDelayed() = runTest {
        val stored = MutableStateFlow(ReaderSettings(fontSize = 22))
        val model = ReaderSettingsHostViewModel(stored, { edit ->
            delay(100)
            stored.value = edit(stored.value)
        }, backgroundScope)
        runCurrent()
        repeat(2) { model.update { it.copy(fontSize = it.fontSize + 1) } }
        advanceTimeBy(201)
        runCurrent()
        assertEquals(24, stored.value.fontSize)
    }

    @Test fun separateActivityHostsObserveTheSameDisplayChanges() = runTest {
        val stored = MutableStateFlow(ReaderSettings())
        val main = ReaderSettingsHostViewModel(stored, {}, backgroundScope)
        val processText = ReaderSettingsHostViewModel(stored, {}, backgroundScope)
        runCurrent()
        stored.value = ReaderSettings(
            displaySettings = moe.antimony.hoshi.features.display.AppDisplaySettings(
                autoSwitch = false,
                manualPaletteSlot = moe.antimony.hoshi.features.display.DisplayPaletteSlot.Dark,
                darkPalette = moe.antimony.hoshi.features.display.DisplayPaletteSelection(
                    moe.antimony.hoshi.features.display.DisplayPalettePreset.DarkSepia,
                ),
            ),
        )
        runCurrent()
        assertTrue(main.uiState.value.settings!!.usesDarkInterface(false))
        assertEquals(main.uiState.value.settings, processText.uiState.value.settings)
    }

    @Test fun queuedEditsPreserveIndependentSettingsAfterDelayedPersistence() = runTest {
        val stored = MutableStateFlow(ReaderSettings())
        val model = ReaderSettingsHostViewModel(stored, { edit ->
            delay(100)
            stored.value = edit(stored.value)
        }, backgroundScope)
        runCurrent()
        model.update { it.copy(volumeKeysTurnPages = true) }
        model.update { it.copy(keepScreenOnWhileReading = true) }
        advanceTimeBy(201)
        runCurrent()
        assertTrue(stored.value.volumeKeysTurnPages)
        assertTrue(stored.value.keepScreenOnWhileReading)
    }

    @Test fun failedInitialMigrationRemainsUnloadedUntilRetrySucceeds() = runTest {
        var failed = true
        val model = ReaderSettingsHostViewModel(
            flow { if (failed) throw IOException() else emit(ReaderSettings(fontSize = 30)) },
            {}, backgroundScope,
        )
        runCurrent()
        assertNull(model.uiState.value.settings)
        assertNotNull(model.uiState.value.error)
        failed = false
        model.retry()
        runCurrent()
        assertEquals(30, model.uiState.value.settings?.fontSize)
        assertNull(model.uiState.value.error)
    }

    @Test fun failedWriteKeepsLastConfirmedSettings() = runTest {
        val stored = MutableStateFlow(ReaderSettings(fontSize = 24))
        val model = ReaderSettingsHostViewModel(stored, { throw IOException() }, backgroundScope)
        runCurrent()
        model.update { it.copy(fontSize = 32) }
        runCurrent()
        assertEquals(24, model.uiState.value.settings?.fontSize)
        assertNotNull(model.uiState.value.error)
    }
}
