package moe.antimony.hoshi.features.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Static generated state only. Never reads or mutates the installed library/account. */
@RunWith(AndroidJUnit4::class)
class SyncQueueViewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsEveryActiveTransferAndSeparateTotals() {
        val active = linkedMapOf(
            "a" to SyncFileTransfer(1, SyncTransferDirection.Upload, SyncFileType.epub, SyncTransferPhase.Transferring, 512, 1024, 0, 1024.0),
            "b" to SyncFileTransfer(2, SyncTransferDirection.Download, SyncFileType.epub, SyncTransferPhase.Transferring, 256, 1024, 256, 2048.0),
        )
        compose.setContent {
            HoshiReaderTheme {
                SyncQueueContent(GoogleDriveSyncState(
                    queue = listOf(SyncQueueItem("a", "Upload fixture", SyncTransferDirection.Upload, null),
                        SyncQueueItem("b", "Download fixture", SyncTransferDirection.Download, null),
                        SyncQueueItem("c", "Waiting fixture", SyncTransferDirection.Upload, null)),
                    activeTransfers = active,
                ), {})
            }
        }
        compose.onNodeWithText("Upload fixture").assertIsDisplayed()
        compose.onNodeWithText("Download fixture").assertIsDisplayed()
        compose.onNodeWithText("Waiting fixture").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(2)
        compose.onAllNodesWithText("1.0 KiB/s", substring = true).assertCountEquals(2)
        compose.onAllNodesWithText("2.0 KiB/s", substring = true).assertCountEquals(2)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.cacheDir, "sync-queue-fixture.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
