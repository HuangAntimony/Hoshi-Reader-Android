package moe.antimony.hoshi.features.diagnostics

import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun newerReloadWinsOverOldSuccessAndExportsLatestRuntimeAndCrashHistory() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pendingReload = CompletableDeferred<ProcessExitDiagnosticsReport>()
        val previous = report("Original runtime error")
        val latest = report("Latest runtime error")
        var loads = 0
        val model = model(load = {
            when (++loads) {
                1 -> previous
                2 -> withContext(NonCancellable) { pendingReload.await() }
                else -> latest
            }
        })
        try {
            model.reload()
            runCurrent()
            assertEquals(previous, model.state.value.report)
            model.reload()
            runCurrent()
            model.reload()
            runCurrent()
            pendingReload.complete(previous)
            runCurrent()

            val current = model.state.value.report!!
            assertEquals(latest, current)
            val exported = model.shareFile(current.toShareText()).readText()
            assertFalse(exported.contains("Original runtime error"))
            assertTrue(exported.contains("Latest runtime error"))
            assertTrue(exported.contains("Original fatal stack"))
        } finally {
            pendingReload.complete(previous)
            model.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun newerReloadSuppressesLateFailureFromOlderRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val previousRequest = CompletableDeferred<ProcessExitDiagnosticsReport>()
        val latest = report("Latest runtime error")
        var loads = 0
        val model = model(
            load = {
                if (++loads == 1) withContext(NonCancellable) { previousRequest.await() } else latest
            },
        )
        try {
            model.reload()
            runCurrent()
            model.reload()
            runCurrent()
            assertEquals(latest, model.state.value.report)

            previousRequest.completeExceptionally(IOException("Old request failed", IllegalStateException("Old provider unavailable")))
            runCurrent()

            assertEquals(latest, model.state.value.report)
            assertNull(model.state.value.loadError)
        } finally {
            previousRequest.complete(latest)
            model.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun currentLoadFailurePreservesVisibleCauseAndOriginalDiagnosticEvent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        ErrorDiagnosticEvents.buffer.drain()
        val failure = IOException("Cannot read process history", IllegalStateException("Service unavailable"))
        val model = model(load = { throw failure })
        try {
            model.reload()
            runCurrent()

            val text = model.state.value.loadError!!.resolve({ _, _ -> "Unable to load diagnostics." }, { _, _, _ -> "" })
            assertTrue(text.contains("Unable to load diagnostics."))
            assertTrue(text.contains("Cannot read process history\nService unavailable"))
            assertSame(failure, ErrorDiagnosticEvents.buffer.drain().single().error)
        } finally {
            model.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    private fun model(load: suspend () -> ProcessExitDiagnosticsReport) = DiagnosticsViewModel(
        loadReport = load,
        shareReport = { text -> temporaryFolder.newFile().apply { writeText(text) } },
        saveReport = { _, _ -> error("Unexpected save") },
    )

    private fun report(message: String) = ProcessExitDiagnosticsReport(
        packageName = "moe.antimony.hoshi.debug",
        versionName = "1",
        versionCode = 1,
        sdkInt = 35,
        nonfatalErrors = listOf(NonfatalDiagnosticRecord(1, "Import book", "java.io.IOException", message, "Original runtime stack")),
        capturedCrashes = listOf(CapturedCrashRecord(2, "Original fatal stack")),
        records = emptyList(),
    )
}
