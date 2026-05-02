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
        assertTrue(source.contains("validateImportFile(uri, ImportFileType.SasayakiAudiobook)"))
        assertTrue(source.contains("importer.launch(ImportFileType.SasayakiAudiobook.mimeTypes)"))
    }

    @Test
    fun sheetUsesSettingsToggleToChooseExternalUriOrPrivateCopy() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiSheet.kt").readText()
        val loadAudioIndex = source.indexOf("Load Audio")
        val copyToggleIndex = source.indexOf("Copy Audiobook to App Storage")
        val delayIndex = source.indexOf("Delay")

        assertTrue(source.contains("settings.copyAudiobookToPrivateStorage"))
        assertTrue(source.contains("player.importAudio("))
        assertTrue(source.contains("copyToPrivateStorage = settings.copyAudiobookToPrivateStorage"))
        assertTrue(copyToggleIndex > loadAudioIndex)
        assertTrue(copyToggleIndex < delayIndex)
        assertTrue(source.contains("settings.copy(copyAudiobookToPrivateStorage = it)"))
    }

    @Test
    fun sheetKeepsStorageControlsBesideLoadAudioAndRemovesWastedHeader() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiSheet.kt").readText()
        val loadAudioIndex = source.indexOf("Load Audio")
        val removeIndex = source.indexOf("Remove")
        val storageSummaryIndex = source.indexOf("audioStorageSummary")

        assertTrue(loadAudioIndex >= 0)
        assertTrue(storageSummaryIndex > loadAudioIndex)
        assertTrue(removeIndex > loadAudioIndex)
        assertTrue(source.contains("player.clearAudio()"))
        assertTrue(source.contains("Text(if (player.hasAudio) \"Remove\" else if (isImporting) \"Importing\" else \"Open\")"))
        assertTrue(source.contains("if (player.hasAudio) {\n                                player.clearAudio()"))
        assertTrue(!source.contains("Text(\n                    text = \"Sasayaki\""))
        assertTrue(!source.contains("contentDescription = \"Close\""))
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
