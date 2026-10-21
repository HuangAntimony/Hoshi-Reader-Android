package moe.antimony.hoshi.features.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
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

/** Generated state only. Never reads or mutates the installed library/account. */
@RunWith(AndroidJUnit4::class)
class SyncQueueViewTest {
    @get:Rule val compose = createComposeRule()

    private fun queue(keys: List<Int>, active: Set<Int>): GoogleDriveSyncState = GoogleDriveSyncState(
        queue = keys.map { SyncQueueItem("$it", "Book $it", SyncTransferDirection.Upload, null) },
        activeTransfers = active.associate { "$it" to SyncFileTransfer(it.toLong(), SyncTransferDirection.Upload,
            SyncFileType.epub, SyncTransferPhase.Transferring, 512, 1024, 0, 1024.0) },
    )

    @Test fun keepsQueueTopWhenActiveBooksReorderAndCompletedBooksLeave() {
        val keys = (0..39).toList()
        val state = mutableStateOf(queue(keys, (0..7).toSet()))
        compose.setContent { HoshiReaderTheme { Box(Modifier.height(420.dp)) { SyncQueueContent(state.value, {}) } } }
        compose.onNodeWithText("Book 0").assertIsDisplayed()
        // Book 0 finishes one file but remains queued for its next file; active books move ahead.
        compose.runOnIdle { state.value = queue((1..7).toList() + 0 + (8..39), (1..7).toSet()) }
        compose.onNodeWithText("Book 1").assertIsDisplayed()
        compose.runOnIdle { state.value = queue((2..7).toList() + 0 + (8..39), (2..7).toSet()) }
        compose.onNodeWithText("Book 2").assertIsDisplayed()
        compose.runOnIdle { state.value = queue(listOf(0) + (2..39), setOf(0, 2, 3, 4)) }
        compose.onNodeWithText("Book 0").assertIsDisplayed()
        compose.onNodeWithText("Book 2").assertIsDisplayed()
    }

    @Test fun preservesManualPositionAndFollowsTopAgainAfterReturning() {
        val state = mutableStateOf(queue((0..39).toList(), (0..7).toSet()))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { HoshiReaderTheme { Box(Modifier.height(420.dp)) { SyncQueueContent(state.value, {}) } } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        val before = compose.onNodeWithText("Book 20").fetchSemanticsNode().boundsInRoot.top
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Book 20").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("Book 20").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnIdle { state.value = queue((1..39).toList() + 0, (1..7).toSet()) }
        compose.onNodeWithText("Book 20").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("Book 20").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("Book 1").assertIsDisplayed()
        compose.runOnIdle { state.value = queue(listOf(0) + (1..39), (0..7).toSet()) }
        compose.onNodeWithText("Book 0").assertIsDisplayed()
    }

    @Test fun shrinkingEInkSheetDoesNotDrawAnOutlineAcrossTheBackdrop() {
        val state = mutableStateOf(queue(listOf(0, 1, 2), setOf(0, 1, 2)))
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, eInkMode = true) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    SyncQueueSheet(state.value, {}, {})
                }
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (count in listOf(3, 1, 0)) {
            compose.runOnIdle { state.value = queue((0 until count).toList(), (0 until count).toSet()) }
            compose.waitForIdle()
            val sheetTop = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
                .fetchSemanticsNode().boundsInWindow.top.toInt()
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(instrumentation.targetContext.cacheDir, "sync-queue-sheet-$count.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                if (count == 0) assertTrue("Empty sheet should leave a visible backdrop", sheetTop > bitmap.height / 2)
                // The fixture backdrop is blank. Ignore status icons and the sheet's own edge;
                // a black line across its middle 80% can only be an out-of-place outline.
                for (y in bitmap.height / 10 until sheetTop - 4) {
                    val dark = (bitmap.width / 10 until bitmap.width * 9 / 10).count { x ->
                        android.graphics.Color.red(bitmap.getPixel(x, y)) < 80
                    }
                    assertTrue("$count books: stray horizontal border at y=$y above sheetTop=$sheetTop", dark < bitmap.width / 2)
                }
            } finally { bitmap.recycle() }
        }
    }

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
