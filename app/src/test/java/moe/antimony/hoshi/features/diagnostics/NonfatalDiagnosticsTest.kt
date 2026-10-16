package moe.antimony.hoshi.features.diagnostics

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import moe.antimony.hoshi.ui.ErrorDiagnosticBuffer
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class NonfatalDiagnosticsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun handledErrorsSurviveRestartWithTimestampOperationAndCause() = runTest {
        val directory = temporaryFolder.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = NonfatalDiagnosticsStore(directory, dispatcher)
        store.append(record(17, "Import EPUB", "Cannot read book\nBad ZIP"))

        val restored = NonfatalDiagnosticsStore(directory, dispatcher).snapshot().single()
        assertEquals(17L, restored.timestampMillis)
        assertEquals("Import EPUB", restored.operation)
        assertEquals("java.io.IOException", restored.exceptionType)
        assertEquals("Cannot read book\nBad ZIP", restored.message)
        assertTrue(restored.stackTrace.contains("Caused by: java.lang.IllegalArgumentException: Bad ZIP"))
    }

    @Test
    fun storageKeepsTheNewestBoundedHistoryAcrossRestart() = runTest {
        val directory = temporaryFolder.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = NonfatalDiagnosticsStore(directory, dispatcher, maxRecords = 3)
        repeat(5) { store.append(record(it.toLong())) }

        assertEquals(listOf(4L, 3L, 2L), NonfatalDiagnosticsStore(directory, dispatcher, 3).snapshot().map { it.timestampMillis })
    }

    @Test
    fun concurrentAppendsDoNotLoseDistinctErrors() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = NonfatalDiagnosticsStore(temporaryFolder.newFolder(), dispatcher)
        (0L until 40L).map { async { store.append(record(it)) } }.awaitAll()

        val entries = store.snapshot()
        assertEquals(40, entries.size)
        assertEquals((0L until 40L).toSet(), entries.map { it.timestampMillis }.toSet())
    }

    @Test
    fun repeatedThrowableIsRecordedOnceButDistinctRetriesRemainDistinct() {
        val events = ErrorDiagnosticBuffer()
        val error = IOException("Connection refused")
        repeat(3) { events.record(UiText.Literal("Download"), error) }
        events.record(UiText.Literal("Download"), IOException("Connection refused"))

        assertEquals(2, events.drain().size)
        events.record(UiText.Literal("Download"), error)
        assertTrue(events.drain().isEmpty())
    }

    @Test
    fun pendingErrorsAreBoundedAndCancellationIsExcluded() {
        val events = ErrorDiagnosticBuffer(capacity = 3)
        repeat(5) { events.record(UiText.Literal("Download"), IOException("$it"), it.toLong()) }
        events.record(UiText.Literal("Download"), CancellationException("left screen"))

        assertEquals(listOf(2L, 3L, 4L), events.drain().map { it.timestampMillis })
    }

    @Test
    fun openingDiagnosticsImmediatelyFlushesPendingLocalizedErrorWithoutStartingCollector() = runTest {
        val events = ErrorDiagnosticBuffer()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = NonfatalDiagnosticsStore(temporaryFolder.newFolder(), dispatcher)
        val logger = NonfatalDiagnosticsLogger(store, backgroundScope, events) { operation ->
            operation.resolve({ _, _ -> "无法导入书籍。" }, { _, _, _ -> "" })
        }
        events.record(UiText.Resource(42), IOException("Cannot read book", IllegalArgumentException("Bad ZIP")), 27)

        val entry = logger.snapshot().single()
        assertEquals(27L, entry.timestampMillis)
        assertEquals("无法导入书籍。", entry.operation)
        assertEquals("Cannot read book\nBad ZIP", entry.message)
        assertTrue(entry.stackTrace.contains("java.io.IOException: Cannot read book"))
        assertTrue(entry.stackTrace.contains("Caused by: java.lang.IllegalArgumentException: Bad ZIP"))
    }

    @Test
    fun loggingFailureDoesNotThrowOrReplaceOriginalErrorAndLaterEventsRecover() = runTest {
        val directory = temporaryFolder.newFile("not-a-directory")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = ErrorDiagnosticBuffer()
        val logger = NonfatalDiagnosticsLogger(NonfatalDiagnosticsStore(directory, dispatcher), backgroundScope, events) {
            (it as UiText.Literal).value
        }
        events.record(UiText.Literal("Original operation"), IOException("Original error"))
        logger.snapshot()
        assertTrue(directory.delete())
        assertTrue(directory.mkdir())
        events.record(UiText.Literal("Retry operation"), IOException("Retry reason"))

        assertEquals("Retry reason", logger.snapshot().single().message)
        assertTrue(events.drain().isEmpty())
    }

    @Test
    fun reportExportsRuntimeErrorsTogetherWithCrashAndProcessExitHistory() {
        val report = ProcessExitDiagnosticsReport(
            packageName = "moe.antimony.hoshi.debug", versionName = "1", versionCode = 1, sdkInt = 35,
            capturedCrashes = listOf(CapturedCrashRecord(1, "Fatal stack")),
            nonfatalErrors = listOf(record(2, "Save backup", "Disk full\nProvider unavailable")),
            records = emptyList(),
        )

        val text = report.toShareText()
        assertTrue(text.contains("Save backup"))
        assertTrue(text.contains("1970-01-01T00:00:00.002Z"))
        assertTrue(text.contains("Disk full\nProvider unavailable"))
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException: Bad ZIP"))
        assertTrue(text.contains("Fatal stack"))
        assertTrue(text.contains("No recent process exits recorded by Android."))
    }

    @Test
    fun largeReportsAreSharedAsReadableFilesAndOldExportsAreBounded() {
        val cache = temporaryFolder.newFolder()
        val text = "original error details\n".repeat(70_000)
        repeat(8) { createDiagnosticsShareFile(cache, "previous", it.toLong()) }

        val file = createDiagnosticsShareFile(cache, text, 1_700_000_000_000)
        assertEquals("diagnostics-exports", file.parentFile.name)
        assertEquals("hoshi-diagnostics-20231114-221320.txt", file.name)
        assertEquals(text, file.readText())
        assertTrue(file.parentFile.listFiles().orEmpty().size <= 5)
        assertFalse(file.extension.isBlank())
    }

    @Test
    fun applicationCollectorPersistsErrorsWithoutOpeningDiagnostics() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = ErrorDiagnosticBuffer()
        val store = NonfatalDiagnosticsStore(temporaryFolder.newFolder(), dispatcher)
        val logger = NonfatalDiagnosticsLogger(store, backgroundScope, events) { (it as UiText.Literal).value }
        logger.start()
        events.record(UiText.Literal("Download dictionary"), IOException("HTTP 503"), 81)
        runCurrent()

        val entry = store.snapshot().single()
        assertEquals(81L, entry.timestampMillis)
        assertEquals("Download dictionary", entry.operation)
        assertEquals("HTTP 503", entry.message)
    }

    @Test
    fun oversizedExceptionPayloadIsBoundedWhileKeepingOriginalHeadAndRootCause() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = ErrorDiagnosticBuffer()
        val logger = NonfatalDiagnosticsLogger(
            NonfatalDiagnosticsStore(temporaryFolder.newFolder(), dispatcher), backgroundScope, events,
        ) { (it as UiText.Literal).value }
        events.record(
            UiText.Literal("Download"),
            IOException("Outer failure " + "x".repeat(50_000), IllegalStateException("Original root cause")),
        )

        val entry = logger.snapshot().single()
        assertTrue(entry.message.startsWith("Outer failure"))
        assertTrue(entry.message.endsWith("Original root cause"))
        assertTrue(entry.stackTrace.startsWith("java.io.IOException: Outer failure"))
        assertTrue(entry.stackTrace.contains("Caused by: java.lang.IllegalStateException: Original root cause"))
        assertTrue(entry.message.length < 13_000)
        assertTrue(entry.stackTrace.length < 25_000)
    }

    @Test
    fun reportUsesLocalizedRuntimeLabelsWhileRetainingOriginalDiagnostics() {
        val report = ProcessExitDiagnosticsReport(
            packageName = "moe.antimony.hoshi.debug", versionName = "1", versionCode = 1, sdkInt = 35,
            nonfatalErrors = listOf(record(2, "保存备份", "Disk full")), records = emptyList(),
        )
        val text = report.toShareText(
            NonfatalDiagnosticsLabels(
                title = "运行错误", time = { "时间：$it" }, operation = { "操作：$it" },
                exception = { "异常：$it" }, details = "详情：", trace = "堆栈：",
            ),
        )

        assertTrue(text.contains("运行错误"))
        assertTrue(text.contains("操作：保存备份"))
        assertTrue(text.contains("异常：java.io.IOException"))
        assertTrue(text.contains("详情：\nDisk full"))
        assertTrue(text.contains("堆栈：\njava.io.IOException"))
    }

    @Test
    fun corruptHandledHistoryAutomaticallyRecoversAndPersistsOriginalParseFailureAndLaterErrors() = runTest {
        val directory = temporaryFolder.newFolder()
        val corruptText = "{broken diagnostic JSON"
        val history = directory.resolve("handled-errors.json").apply { writeText(corruptText) }
        val expectedFailure = runCatching { Json.decodeFromString<List<NonfatalDiagnosticRecord>>(corruptText) }.exceptionOrNull()!!
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = ErrorDiagnosticBuffer()
        val logger = NonfatalDiagnosticsLogger(
            NonfatalDiagnosticsStore(directory, dispatcher, 100), backgroundScope, events,
        ) { operation -> operation.resolve({ _, _ -> "无法读取运行错误。" }, { _, _, _ -> "" }) }
        val platformReport = ProcessExitDiagnosticsReport(
            packageName = "moe.antimony.hoshi.debug", versionName = "1", versionCode = 1, sdkInt = 35,
            capturedCrashes = listOf(CapturedCrashRecord(1, "Original fatal stack")),
            records = listOf(ProcessExitRecord(2, ProcessExitReason.NativeCrash, 11, 100, 0, 0, "Native crash", null)),
        )

        events.record(UiText.Literal("Pending import"), IOException("Pending failure", IllegalStateException("Pending cause")), 18)
        val report = loadDiagnosticsReport(logger, "无法读取运行错误。") { platformReport }
        assertEquals("Pending failure\nPending cause", report.nonfatalErrors.first().message)
        val readFailure = report.nonfatalErrors.last()
        assertEquals("无法读取运行错误。", readFailure.operation)
        assertEquals(expectedFailure.javaClass.name, readFailure.exceptionType)
        assertTrue(readFailure.message.contains(expectedFailure.message!!))
        assertTrue(readFailure.stackTrace.contains(expectedFailure.javaClass.name))
        assertEquals("Original fatal stack", report.capturedCrashes.single().text)
        assertEquals(ProcessExitReason.NativeCrash, report.records.single().reason)
        assertEquals(corruptText, directory.resolve("handled-errors.corrupt.json").readText())
        assertEquals(report.nonfatalErrors, Json.decodeFromString<List<NonfatalDiagnosticRecord>>(history.readText()))
        assertTrue(events.drain().isEmpty())

        events.record(UiText.Literal("Retry import"), IOException("New failure", IllegalStateException("New cause")), 19)
        val recovered = loadDiagnosticsReport(logger, "无法读取运行错误。") { platformReport }
        assertEquals("New failure\nNew cause", recovered.nonfatalErrors.first().message)
        assertTrue(recovered.nonfatalErrors.first().stackTrace.contains("Caused by: java.lang.IllegalStateException: New cause"))
        assertEquals(readFailure, recovered.nonfatalErrors.last())
        assertEquals(recovered.nonfatalErrors, NonfatalDiagnosticsStore(directory, dispatcher, 100).snapshot())
        assertEquals("Original fatal stack", recovered.capturedCrashes.single().text)
    }

    @Test
    fun corruptionBackupKeepsOnlyTheLatestOriginalFileAcrossRepeatedRecovery() = runTest {
        val directory = temporaryFolder.newFolder()
        val history = directory.resolve("handled-errors.json")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logger = NonfatalDiagnosticsLogger(
            NonfatalDiagnosticsStore(directory, dispatcher, 100), backgroundScope, ErrorDiagnosticBuffer(),
        ) { "Read runtime history" }
        repeat(3) { index ->
            history.writeText("{broken JSON $index")
            loadDiagnosticsReport(logger, "Read runtime history") {
                ProcessExitDiagnosticsReport("moe.antimony.hoshi.debug", "1", 1, 35, records = emptyList())
            }
        }

        assertEquals("{broken JSON 2", directory.resolve("handled-errors.corrupt.json").readText())
        assertEquals(1, directory.listFiles { file -> file.name.contains("corrupt") }.orEmpty().size)
        assertEquals(1, NonfatalDiagnosticsStore(directory, dispatcher, 100).snapshot().size)
    }

    @Test
    fun nonParsingHistoryReadFailureRemainsVisibleWithoutOverwritingTheSourceOrRecursiveEvents() = runTest {
        val directory = temporaryFolder.newFolder()
        val history = directory.resolve("handled-errors.json").apply { mkdir() }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = ErrorDiagnosticBuffer()
        val logger = NonfatalDiagnosticsLogger(
            NonfatalDiagnosticsStore(directory, dispatcher, 100), backgroundScope, events,
        ) { "Read runtime history" }
        val original = runCatching { logger.snapshot() }.exceptionOrNull()!!

        val report = loadDiagnosticsReport(logger, "Read runtime history") {
            ProcessExitDiagnosticsReport(
                "moe.antimony.hoshi.debug", "1", 1, 35,
                capturedCrashes = listOf(CapturedCrashRecord(1, "Original fatal stack")), records = emptyList(),
            )
        }
        val readFailure = report.nonfatalErrors.single()
        assertEquals(original.javaClass.name, readFailure.exceptionType)
        assertTrue(readFailure.message.contains(original.message!!))
        assertTrue(readFailure.stackTrace.contains(original.javaClass.name))
        assertTrue(history.isDirectory)
        assertFalse(directory.resolve("handled-errors.corrupt.json").exists())
        assertTrue(events.drain().isEmpty())
        assertEquals("Original fatal stack", report.capturedCrashes.single().text)
    }

    @Test
    fun serviceFailuresRetainActualHttpAndProviderCodesWithoutInventingThrowableOrStack() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = ErrorDiagnosticBuffer()
        val logger = NonfatalDiagnosticsLogger(
            NonfatalDiagnosticsStore(temporaryFolder.newFolder(), dispatcher), backgroundScope, events,
        ) { (it as UiText.Literal).value }
        val details = "HTTP 401\nprovider_code: invalid_api_key"
        repeat(2) { events.recordServiceFailure(UiText.Literal("Sign in"), details, 89) }

        val entries = logger.snapshot()
        assertEquals(2, entries.size)
        val entry = entries.first()
        assertEquals(89L, entry.timestampMillis)
        assertEquals("Sign in", entry.operation)
        assertEquals(details, entry.message)
        assertEquals("", entry.exceptionType)
        assertEquals("", entry.stackTrace)
        val text = entry.toReportText(
            NonfatalDiagnosticsLabels(
                "Runtime Errors", { "Time: $it" }, { "Operation: $it" }, { "Exception: $it" }, "Details:", "Stack trace:",
            ),
        )
        assertTrue(text.contains(details))
        assertFalse(text.contains("Exception:"))
        assertFalse(text.contains("Stack trace:"))
    }

    @Test
    fun cancellingScreenSnapshotAfterDrainingDoesNotLosePendingOriginalErrors() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = ErrorDiagnosticBuffer()
        val store = NonfatalDiagnosticsStore(temporaryFolder.newFolder(), dispatcher)
        lateinit var snapshotJob: Job
        var resolvedOperations = 0
        var snapshotReturned = false
        val logger = NonfatalDiagnosticsLogger(store, backgroundScope, events) { operation ->
            if (resolvedOperations++ == 0) snapshotJob.cancel(CancellationException("Diagnostics closed"))
            (operation as UiText.Literal).value
        }
        repeat(3) { index ->
            events.record(
                UiText.Literal("Import $index"),
                IOException("Original failure $index", IllegalStateException("Original cause $index")),
                index.toLong(),
            )
        }

        snapshotJob = launch(dispatcher) {
            logger.snapshot()
            snapshotReturned = true
        }
        runCurrent()
        snapshotJob.join()
        logger.start()
        runCurrent()

        assertTrue(snapshotJob.isCancelled)
        assertFalse(snapshotReturned)
        val entries = store.snapshot()
        assertEquals(listOf(2L, 1L, 0L), entries.map { it.timestampMillis })
        assertEquals(listOf("Import 2", "Import 1", "Import 0"), entries.map { it.operation })
        entries.forEach { entry ->
            assertEquals("Original failure ${entry.timestampMillis}\nOriginal cause ${entry.timestampMillis}", entry.message)
            assertTrue(entry.stackTrace.contains("Caused by: java.lang.IllegalStateException: Original cause ${entry.timestampMillis}"))
        }
    }

    private fun record(timestamp: Long, operation: String = "Import EPUB", message: String = "Cannot read book") =
        NonfatalDiagnosticRecord(
            timestampMillis = timestamp,
            operation = operation,
            exceptionType = "java.io.IOException",
            message = message,
            stackTrace = "java.io.IOException: Cannot read book\nCaused by: java.lang.IllegalArgumentException: Bad ZIP",
        )
}
