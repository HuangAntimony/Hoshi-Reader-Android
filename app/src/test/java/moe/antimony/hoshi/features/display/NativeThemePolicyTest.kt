package moe.antimony.hoshi.features.display

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeThemePolicyTest {
    @Test fun automaticAlwaysFollowsRealSystemAndManualRespectsEInkOverride() {
        for (systemDark in listOf(false, true)) {
            assertEquals(NativeThemePolicy.FollowSystem, nativeThemePolicy(AppDisplaySettings(eInkMode = true), systemDark))
            val manual = AppDisplaySettings(autoSwitch = false, manualPaletteSlot = DisplayPaletteSlot.Dark)
            assertEquals(NativeThemePolicy.Dark, nativeThemePolicy(manual, systemDark))
            assertEquals(NativeThemePolicy.Light, nativeThemePolicy(manual.copy(eInkMode = true, eInkDarkTheme = false), systemDark))
            assertEquals(NativeThemePolicy.Dark, nativeThemePolicy(manual.copy(eInkMode = true, eInkDarkTheme = true), systemDark))
            assertEquals(NativeThemePolicy.Light, nativeThemePolicy(manual.copy(manualPaletteSlot = DisplayPaletteSlot.Light,
                lightPalette = DisplayPaletteSelection(DisplayPalettePreset.Custom, customBackgroundColor = 0xFF000000)), systemDark))
        }
    }
}
