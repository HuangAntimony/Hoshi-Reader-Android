package moe.antimony.hoshi.features.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDisplaySettingsTest {
    @Test
    fun standardPalettesResolveTheirReaderColorsAndBrightness() {
        val expected = listOf(
            DisplayPalettePreset.Light to Triple(0xFFFFFFFFL, 0xFF000000L, false),
            DisplayPalettePreset.Sepia to Triple(0xFFF2E2C9L, 0xFF332A1BL, false),
            DisplayPalettePreset.Dark to Triple(0xFF000000L, 0xFFFFFFFFL, true),
            DisplayPalettePreset.DarkSepia to Triple(0xFF17150FL, 0xFFF2E2C9L, true),
        )

        expected.forEach { (preset, colors) ->
            val resolved = resolveDisplaySettings(
                AppDisplaySettings(
                    autoSwitch = false,
                    singlePalette = DisplayPaletteSelection(preset = preset),
                ),
                systemDark = false,
            )
            assertEquals(preset, resolved.palette)
            assertEquals(colors.first, resolved.backgroundColor)
            assertEquals(colors.second, resolved.textColor)
            assertEquals(colors.third, resolved.isDark)
        }
    }

    @Test
    fun customPalettePreservesAlphaButComputesBrightnessFromRgb() {
        val dark = resolveDisplaySettings(
            AppDisplaySettings(
                autoSwitch = false,
                singlePalette = DisplayPaletteSelection(
                    preset = DisplayPalettePreset.Custom,
                    customBackgroundColor = 0xCC102030L,
                    customTextColor = 0x80445566L,
                    customInfoColor = 0x40778899L,
                ),
            ),
            systemDark = false,
        )

        assertEquals(0xCC102030L, dark.backgroundColor)
        assertEquals(0x80445566L, dark.textColor)
        assertEquals(0x40778899L, dark.infoColor)
        assertTrue(dark.isDark)

        val light = resolveDisplaySettings(
            AppDisplaySettings(
                autoSwitch = false,
                singlePalette = dark.selection.copy(customBackgroundColor = 0x01F0E0D0L),
            ),
            systemDark = true,
        )
        assertFalse(light.isDark)
    }

    @Test
    fun eInkChangesOnlyResolvedColors() {
        val stored = AppDisplaySettings(
            autoSwitch = false,
            eInkMode = true,
            singlePalette = DisplayPaletteSelection(
                preset = DisplayPalettePreset.Custom,
                customBackgroundColor = 0x44112233L,
                customTextColor = 0x88445566L,
                customInfoColor = 0xCC778899L,
            ),
        )

        val resolved = resolveDisplaySettings(stored, systemDark = false)

        assertEquals(0xFF000000L, resolved.backgroundColor)
        assertEquals(0xFFFFFFFFL, resolved.textColor)
        assertEquals(0xFFFFFFFFL, resolved.infoColor)
        assertEquals(0x44112233L, stored.singlePalette.customBackgroundColor)
        assertTrue(resolved.eInkMode)
    }

    @Test
    fun manualEInkBrightnessIsIndependentOfSavedPaletteAndRestoresItOnExit() {
        val original = AppDisplaySettings(
            autoSwitch = false,
            singlePalette = DisplayPaletteSelection(DisplayPalettePreset.Sepia),
            accentSource = DisplayAccentSource.Custom,
            accentSeed = 0xFF00796B,
        )
        val eInk = original.copy(eInkMode = true, eInkDarkTheme = true)
        for (systemDark in listOf(false, true)) {
            val resolved = resolveDisplaySettings(eInk, systemDark)
            assertTrue(resolved.isDark)
            assertEquals(0xFF000000L, resolved.backgroundColor)
            assertEquals(0xFFFFFFFFL, resolved.textColor)
            assertEquals(original.singlePalette, eInk.singlePalette)
            assertEquals(
                resolveDisplaySettings(original, systemDark),
                resolveDisplaySettings(eInk.copy(eInkMode = false), systemDark),
            )
        }
    }

    @Test
    fun automaticEInkFollowsSystemEvenWithOppositeCustomPaletteBrightness() {
        val settings = AppDisplaySettings(
            eInkMode = true,
            eInkDarkTheme = true,
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
    fun firstAutomaticEnableSeedsMatchingSideAndLaterTogglesRestoreBothSides() {
        val custom = DisplayPaletteSelection(
            preset = DisplayPalettePreset.Custom,
            customBackgroundColor = 0xFF101010L,
            customTextColor = 0xFFEFEFEFL,
            customInfoColor = 0xFFAAAAAAL,
        )
        val firstAutomatic = AppDisplaySettings(
            autoSwitch = false,
            automaticInitialized = false,
            singlePalette = custom,
        ).withAutoSwitch(enabled = true, systemDark = false)

        assertEquals(custom, firstAutomatic.darkPalette)
        assertEquals(DisplayPalettePreset.Light, firstAutomatic.lightPalette.preset)
        assertTrue(firstAutomatic.automaticInitialized)

        val editedDual = firstAutomatic.copy(
            lightPalette = DisplayPaletteSelection(preset = DisplayPalettePreset.Sepia),
            darkPalette = DisplayPaletteSelection(preset = DisplayPalettePreset.DarkSepia),
        )
        val manual = editedDual.withAutoSwitch(enabled = false, systemDark = false)
        assertEquals(DisplayPalettePreset.Sepia, manual.singlePalette.preset)

        val restored = manual.withAutoSwitch(enabled = true, systemDark = true)
        assertEquals(DisplayPalettePreset.Sepia, restored.lightPalette.preset)
        assertEquals(DisplayPalettePreset.DarkSepia, restored.darkPalette.preset)
    }

    @Test
    fun customBrightnessUsesLinearSrgbLuminance() {
        val resolved = resolveDisplaySettings(
            AppDisplaySettings(
                autoSwitch = false,
                singlePalette = DisplayPaletteSelection(
                    preset = DisplayPalettePreset.Custom,
                    customBackgroundColor = 0xFF999999L,
                ),
            ),
            systemDark = false,
        )

        assertTrue(resolved.isDark)
    }

    @Test
    fun defaultDarkSlotStartsWithUsefulDarkCustomColors() {
        val darkCustom = AppDisplaySettings().darkPalette.copy(preset = DisplayPalettePreset.Custom)
        val resolved = resolveDisplaySettings(
            AppDisplaySettings(autoSwitch = false, singlePalette = darkCustom),
            systemDark = false,
        )

        assertEquals(0xFF000000L, resolved.backgroundColor)
        assertEquals(0xFFFFFFFFL, resolved.textColor)
        assertTrue(resolved.isDark)
    }

    @Test
    fun choosingPresetDoesNotDiscardSlotCustomColors() {
        val custom = DisplayPaletteSelection(
            preset = DisplayPalettePreset.Custom,
            customBackgroundColor = 0x12112233L,
            customTextColor = 0x34445566L,
            customInfoColor = 0x56778899L,
        )

        val changed = AppDisplaySettings(singlePalette = custom)
            .withSelectedPreset(DisplayPaletteSlot.Single, DisplayPalettePreset.Sepia)
            .withSelectedPreset(DisplayPaletteSlot.Single, DisplayPalettePreset.Custom)

        assertEquals(custom, changed.singlePalette)
    }
}
