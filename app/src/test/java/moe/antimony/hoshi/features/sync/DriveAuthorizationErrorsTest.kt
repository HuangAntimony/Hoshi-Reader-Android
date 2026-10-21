package moe.antimony.hoshi.features.sync

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.resolve
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DriveAuthorizationErrorsTest {
    @Before fun clearHistory() { ErrorDiagnosticEvents.buffer.drain() }
    @After fun drainHistory() { ErrorDiagnosticEvents.buffer.drain() }

    @Test
    fun originalFailureAndNestedCauseReachVisibleStateAndDiagnostics() = runTest {
        val failure = IOException("Token exchange timed out", IOException("Connection reset by peer"))
        val visible = MutableStateFlow<UiText?>(null)
        runDriveAuthorization({ visible.value = it }) { throw failure }
        val text = visible.value!!.resolve({ id, _ ->
            assertEquals(R.string.sync_google_drive_authorization_failed, id)
            "Authorization failed"
        }, { _, _, _ -> "" })
        assertTrue(text.contains("Authorization failed"))
        assertTrue(text.contains("Token exchange timed out"))
        assertTrue(text.contains("Connection reset by peer"))
        assertSame(failure, ErrorDiagnosticEvents.buffer.drain().single().error)
    }

    @Test
    fun successAndCoroutineCancellationDoNotShowOrRecordErrors() = runTest {
        var visible: UiText? = null
        runDriveAuthorization({ visible = it }) {}
        val cancellation = CancellationException("Settings closed")
        assertSame(cancellation, runCatching {
            runDriveAuthorization({ visible = it }) { throw cancellation }
        }.exceptionOrNull())
        assertNull(visible)
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
    }

    @Test
    fun googleCancellationIsQuietButPlatformFailureRetainsItsStatusAndCause() = runTest {
        var visible: UiText? = null
        runDriveAuthorization({ visible = it }) { throw ApiException(Status(CommonStatusCodes.CANCELED)) }
        assertNull(visible)
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
        val failure = ApiException(Status(CommonStatusCodes.DEVELOPER_ERROR, "OAuth client registration mismatch"))
        runDriveAuthorization({ visible = it }) { throw failure }
        val text = visible!!.resolve({ _, _ -> "Authorization failed" }, { _, _, _ -> "" })
        assertTrue(text.contains("10"))
        assertTrue(text.contains("OAuth client registration mismatch"))
        assertSame(failure, ErrorDiagnosticEvents.buffer.drain().single().error)
    }
}
