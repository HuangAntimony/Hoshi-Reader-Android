package moe.antimony.hoshi.features.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.resolve
import moe.antimony.hoshi.ui.withErrorDetails
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncErrorDiagnosticsTest {
    @Before fun clearDiagnostics() { ErrorDiagnosticEvents.buffer.drain() }
    @After fun drainDiagnostics() { ErrorDiagnosticEvents.buffer.drain() }

    @Test fun cancellationPropagatesWithoutVisibleErrorOrHistory() = runTest {
        val cancelled = CancellationException("User left the operation")
        var visibleError: UiText? = null
        assertSame(cancelled, runCatching {
            visibleError = syncOperationError { throw cancelled }
        }.exceptionOrNull())
        assertNull(visibleError)
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
    }

    @Test fun failureWithoutMessageStillShowsOriginalType() = runTest {
        val error = IllegalStateException()
        val message = syncOperationError { throw error }!!
            .resolve({ _, _ -> "Sync failed" }, { _, _, _ -> "" })
        assertTrue(message.contains("IllegalStateException"))
        assertSame(error, ErrorDiagnosticEvents.buffer.drain().single().error)
    }

    @Test fun successDoesNotCreateErrorOrHistory() = runTest {
        assertNull(syncOperationError {})
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
    }

    @Test
    fun driveFailurePreservesBothHttpStatusAndServerDiagnostic() {
        val error = GoogleDriveApiException("Storage quota exceeded for project 1234", statusCode = 403)
        val message = UiText.Resource(R.string.sync_google_drive_authorization_failed)
            .withErrorDetails(error)
            .resolve({ _, _ -> "Sync failed" }, { _, _, _ -> "" })
        assertTrue(message.contains("Storage quota exceeded for project 1234"))
        assertTrue(message.contains("403"))
        assertFalse(error.isTransientDriveNetworkFailure())
    }
}
