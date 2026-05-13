package moe.antimony.hoshi.features.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLoadStateTest {
    @Test
    fun settingsContentIsReadyOnlyWhenAllRequiredValuesAreLoaded() {
        assertFalse(settingsContentReady(null))
        assertFalse(settingsContentReady("settings", null))
        assertTrue(settingsContentReady("settings"))
        assertTrue(settingsContentReady("settings", "sync"))
    }
}
