package moe.antimony.hoshi.features.update

import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.*
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.resolve
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PreviewUpdateViewModelTest {
    private val sha = "a".repeat(40)
    private val build = PreviewBuild(sha, "apk-preview-aaaaaaa-42-1", "2026-10-21T10:00:00Z",
        AvailableUpdate("", "https://example/release", "preview.apk", "https://example/preview.apk", sha256 = null,
            previewCommitSha = sha, previewTag = "apk-preview-aaaaaaa-42-1", previewPublishedAt = "2026-10-21T10:00:00Z"))
    private inner class Fake : PreviewUpdateOperations {
        override val records = MutableStateFlow<UpdateDownloadRecord?>(null)
        var downloads = 0
        var failure: Throwable? = null
        var readyFailure: Throwable? = null
        var readyGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun latest(): PreviewLookup { failure?.let { throw it }; return PreviewLookup.Found(build) }
        override suspend fun refresh() = Unit
        override suspend fun download(build: PreviewBuild) { downloads++; records.value = record(UpdateDownloadRecordStatus.Downloading) }
        override suspend fun readyFile(record: UpdateDownloadRecord): File {
            readyGate?.await()
            readyFailure?.let { throw it }
            return File("preview.apk")
        }
        fun record(status: UpdateDownloadRecordStatus) = UpdateDownloadRecord("", assetName = "preview.apk", fileName = "preview.apk",
            downloadId = 1, sha256 = null, status = status, previewCommitSha = sha, previewTag = build.tag,
            previewPublishedAt = build.publishedAt)
    }
    private fun testModel(currentSha: String, block: suspend TestScope.(PreviewUpdateViewModel, Fake) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fake = Fake()
        val model = PreviewUpdateViewModel(fake, currentSha)
        try { runCurrent(); block(model, fake) }
        finally { model.viewModelScope.coroutineContext.job.cancelAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun fullCommitEqualitySkipsDownloadButSameShortPrefixDoesNot() = testModel(sha) { model, fake ->
        model.check(); runCurrent()
        assertNull(model.state.value.pendingBuild)
        assertEquals(UiText.Resource(R.string.preview_up_to_date), model.state.value.message)
        assertEquals(0, fake.downloads)
    }
    @Test fun differingFullCommitRequiresConfirmationAndCancelDoesNotDownload() = testModel("a".repeat(39) + "b") { model, fake ->
        model.check(); runCurrent()
        assertEquals(build, model.state.value.pendingBuild)
        assertEquals(0, fake.downloads)
        model.cancelConfirmation(); model.confirmDownload(); runCurrent()
        assertEquals(0, fake.downloads)
    }
    @Test fun unknownCommitCanDownloadAndForegroundCompletionEmitsOneInstallRequest() = testModel("") { model, fake ->
        model.setForeground(true); runCurrent()
        model.check(); runCurrent(); model.confirmDownload(); model.confirmDownload(); runCurrent()
        assertEquals(1, fake.downloads)
        fake.records.value = fake.record(UpdateDownloadRecordStatus.Downloaded); runCurrent()
        assertEquals(File("preview.apk"), model.takeInstallRequest())
        assertNull(model.takeInstallRequest())
        model.setForeground(false); model.setForeground(true); runCurrent()
        assertNull(model.takeInstallRequest())
    }
    @Test fun backgroundCompletionAndRestoredDownloadWaitForManualInstall() = testModel("") { model, fake ->
        model.setForeground(true); runCurrent(); model.check(); runCurrent(); model.confirmDownload(); runCurrent()
        model.setForeground(false)
        fake.records.value = fake.record(UpdateDownloadRecordStatus.Downloaded); runCurrent()
        model.setForeground(true); runCurrent()
        assertNull(model.takeInstallRequest())
        assertEquals(UpdateDownloadRecordStatus.Downloaded, model.state.value.download?.status)
        model.installDownloaded(); runCurrent()
        assertEquals(File("preview.apk"), model.takeInstallRequest())
    }
    @Test fun leavingDuringValidationCannotAutoInstallAfterReturning() = testModel("") { model, fake ->
        model.setForeground(true); runCurrent(); model.check(); runCurrent(); model.confirmDownload(); runCurrent()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.readyGate = gate
        fake.records.value = fake.record(UpdateDownloadRecordStatus.Downloaded); runCurrent()
        model.setForeground(false); model.setForeground(true)
        gate.complete(Unit); runCurrent()
        assertNull(model.takeInstallRequest())
    }
    @Test fun installationValidationBlocksAnotherDownloadAndRejectsChangedIdentity() = testModel("") { model, fake ->
        model.setForeground(true); runCurrent()
        fake.records.value = fake.record(UpdateDownloadRecordStatus.Downloaded); runCurrent()
        model.check(); runCurrent()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.readyGate = gate
        model.installDownloaded(); runCurrent()
        assertTrue(model.state.value.preparingInstall)
        model.check(); model.confirmDownload(); runCurrent()
        assertEquals(0, fake.downloads)
        fake.records.value = fake.record(UpdateDownloadRecordStatus.Downloaded).copy(downloadId = 2)
        runCurrent(); gate.complete(Unit); runCurrent()
        assertNull(model.takeInstallRequest())
        assertFalse(model.state.value.preparingInstall)
    }
    @Test fun checksumFailureNeverOpensInstallerAndKeepsNestedDiagnostics() = testModel("") { model, fake ->
        ErrorDiagnosticEvents.buffer.drain()
        val failure = IOException("SHA-256 mismatch", IllegalStateException("Expected a, received b"))
        fake.readyFailure = failure
        model.setForeground(true); runCurrent()
        fake.records.value = fake.record(UpdateDownloadRecordStatus.Downloaded); runCurrent()
        model.installDownloaded(); runCurrent()
        assertNull(model.takeInstallRequest())
        val text = model.state.value.error!!.resolve({ _, _ -> "Failed" }, { _, _, _ -> "" })
        assertTrue(text.contains("SHA-256 mismatch")); assertTrue(text.contains("Expected a, received b"))
        assertSame(failure, ErrorDiagnosticEvents.buffer.drain().single().error)
    }
    @Test fun cancelledQueryDoesNotBecomeAVisibleOrRecordedFailure() = testModel("") { model, fake ->
        ErrorDiagnosticEvents.buffer.drain()
        fake.failure = kotlinx.coroutines.CancellationException("Screen closed")
        model.check(); runCurrent()
        assertNull(model.state.value.error)
        assertFalse(model.state.value.busy)
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
    }
    @Test fun errorsRetainNestedCauseInVisibleStateAndDiagnosticHistory() = testModel("") { model, fake ->
        ErrorDiagnosticEvents.buffer.drain()
        val failure = IOException("GitHub HTTP 403", IllegalStateException("Rate limit exhausted"))
        fake.failure = failure
        model.check(); runCurrent()
        val text = model.state.value.error!!.resolve({ _, _ -> "Failed" }, { _, _, _ -> "" })
        assertTrue(text.contains("GitHub HTTP 403")); assertTrue(text.contains("Rate limit exhausted"))
        assertSame(failure, ErrorDiagnosticEvents.buffer.drain().single().error)
    }
}
