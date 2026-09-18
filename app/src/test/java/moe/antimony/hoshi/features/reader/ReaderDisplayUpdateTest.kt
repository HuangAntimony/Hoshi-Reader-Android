package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.display.AppDisplaySettings
import moe.antimony.hoshi.features.display.DisplayPalettePreset
import moe.antimony.hoshi.features.display.DisplayPaletteSlot
import moe.antimony.hoshi.features.display.DisplayPaletteSelection
import org.junit.Assert.*
import org.junit.Test

class ReaderDisplayUpdateTest {
    @Test fun choosingCustomGroupChangesPopupBrightnessWithoutReloadingReaderContent() {
        val custom = DisplayPaletteSelection(DisplayPalettePreset.Custom, 0xFF808080, 0xFFFFFFFF, 0xFFCCCCCC)
        val display = AppDisplaySettings(autoSwitch = false, lightPalette = custom, darkPalette = custom)
        val light = ReaderSettings(displaySettings = display)
        val dark = light.copy(displaySettings = display.copy(manualPaletteSlot = DisplayPaletteSlot.Dark))
        for (systemDark in listOf(false, true)) {
            assertFalse(light.usesDarkInterface(systemDark))
            assertTrue(dark.usesDarkInterface(systemDark))
            assertEquals(light.backgroundColorCss(systemDark), dark.backgroundColorCss(systemDark))
            assertEquals(light.textColorCss(systemDark), dark.textColorCss(systemDark))
            assertEquals(ReaderInterfaceTheme.Light, light.resolvedForDisplay(systemDark).uiTheme)
            assertEquals(ReaderInterfaceTheme.Dark, dark.resolvedForDisplay(systemDark).uiTheme)
        }
        assertEquals(light.readerContentReloadKey(), dark.readerContentReloadKey())
    }

    @Test fun paletteAndEInkChangesUpdateAppearanceWithoutReloadingContent() {
        val light = ReaderSettings(displaySettings = AppDisplaySettings())
        val dark = light.copy(
            eInkMode = true,
            displaySettings = AppDisplaySettings(
                autoSwitch = false,
                manualPaletteSlot = DisplayPaletteSlot.Dark,
                eInkMode = true,
            ),
        )
        assertEquals(light.readerContentReloadKey(), dark.readerContentReloadKey())
        val before = readerAppearanceUpdateKey(light, false, 0xFF000000, 0xFFFFFFFF)
        val after = readerAppearanceUpdateKey(dark, false, 0xFF000000, 0xFFFFFFFF)
        assertEquals("#fff", before.backgroundColorCss.lowercase())
        assertEquals("#000", after.backgroundColorCss.lowercase())
        assertEquals("1", after.eInkModeCss)
    }

    @Test fun systemNightChangeSelectsIndependentCustomColorsThroughExistingBridge() {
        val settings = ReaderSettings(displaySettings = AppDisplaySettings(
            lightPalette = DisplayPaletteSelection(DisplayPalettePreset.Custom, 0xFFF0E0D0, 0xFF201000, 0xFF999999),
            darkPalette = DisplayPaletteSelection(DisplayPalettePreset.Custom, 0xFF102030, 0xFFD0E0F0, 0xFFCCCCCC),
        ))
        assertEquals("#f0e0d0", settings.backgroundColorCss(false).lowercase())
        assertEquals("#102030", settings.backgroundColorCss(true).lowercase())
        assertEquals("#d0e0f0", settings.textColorCss(true).lowercase())
        assertFalse(settings.usesDarkInterface(false))
        assertTrue(settings.usesDarkInterface(true))
    }
}
