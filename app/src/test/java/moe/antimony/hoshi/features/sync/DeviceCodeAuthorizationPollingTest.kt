package moe.antimony.hoshi.features.sync

import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.features.diagnostics.NonfatalDiagnosticsLogger
import moe.antimony.hoshi.features.diagnostics.NonfatalDiagnosticsStore
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.resolve
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCodeAuthorizationPollingTest {
    @Test
    fun transientNetworkFailuresContinuePollingWithBackoff() {
        assertTrue(UnknownHostException("oauth2.googleapis.com").isTransientDeviceCodePollingFailure())
        assertTrue(SocketTimeoutException("timeout").isTransientDeviceCodePollingFailure())
        assertTrue(IOException("temporary network failure").isTransientDeviceCodePollingFailure())
        assertFalse(DriveAuthException("invalid client").isTransientDeviceCodePollingFailure())

        assertEquals(
            DriveAuthorizationResult.TransientNetworkFailure,
            UnknownHostException("oauth2.googleapis.com").toDeviceCodePollingFailureResult(),
        )
        assertEquals(10L, nextDeviceCodePollIntervalSeconds(5, DriveAuthorizationResult.TransientNetworkFailure))
        assertEquals(60L, nextDeviceCodePollIntervalSeconds(60, DriveAuthorizationResult.TransientNetworkFailure))
    }

    @Test
    fun terminalAuthorizationFailureRetainsItsUnderlyingDiagnosticReason() {
        val result = IllegalStateException("Authorization response invalid", IllegalArgumentException("missing device_code"))
            .toDeviceCodePollingFailureResult() as DriveAuthorizationResult.Failed
        assertTrue(result.message.contains("Authorization response invalid"))
        assertTrue(result.message.contains("missing device_code"))
    }

    @Test
    fun oauthFailureRetainsHttpStatusProviderCodeAndDescription() {
        val message = oauthFailureMessage(
            statusCode = 401,
            errorCode = "invalid_client",
            errorDescription = "The OAuth client was deleted in project 1234",
            summary = "Google OAuth client is invalid",
        )
        assertTrue(message.contains("Google OAuth client is invalid"))
        assertTrue(message.contains("401"))
        assertTrue(message.contains("invalid_client"))
        assertTrue(message.contains("The OAuth client was deleted in project 1234"))
    }

    @Test
    fun terminalPollingFailurePublishesTheOriginalThrowableAndOperationForDiagnostics() {
        ErrorDiagnosticEvents.buffer.drain()
        val cause = IllegalArgumentException("missing device_code")
        val error = IllegalStateException("Authorization response invalid", cause)

        val result = error.toDeviceCodePollingFailureResult() as DriveAuthorizationResult.Failed
        val event = ErrorDiagnosticEvents.buffer.drain().singleOrNull()

        assertTrue(result.message.contains("missing device_code"))
        assertNotNull("A terminal polling failure must reach built-in Diagnostics", event)
        assertSame(error, event!!.error)
        assertSame(cause, requireNotNull(event.error).cause)
        assertEquals("Authorization response invalid", requireNotNull(event.error).message)
        assertEquals(UiText.Resource(R.string.sync_google_drive_authorization_failed), event.operation)
    }

    @Test
    fun transientPollingAndCancellationRemainQuietInDiagnostics() {
        ErrorDiagnosticEvents.buffer.drain()
        IOException("temporary network interruption").toDeviceCodePollingFailureResult()
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            kotlinx.coroutines.CancellationException("user left").toDeviceCodePollingFailureResult()
        }
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
    }

    @Test
    fun terminalOauthResponsePersistsOnlyActualProviderDetailsWithoutAnInventedThrowable() = runTest {
        ErrorDiagnosticEvents.buffer.drain()
        val directory = createTempDirectory("oauth-diagnostics").toFile()
        val logger = NonfatalDiagnosticsLogger(
            NonfatalDiagnosticsStore(directory, StandardTestDispatcher(testScheduler)),
            backgroundScope,
            ErrorDiagnosticEvents.buffer,
        ) { operation ->
            operation.resolve(
                getString = { id, _ ->
                    check(id == R.string.sync_google_drive_authorization_failed)
                    "Google Drive authorization failed."
                },
                getQuantityString = { _, _, _ -> error("Unexpected plural operation") },
            )
        }
        try {
            oauthFailureResult(
                statusCode = 401,
                errorCode = "invalid_client",
                errorDescription = "The OAuth client was deleted in project 1234",
                summary = "Google OAuth client is invalid",
            )
            val record = logger.snapshot().singleOrNull()

            assertNotNull("The actual provider response must survive opening Diagnostics", record)
            assertEquals("Google Drive authorization failed.", record!!.operation)
            assertEquals("HTTP 401\ninvalid_client\nThe OAuth client was deleted in project 1234", record.message)
            assertEquals("", record.exceptionType)
            assertEquals("", record.stackTrace)
        } finally {
            ErrorDiagnosticEvents.buffer.drain()
            directory.deleteRecursively()
        }
    }

    @Test
    fun oauthSlowDownUsesGoogleRequiredIncrement() {
        assertEquals(10L, nextDeviceCodePollIntervalSeconds(5, DriveAuthorizationResult.SlowDown))
        assertEquals(5L, nextDeviceCodePollIntervalSeconds(5, DriveAuthorizationResult.Pending))
    }
}
