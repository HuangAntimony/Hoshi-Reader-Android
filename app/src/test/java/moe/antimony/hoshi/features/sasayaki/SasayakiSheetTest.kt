package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiSheetTest {
    @Test
    fun sheetExposesIosSasayakiSettingsToggles() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiSheet.kt").readText()

        assertTrue(source.contains("settings: SasayakiSettings"))
        assertTrue(source.contains("onSettingsChange: (SasayakiSettings) -> Unit"))
        assertTrue(source.contains("Settings"))
        assertTrue(source.contains("Show Sasayaki Toggle"))
        assertTrue(source.contains("settings.copy(showReaderToggle = it)"))
        assertTrue(source.contains("Auto-Scroll"))
        assertTrue(source.contains("settings.copy(autoScroll = it)"))
        assertTrue(source.contains("Auto-Pause on Lookup"))
        assertTrue(source.contains("settings.copy(autoPause = it)"))
    }
}
