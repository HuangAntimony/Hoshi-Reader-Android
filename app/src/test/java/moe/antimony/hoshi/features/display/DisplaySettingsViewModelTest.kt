package moe.antimony.hoshi.features.display

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import moe.antimony.hoshi.features.settings.SettingsLoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import moe.antimony.hoshi.ui.resolve
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DisplaySettingsViewModelTest {
    @Test
    fun delayedFirstReadKeepsControlsUnavailableAndRejectsEdits() = runTest {
        val firstRead = CompletableDeferred<AppDisplaySettings>()
        var writes = 0
        val model = DisplaySettingsViewModel(
            settings = flow { emit(firstRead.await()) },
            updateSettings = { writes++ },
            setAutoSwitchValue = { _, _ -> writes++ },
            selectPresetValue = { _, _ -> writes++ },
            coroutineScope = backgroundScope,
        )
        runCurrent()
        assertNull(model.uiState.value.settings)
        model.setEInkMode(true)
        model.setAutoSwitch(false, false)
        model.selectPalettePreset(DisplayPaletteSlot.Dark, DisplayPalettePreset.Dark)
        runCurrent()
        assertEquals(0, writes)

        val persisted = AppDisplaySettings(autoSwitch = false, eInkMode = true)
        firstRead.complete(persisted)
        runCurrent()
        assertEquals(persisted, model.uiState.value.settings)
    }

    @Test
    fun failedReadCanRetryWithoutRevealingLastSettingsAndNewInstanceLoadsAgain() = runTest {
        val persisted = AppDisplaySettings(autoSwitch = false, eInkDarkTheme = true)
        val finishFirstRead = CompletableDeferred<Unit>()
        var attempts = 0
        val source = flow {
            attempts++
            if (attempts == 1) throw IOException("display preference checksum mismatch")
            finishFirstRead.await()
            emit(persisted)
        }
        fun newModel() = DisplaySettingsViewModel(source, {}, { _, _ -> }, { _, _ -> }, backgroundScope)
        val model = newModel()
        runCurrent()
        assertEquals(true, model.uiState.value.loadState is SettingsLoadState.Failed)
        val failure = model.uiState.value.loadState as SettingsLoadState.Failed
        assertTrue(failure.message.resolve({ _, _ -> "Load failed" }, { _, _, _ -> "" })
            .contains("display preference checksum mismatch"))
        assertNull(model.uiState.value.settings)

        model.retryLoad()
        assertEquals(SettingsLoadState.Loading, model.uiState.value.loadState)
        runCurrent()
        finishFirstRead.complete(Unit)
        runCurrent()
        assertEquals(SettingsLoadState.Loaded(persisted), model.uiState.value.loadState)

        val reopened = newModel()
        assertEquals(SettingsLoadState.Loading, reopened.uiState.value.loadState)
        runCurrent()
        assertEquals(SettingsLoadState.Loaded(persisted), reopened.uiState.value.loadState)
        assertEquals(3, attempts)
    }

    @Test
    fun liveReadFailureHidesControlsButPreservesUnsavedPaletteUntilRetrySucceeds() = runTest {
        val persisted = AppDisplaySettings(autoSwitch = false)
        val failRead = CompletableDeferred<Unit>()
        var attempts = 0
        val model = DisplaySettingsViewModel(
            settings = flow {
                attempts++
                emit(persisted)
                if (attempts == 1) {
                    failRead.await()
                    throw IOException()
                }
            },
            updateSettings = {},
            setAutoSwitchValue = { _, _ -> },
            selectPresetValue = { _, _ -> },
            coroutineScope = backgroundScope,
        )
        runCurrent()
        model.openPaletteEditor(DisplayPaletteSlot.Light)
        model.updatePaletteDraft(backgroundColor = 0x7F123456L)
        val draft = model.uiState.value.paletteDraft
        failRead.complete(Unit)
        runCurrent()
        assertEquals(true, model.uiState.value.loadState is SettingsLoadState.Failed)
        assertNull(model.uiState.value.settings)
        assertEquals(draft, model.uiState.value.paletteDraft)
        model.retryLoad()
        runCurrent()
        assertEquals(SettingsLoadState.Loaded(persisted), model.uiState.value.loadState)
        assertEquals(draft, model.uiState.value.paletteDraft)
    }

    @Test
    fun manualCustomSelectionActivatesItsGroupOnlyOnSuccessfulSave() = runTest {
        val stored = MutableStateFlow(AppDisplaySettings(autoSwitch = false))
        val model = viewModel(stored)
        runCurrent()
        model.selectPalettePreset(DisplayPaletteSlot.Dark, DisplayPalettePreset.Custom)
        model.updatePaletteDraft(backgroundColor = 0xFFEEEEEE, textColor = 0xFF111111)
        assertEquals(DisplayPaletteSlot.Light, stored.value.manualPaletteSlot)
        model.dismissPaletteEditor()
        assertEquals(DisplayPaletteSlot.Light, stored.value.manualPaletteSlot)

        model.selectPalettePreset(DisplayPaletteSlot.Dark, DisplayPalettePreset.Custom)
        model.updatePaletteDraft(backgroundColor = 0xFFEEEEEE, textColor = 0xFF111111)
        model.savePaletteDraft()
        runCurrent()
        assertEquals(DisplayPaletteSlot.Dark, stored.value.manualPaletteSlot)
        assertEquals(true, resolveDisplaySettings(stored.value, false).isDark)
        assertEquals(0xFFEEEEEEL, stored.value.darkPalette.customBackgroundColor)
        assertEquals(0xFFFFFFFFL, stored.value.lightPalette.customBackgroundColor)
    }

    @Test
    fun eInkBrightnessChangesLeaveReadingPalettesAndAccentIntact() = runTest {
        val original = AppDisplaySettings(
            autoSwitch = false,
            lightPalette = DisplayPaletteSelection(DisplayPalettePreset.Sepia),
            accentSource = DisplayAccentSource.Custom,
            accentSeed = 0xFF00796B,
        )
        val stored = MutableStateFlow(original)
        val model = viewModel(stored)
        runCurrent()
        model.setEInkMode(true)
        runCurrent()
        model.setEInkDarkTheme(true)
        runCurrent()
        assertEquals(true, resolveDisplaySettings(model.uiState.value.settings!!, false).isDark)
        model.setEInkMode(false)
        runCurrent()
        assertEquals(original.copy(eInkDarkTheme = true), stored.value)
        assertEquals(resolveDisplaySettings(original, false), resolveDisplaySettings(stored.value, false))
    }

    @Test
    fun customPaletteDraftDoesNotChangeConfirmedSettingsUntilSave() = runTest {
        val stored = MutableStateFlow(AppDisplaySettings())
        val model = viewModel(stored)
        runCurrent()

        model.openPaletteEditor(DisplayPaletteSlot.Light)
        model.updatePaletteDraft(backgroundColor = 0x80112233L)

        assertEquals(0xFFFFFFFFL, stored.value.lightPalette.customBackgroundColor)
        assertEquals(0x80112233L, model.uiState.value.paletteDraft?.backgroundColor)

        model.dismissPaletteEditor()
        assertNull(model.uiState.value.paletteDraft)
        assertEquals(0xFFFFFFFFL, stored.value.lightPalette.customBackgroundColor)
    }

    @Test
    fun savingCustomPaletteWritesColorsAndSelectionTogether() = runTest {
        val stored = MutableStateFlow(AppDisplaySettings())
        val model = viewModel(stored)
        runCurrent()

        model.openPaletteEditor(DisplayPaletteSlot.Dark)
        model.updatePaletteDraft(
            backgroundColor = 0xCC112233L,
            textColor = 0xDD445566L,
            infoColor = 0xEE778899L,
        )
        model.savePaletteDraft()
        runCurrent()

        assertEquals(DisplayPalettePreset.Custom, stored.value.darkPalette.preset)
        assertEquals(0xCC112233L, stored.value.darkPalette.customBackgroundColor)
        assertEquals(0xDD445566L, stored.value.darkPalette.customTextColor)
        assertEquals(0xEE778899L, stored.value.darkPalette.customInfoColor)
        assertNull(model.uiState.value.paletteDraft)
    }

    @Test
    fun failedCustomPaletteWriteKeepsConfirmedValueAndDraftOpen() = runTest {
        val stored = MutableStateFlow(AppDisplaySettings())
        val model = DisplaySettingsViewModel(
            settings = stored,
            updateSettings = { throw IOException("display preference write failed", IOException("disk quota exceeded")) },
            setAutoSwitchValue = { _, _ -> },
            selectPresetValue = { _, _ -> },
            coroutineScope = backgroundScope,
        )
        runCurrent()

        model.openPaletteEditor(DisplayPaletteSlot.Light)
        model.updatePaletteDraft(backgroundColor = 0x7F123456L)
        model.savePaletteDraft()
        runCurrent()

        assertEquals(DisplayPalettePreset.Light, stored.value.lightPalette.preset)
        assertEquals(0xFFFFFFFFL, stored.value.lightPalette.customBackgroundColor)
        assertEquals(0x7F123456L, model.uiState.value.paletteDraft?.backgroundColor)
        assertNotNull(model.uiState.value.error)
        val message = requireNotNull(model.uiState.value.error).resolve({ _, _ -> "Save failed" }, { _, _, _ -> "" })
        assertTrue(message.contains("display preference write failed"))
        assertTrue(message.contains("disk quota exceeded"))
    }

    private fun TestScope.viewModel(stored: MutableStateFlow<AppDisplaySettings>) = DisplaySettingsViewModel(
        settings = stored,
        updateSettings = { transform -> stored.value = transform(stored.value) },
        setAutoSwitchValue = { enabled, systemDark ->
            stored.value = stored.value.withAutoSwitch(enabled, systemDark)
        },
        selectPresetValue = { slot, preset ->
            stored.value = stored.value.withSelectedPreset(slot, preset)
        },
        coroutineScope = backgroundScope,
    )
}
