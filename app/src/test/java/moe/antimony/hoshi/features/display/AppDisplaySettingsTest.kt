package moe.antimony.hoshi.features.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDisplaySettingsTest {
    @Test
    fun manualModeSelectsOneOfSixPalettesRegardlessOfSystemBrightness() {
        val choices = listOf(
            DisplayPaletteSlot.Light to listOf(DisplayPalettePreset.Light, DisplayPalettePreset.Sepia, DisplayPalettePreset.Custom),
            DisplayPaletteSlot.Dark to listOf(DisplayPalettePreset.Dark, DisplayPalettePreset.DarkSepia, DisplayPalettePreset.Custom),
        )
        var settings = AppDisplaySettings(autoSwitch = false)
        choices.forEach { (slot, presets) ->
            presets.forEach { preset ->
                settings = settings.withSelectedPreset(slot, preset)
                for (systemDark in listOf(false, true)) {
                    val resolved = resolveDisplaySettings(settings, systemDark)
                    assertEquals(slot, settings.activePaletteSlot(systemDark))
                    assertEquals(preset, resolved.palette)
                    assertEquals(slot == DisplayPaletteSlot.Dark, resolved.isDark)
                }
            }
        }
    }

    @Test
    fun standardPalettesKeepTheirReaderColors() {
        val expected = listOf(
            DisplayPalettePreset.Light to Triple(DisplayPaletteSlot.Light, 0xFFFFFFFFL, 0xFF000000L),
            DisplayPalettePreset.Sepia to Triple(DisplayPaletteSlot.Light, 0xFFF2E2C9L, 0xFF332A1BL),
            DisplayPalettePreset.Dark to Triple(DisplayPaletteSlot.Dark, 0xFF000000L, 0xFFFFFFFFL),
            DisplayPalettePreset.DarkSepia to Triple(DisplayPaletteSlot.Dark, 0xFF17150FL, 0xFFF2E2C9L),
        )
        expected.forEach { (preset, colors) ->
            val settings = AppDisplaySettings(autoSwitch = false).withSelectedPreset(colors.first, preset)
            val resolved = resolveDisplaySettings(settings, false)
            assertEquals(colors.second, resolved.backgroundColor)
            assertEquals(colors.third, resolved.textColor)
        }
    }

    @Test
    fun customInterfaceBrightnessBelongsToItsGroupAndColorsKeepAlpha() {
        val settings = AppDisplaySettings(
            lightPalette = DisplayPaletteSelection(DisplayPalettePreset.Custom, 0x80111111, 0xAAEEEEEE, 0x40778899),
            darkPalette = DisplayPaletteSelection(DisplayPalettePreset.Custom, 0xCCEEEEEE, 0xFF111111, 0x7F445566),
        )
        for (systemDark in listOf(false, true)) {
            val automatic = resolveDisplaySettings(settings, systemDark)
            val selected = if (systemDark) settings.darkPalette else settings.lightPalette
            assertEquals(systemDark, automatic.isDark)
            assertEquals(selected.customBackgroundColor, automatic.backgroundColor)
            assertEquals(selected.customTextColor, automatic.textColor)
            assertEquals(selected.customInfoColor, automatic.infoColor)
            val manual = settings.withAutoSwitch(false, systemDark)
            assertEquals(automatic, resolveDisplaySettings(manual, !systemDark))
        }
    }

    @Test
    fun automaticToggleSharesBothSavedSelectionsAndKeepsCurrentPaletteOnDisable() {
        val original = AppDisplaySettings(
            autoSwitch = false,
            lightPalette = DisplayPaletteSelection(DisplayPalettePreset.Custom, 0xFFABCDEF, 0xFF123456, 0xFF654321),
            darkPalette = DisplayPaletteSelection(DisplayPalettePreset.Custom, 0xFF123456, 0xFFABCDEF, 0xFFAAAAAA),
        )
        for (systemDark in listOf(false, true)) {
            val automatic = original.withAutoSwitch(true, systemDark)
            assertEquals(original.lightPalette, automatic.lightPalette)
            assertEquals(original.darkPalette, automatic.darkPalette)
            val manual = automatic.withAutoSwitch(false, systemDark)
            assertEquals(resolveDisplaySettings(automatic, systemDark), resolveDisplaySettings(manual, !systemDark))
            val changed = manual.withSelectedPreset(DisplayPaletteSlot.Light, DisplayPalettePreset.Sepia)
            val restored = changed.withAutoSwitch(true, true)
            assertEquals(DisplayPalettePreset.Sepia, resolveDisplaySettings(restored, false).palette)
            assertEquals(original.darkPalette, resolveDisplaySettings(restored, true).selection)
            assertEquals(original.lightPalette.customBackgroundColor, restored.lightPalette.customBackgroundColor)
        }
    }

    @Test
    fun customColorsAreIndependentAndSurvivePresetChangesInBothModes() {
        var settings = AppDisplaySettings(autoSwitch = false)
            .withCustomPalette(DisplayPaletteSlot.Light, 0x12112233, 0x34445566, 0x56778899)
            .withCustomPalette(DisplayPaletteSlot.Dark, 0xCCABCDEF, 0xDD123456, 0xEE654321)
        val light = settings.lightPalette
        val dark = settings.darkPalette
        for (auto in listOf(false, true)) {
            settings = settings.withAutoSwitch(auto, false)
                .withSelectedPreset(DisplayPaletteSlot.Light, DisplayPalettePreset.Sepia)
                .withSelectedPreset(DisplayPaletteSlot.Dark, DisplayPalettePreset.DarkSepia)
                .withSelectedPreset(DisplayPaletteSlot.Light, DisplayPalettePreset.Custom)
                .withSelectedPreset(DisplayPaletteSlot.Dark, DisplayPalettePreset.Custom)
            assertEquals(light.copy(preset = DisplayPalettePreset.Custom), settings.lightPalette)
            assertEquals(dark.copy(preset = DisplayPalettePreset.Custom), settings.darkPalette)
        }
    }

    @Test
    fun editingInactiveAutomaticSlotDoesNotChangeCurrentDisplay() {
        val original = AppDisplaySettings()
        val changed = original.withCustomPalette(DisplayPaletteSlot.Dark, 0xFFFFFFFF, 0xFF000000, 0xFFAAAAAA)
            .withSelectedPreset(DisplayPaletteSlot.Dark, DisplayPalettePreset.Custom)
        assertEquals(resolveDisplaySettings(original, false), resolveDisplaySettings(changed, false))
        assertTrue(resolveDisplaySettings(changed, true).isDark)
    }

    @Test
    fun manualEInkBrightnessIsIndependentAndRestoresPaletteOnExit() {
        val original = AppDisplaySettings(autoSwitch = false)
            .withSelectedPreset(DisplayPaletteSlot.Light, DisplayPalettePreset.Sepia)
            .copy(accentSource = DisplayAccentSource.Custom, accentSeed = 0xFF00796B)
        for (dark in listOf(false, true)) {
            val eInk = original.copy(eInkMode = true, eInkDarkTheme = dark)
            for (systemDark in listOf(false, true)) {
                val resolved = resolveDisplaySettings(eInk, systemDark)
                assertEquals(dark, resolved.isDark)
                assertEquals(if (dark) 0xFF000000L else 0xFFFFFFFFL, resolved.backgroundColor)
                assertEquals(if (dark) 0xFFFFFFFFL else 0xFF000000L, resolved.textColor)
                assertEquals(resolved.textColor, resolved.infoColor)
                assertEquals(original.lightPalette, eInk.lightPalette)
                assertEquals(resolveDisplaySettings(original, systemDark), resolveDisplaySettings(eInk.copy(eInkMode = false), systemDark))
            }
        }
    }

    @Test
    fun automaticEInkFollowsSystemAndRetainsDisplayedBrightnessWhenDisabled() {
        val settings = AppDisplaySettings(
            eInkMode = true,
            lightPalette = DisplayPaletteSelection(DisplayPalettePreset.Custom, 0xFF000000),
            darkPalette = DisplayPaletteSelection(DisplayPalettePreset.Custom, 0xFFFFFFFF),
        )
        for (systemDark in listOf(false, true)) {
            assertEquals(systemDark, resolveDisplaySettings(settings, systemDark).isDark)
            val manual = settings.withAutoSwitch(false, systemDark)
            assertEquals(systemDark, resolveDisplaySettings(manual, !systemDark).isDark)
            assertEquals(systemDark, manual.eInkDarkTheme)
            val automatic = manual.withAutoSwitch(true, !systemDark)
            assertEquals(!systemDark, resolveDisplaySettings(automatic, !systemDark).isDark)
            assertEquals(settings.lightPalette, automatic.lightPalette)
            assertEquals(settings.darkPalette, automatic.darkPalette)
        }
    }

    @Test
    fun darkCustomDefaultsAreUsefulAndLightCustomUsesLightInterface() {
        val settings = AppDisplaySettings()
            .withSelectedPreset(DisplayPaletteSlot.Dark, DisplayPalettePreset.Custom)
            .withSelectedPreset(DisplayPaletteSlot.Light, DisplayPalettePreset.Custom)
        val dark = resolveDisplaySettings(settings, true)
        assertEquals(0xFF000000L, dark.backgroundColor)
        assertEquals(0xFFFFFFFFL, dark.textColor)
        assertTrue(dark.isDark)
        assertFalse(resolveDisplaySettings(settings, false).isDark)
    }
}
