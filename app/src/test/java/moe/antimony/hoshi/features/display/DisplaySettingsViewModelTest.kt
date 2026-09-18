package moe.antimony.hoshi.features.display

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DisplaySettingsViewModelTest {
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
            updateSettings = { throw IOException() },
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
