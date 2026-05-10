package moe.antimony.hoshi.features.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProcessExitDiagnosticsTest {
    @Test
    fun shareTextIncludesAppDeviceAndRecentExitDetails() {
        val report = ProcessExitDiagnosticsReport(
            packageName = "moe.antimony.hoshi.debug",
            versionName = "0.1.5",
            versionCode = 105,
            sdkInt = 35,
            capturedCrashes = listOf(
                CapturedCrashRecord(
                    timestampMillis = 1_700_000_000_123,
                    text = """
                        Captured Hoshi crash
                        Thread: main
                        java.util.regex.PatternSyntaxException: Syntax error in regexp pattern near index 8
                        \{[^}]+}
                            at moe.antimony.hoshi.features.anki.AnkiRepositoryKt.<clinit>(AnkiRepository.kt:237)
                    """.trimIndent(),
                ),
            ),
            records = listOf(
                ProcessExitRecord(
                    timestampMillis = 1_700_000_000_000,
                    reason = ProcessExitReason.JavaCrash,
                    status = 0,
                    importance = 100,
                    pssKb = 12_345,
                    rssKb = 67_890,
                    description = "FATAL EXCEPTION: main",
                    trace = "java.lang.IllegalStateException: boom",
                ),
            ),
        )

        val text = report.toShareText()

        assertTrue(text.contains("Hoshi Diagnostics"))
        assertTrue(text.contains("Package: moe.antimony.hoshi.debug"))
        assertTrue(text.contains("Version: 0.1.5 (105)"))
        assertTrue(text.contains("Android SDK: 35"))
        assertTrue(text.contains("Captured Crash 1"))
        assertTrue(text.contains("java.util.regex.PatternSyntaxException"))
        assertTrue(text.contains("AnkiRepositoryKt.<clinit>"))
        assertTrue(text.contains("Reason: Java crash"))
        assertTrue(text.contains("Description: FATAL EXCEPTION: main"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
    }

    @Test
    fun shareTextUsesAHelpfulUnsupportedMessageWhenExitHistoryIsUnavailable() {
        val report = ProcessExitDiagnosticsReport(
            packageName = "moe.antimony.hoshi.debug",
            versionName = "0.1.5",
            versionCode = 105,
            sdkInt = 29,
            records = emptyList(),
        )

        val text = report.toShareText()

        assertTrue(text.contains("Android SDK: 29"))
        assertTrue(text.contains("Process exit history is available on Android 11 and later."))
    }

    @Test
    fun shareTextIsBoundedForAndroidShareTargets() {
        val tracePrefix = "java.lang.IllegalStateException: root cause\n"
        val traceSuffix = "\n\tat android.os.Looper.loop(Looper.java:313)"
        val omittedMiddle = "x".repeat(ProcessExitDiagnosticsReport.MAX_TRACE_CHARS + 500)
        val longTrace = tracePrefix + omittedMiddle + traceSuffix
        val report = ProcessExitDiagnosticsReport(
            packageName = "moe.antimony.hoshi.debug",
            versionName = "0.1.5",
            versionCode = 105,
            sdkInt = 35,
            records = listOf(
                ProcessExitRecord(
                    timestampMillis = 1_700_000_000_000,
                    reason = ProcessExitReason.NativeCrash,
                    status = 11,
                    importance = 100,
                    pssKb = 0,
                    rssKb = 0,
                    description = null,
                    trace = longTrace,
                ),
            ),
        )

        val text = report.toShareText()

        assertTrue("missing trace prefix in ${text.take(500)}", text.contains(tracePrefix.trimEnd()))
        assertTrue("missing trace suffix in ${text.takeLast(500)}", text.contains(traceSuffix.trimStart()))
        assertTrue(text.contains("[trace truncated to first and last"))
        assertFalse(text.contains(longTrace))
    }

    @Test
    fun shareTextOmitsUnreadableBinaryTracePayloads() {
        val report = ProcessExitDiagnosticsReport(
            packageName = "moe.antimony.hoshi.debug",
            versionName = "0.1.5",
            versionCode = 105,
            sdkInt = 35,
            records = listOf(
                ProcessExitRecord(
                    timestampMillis = 1_700_000_000_000,
                    reason = ProcessExitReason.NativeCrash,
                    status = 11,
                    importance = 100,
                    pssKb = 0,
                    rssKb = 0,
                    description = "crash",
                    trace = "\u0000\u0001\u0002\u0003\u0004\u0005binary tombstone payload",
                ),
            ),
        )

        val text = report.toShareText()

        assertTrue(text.contains("Reason: Native crash"))
        assertTrue(text.contains("Description: crash"))
        assertFalse(text.contains("Trace:"))
        assertFalse(text.contains("binary tombstone payload"))
    }

    @Test
    fun capturedCrashWriterPersistsConcreteStackTraceBeforeAndroidHandlesTheCrash() {
        val dir = Files.createTempDirectory("hoshi-crash-diagnostics").toFile()
        val throwable = IllegalStateException(
            "outer",
            java.util.regex.PatternSyntaxException("Syntax error in regexp pattern", "\\{[^}]+}", 8),
        )

        saveCapturedCrashDiagnostic(
            diagnosticsDir = dir,
            thread = Thread("main"),
            throwable = throwable,
            timestampMillis = 1_700_000_000_000,
            packageName = "moe.antimony.hoshi.debug",
            versionName = "0.3.4",
            versionCode = 304,
            sdkInt = 35,
        )

        val crashes = loadCapturedCrashDiagnostics(dir)

        assertEquals(1, crashes.size)
        assertTrue(crashes.first().text.contains("Thread: main"))
        assertTrue(crashes.first().text.contains("java.lang.IllegalStateException: outer"))
        assertTrue(crashes.first().text.contains("java.util.regex.PatternSyntaxException"))
        assertTrue(crashes.first().text.contains("""\{[^}]+}"""))
    }

    @Test
    fun exportFileNameIsStableAndSafeForAndroidDocumentPicker() {
        assertEquals(
            "hoshi-diagnostics-20231114-221320.txt",
            diagnosticsExportFileName(1_700_000_000_000),
        )
    }

    @Test
    fun diagnosticsScreenHandlesSystemBackLikeToolbarBack() {
        val source = File("src/main/java/moe/antimony/hoshi/features/diagnostics/DiagnosticsView.kt").readText()

        assertTrue(source.contains("BackHandler(onBack = onClose)"))
    }

    @Test
    fun applicationInstallsPersistentCrashDiagnostics() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val application = File("src/main/java/moe/antimony/hoshi/HoshiApplication.kt").readText()

        assertTrue(manifest.contains("""android:name=".HoshiApplication""""))
        assertTrue(application.contains("installCrashDiagnostics"))
    }
}
