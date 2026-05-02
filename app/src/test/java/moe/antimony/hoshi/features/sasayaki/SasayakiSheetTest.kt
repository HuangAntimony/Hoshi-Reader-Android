package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiSheetTest {
    @Test
    fun sheetOpensAudiobooksAsPersistedExternalDocumentsLikeIos() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiSheet.kt").readText()

        assertTrue(source.contains("OpenDocumentContent"))
        assertTrue(source.contains("takePersistableUriPermission"))
        assertTrue(source.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(source.contains("copyToPrivateStorage = settings.copyAudiobookToPrivateStorage"))
        assertTrue(source.contains("audio/mpeg"))
        assertTrue(source.contains("audio/mp4"))
    }

    @Test
    fun sheetUsesSettingsToggleToChooseExternalUriOrPrivateCopy() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiSheet.kt").readText()

        assertTrue(source.contains("settings.copyAudiobookToPrivateStorage"))
        assertTrue(source.contains("player.importAudio("))
        assertTrue(source.contains("copyToPrivateStorage = settings.copyAudiobookToPrivateStorage"))
    }

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
