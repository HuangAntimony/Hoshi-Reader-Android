package moe.antimony.hoshi.features.update

import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.resolve
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AboutActionsViewModelTest {
    @Test fun saveFailureRetainsNestedCauseAndDiagnosticsAndCanRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        ErrorDiagnosticEvents.buffer.drain()
        val failure = IOException("Provider refused write", IOException("Storage full"))
        var calls = 0
        val model = AboutActionsViewModel(save = { calls++; if (calls == 1) throw failure })
        try {
            model.saveImage(); model.saveImage(); runCurrent()
            assertEquals(1, calls)
            val text = model.state.value.error!!.resolve({ _, _ -> "Failed" }, { _, _, _ -> "" })
            assertTrue(text.contains("Provider refused write")); assertTrue(text.contains("Storage full"))
            assertSame(failure, ErrorDiagnosticEvents.buffer.drain().single().error)
            model.dismissError(); model.saveImage(); runCurrent()
            assertTrue(model.state.value.saved)
            assertFalse(model.state.value.saving)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun linkFailureReachesVisibleStateAndHistory() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        ErrorDiagnosticEvents.buffer.drain()
        val model = AboutActionsViewModel(save = {})
        try {
            val linkError = IOException("No browser", IOException("No matching activity"))
            model.reportError(moe.antimony.hoshi.R.string.about_open_link_failed, linkError)
            assertTrue(model.state.value.error!!.resolve({ _, _ -> "Failed" }, { _, _, _ -> "" }).contains("No matching activity"))
            assertSame(linkError, ErrorDiagnosticEvents.buffer.drain().single().error)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun saveCompletesEvenWhenDonationDialogCloses() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val model = AboutActionsViewModel(save = { gate.await() })
        try {
            model.openWechat()
            assertTrue(model.state.value.showWechat)
            model.saveImage(); runCurrent()
            model.closeWechat()
            assertFalse(model.state.value.showWechat)
            assertTrue(model.state.value.saving)
            gate.complete(Unit); runCurrent()
            assertTrue(model.state.value.saved)
            assertFalse(model.state.value.saving)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun wechatIsOnlyShownForChineseInterface() {
        assertTrue(showWechatSupport("zh"))
        assertFalse(showWechatSupport("en"))
        assertFalse(showWechatSupport("ja"))
        assertFalse(showWechatSupport(""))
    }
    @Test fun cancelledSaveIsNotAnError() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        ErrorDiagnosticEvents.buffer.drain()
        val model = AboutActionsViewModel(save = { throw CancellationException("Cancelled") })
        try {
            model.saveImage(); runCurrent()
            assertNull(model.state.value.error)
            assertFalse(model.state.value.saving)
            assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
