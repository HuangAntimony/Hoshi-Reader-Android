package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiConnectViewTest {
    @Test
    fun `setup warning is shown while AnkiConnect is disabled`() {
        assertTrue(shouldShowAnkiConnectSetupWarning(AnkiBackendKind.AnkiDroid))
    }

    @Test
    fun `setup warning is hidden after AnkiConnect is enabled`() {
        assertFalse(shouldShowAnkiConnectSetupWarning(AnkiBackendKind.AnkiConnect))
    }
}
