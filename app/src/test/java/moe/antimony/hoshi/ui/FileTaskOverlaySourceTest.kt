package moe.antimony.hoshi.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileTaskOverlaySourceTest {
    @Test
    fun reusableOverlaySupportsEInkAndDeterminateProgress() {
        val source = File("src/main/java/moe/antimony/hoshi/ui/HoshiBlockingProgressOverlay.kt").readText()

        assertTrue(source.contains("progress: Float? = null"))
        assertTrue(source.contains("supportingText: String? = null"))
        assertTrue(source.contains("LocalHoshiEInkMode.current"))
        assertTrue(source.contains("if (eInkMode) Color.Transparent"))
        assertTrue(source.contains("BorderStroke(1.dp, MaterialTheme.colorScheme.outline"))
        assertTrue(source.contains("event.changes.forEach { it.consume() }"))
        assertTrue(source.contains("LinearProgressIndicator("))
    }

    @Test
    fun fileChangingTasksUseBlockingProgressOverlay() {
        val requiredFiles = listOf(
            "src/main/java/moe/antimony/hoshi/features/backup/BackupSettingsView.kt",
            "src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt",
            "src/main/java/moe/antimony/hoshi/features/reader/ReaderAppearanceView.kt",
            "src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiSheet.kt",
            "src/main/java/moe/antimony/hoshi/features/audio/AudioView.kt",
        )

        requiredFiles.forEach { path ->
            val source = File(path).readText()
            assertTrue("$path should render HoshiBlockingProgressOverlay", source.contains("HoshiBlockingProgressOverlay("))
        }
    }

    @Test
    fun backupNoLongerHasPrivateScrimOverlayImplementation() {
        val source = File("src/main/java/moe/antimony/hoshi/features/backup/BackupSettingsView.kt").readText()

        assertTrue(source.contains("HoshiBlockingProgressOverlay("))
        assertTrue(source.contains("current.label"))
        assertTrue(source.contains("onClose = {\n            if (operation == null)"))
        assertFalse(source.contains("MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)"))
    }

    @Test
    fun localAudioImportKeepsByteProgressInOverlay() {
        val source = File("src/main/java/moe/antimony/hoshi/features/audio/AudioView.kt").readText()

        assertTrue(source.contains("message = \"Copying android.db\""))
        assertTrue(source.contains("progress = importProgress?.takeIf { it.totalBytes != null }?.fraction"))
        assertTrue(source.contains("supportingText = importProgress?.label(context)"))
        assertTrue(source.contains("importProgress = LocalAudioImportProgress(copiedBytes = 0, totalBytes = null)"))
    }
}
