package moe.antimony.hoshi.features.diagnostics

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic history and wire data only; never writes installed diagnostics or crashes a process. */
@RunWith(AndroidJUnit4::class)
class DiagnosticsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun crashPagesAndExportKeepCategoriesExclusiveAndShowNativeCrashesWithOrWithoutTrace() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Hand-encoded Android Tombstone: pid=123, crashing tid=456, SIGABRT,
        // abort message, thread map and one symbolized frame. Independent of the decoder.
        val wire = (
            "0801287b30c8034a186d6f652e616e74696d6f6e792e686f7368692e6465627567" +
                "520b080612075349474142525472146e617469766520666978747572652061626f7274" +
                "82014708c803124208c803120c63726173682d776f726b6572222f08b424220b6e6174" +
                "69766541626f72742807321b2f73797374656d2f6c696236342f6c69626578616d706c652e736f"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val trace = readProcessExitTrace(ProcessExitReason.NativeCrash) { ByteArrayInputStream(wire) }
        assertTrue(trace.orEmpty().contains("nativeAbort+7"))
        val capturedText = "java fixture stack\n" + "x".repeat(7_000) + "MIDDLE_CAUSE" + "y".repeat(7_000)
        val report = ProcessExitDiagnosticsReport(
            packageName = context.packageName, versionName = "fixture", versionCode = 1, sdkInt = 34,
            capturedCrashes = listOf(CapturedCrashRecord(10_000, capturedText)),
            records = listOf(
                exit(30_000, ProcessExitReason.NativeCrash, trace),
                exit(20_000, ProcessExitReason.NativeCrash),
                exit(10_050, ProcessExitReason.JavaCrash),
                exit(5_000, ProcessExitReason.LowMemory),
            ),
        )
        val export = report.toShareText()
        assertEquals(4, Regex("(?m)^Crash \\d+$").findAll(export).count())
        assertEquals(1, Regex("java fixture stack").findAll(export).count())
        assertEquals(1, Regex("nativeAbort\\+7").findAll(export).count())
        assertFalse(export.contains("MIDDLE_CAUSE"))
        val selectedPage = mutableStateOf<DiagnosticsRecordKind?>(DiagnosticsRecordKind.Crashes)
        val model = DiagnosticsViewModel(
            loadReport = { report }, shareReport = { error("Not used by this fixture") },
            saveReport = { _, _ -> error("Not used by this fixture") },
        )
        val store = ViewModelStore().apply { put("diagnostics-fixture", model) }
        try {
            compose.setContent {
                HoshiReaderTheme {
                    DiagnosticsScreen(model, { selectedPage.value = null }, { selectedPage.value = it }, selectedPage.value)
                }
            }
            val nativeTitle = context.getString(R.string.diagnostics_exit_native_crash)
            val javaTitle = context.getString(R.string.diagnostics_exit_java_crash)
            val lowMemoryTitle = context.getString(R.string.diagnostics_exit_low_memory)
            compose.onAllNodesWithText(nativeTitle).assertCountEquals(2)
            compose.onAllNodesWithText(javaTitle).assertCountEquals(1)
            compose.onAllNodesWithText(context.getString(R.string.diagnostics_captured_crash)).assertCountEquals(1)
            compose.onNodeWithText(lowMemoryTitle).assertDoesNotExist()
            compose.onAllNodesWithText(nativeTitle)[0].performClick()
            compose.onNodeWithText("native fixture abort", substring = true).assertIsDisplayed()
            compose.onNodeWithText("nativeAbort+7", substring = true).assertIsDisplayed()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            try {
                File(context.cacheDir, "diagnostics-fixture.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { screenshot.recycle() }
            compose.onNodeWithText(context.getString(R.string.action_close)).performClick()
            compose.onNodeWithText(context.getString(R.string.diagnostics_captured_crash)).performClick()
            compose.onNodeWithText(capturedText).assertExists()
            compose.onNodeWithText(context.getString(R.string.action_close)).performClick()
            compose.runOnIdle { selectedPage.value = DiagnosticsRecordKind.ProcessExits }
            compose.onNodeWithText(lowMemoryTitle).assertIsDisplayed()
            compose.onNodeWithText(nativeTitle).assertDoesNotExist()
            compose.onNodeWithText(javaTitle).assertDoesNotExist()
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun exit(time: Long, reason: ProcessExitReason, trace: String? = null) = ProcessExitRecord(
        time, reason, 6, 100, 0, 0, "fixture exit", trace,
    )
}
