package moe.antimony.hoshi.features.sync

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.ui.diagnosticMessage
import org.junit.Assert.*
import org.junit.Test

class NativeDriveAuthorizationTest {
    private val context = NativeDriveAuthorizationContext(
        connected = true, playServicesStatus = 0, hasNetwork = true, hasInternet = true, validated = false,
    )

    @Test fun resolutionRequiredKeepsSafeResultAndNetworkContextWithoutTokenOrScopeContents() {
        val error = runCatching {
            nativeDriveToken("private-token", true, listOf("private-scope"), context)
        }.exceptionOrNull()!!
        assertTrue(error is NativeDriveAuthorizationRecoveryException)
        assertTrue(error.isRetryableBackgroundSyncFailure())
        assertFalse(error.isSyncNetworkFailure())
        val text = error.diagnosticMessage()
        for (detail in listOf("requiresResolution=true", "driveScopeGranted=false", "hasAccessToken=true",
            "nativeConnected=true", "playServicesStatus=0", "hasInternet=true", "validated=false")) {
            assertTrue(text, text.contains(detail))
        }
        assertFalse(text.contains("private-token"))
        assertFalse(text.contains("private-scope"))
    }

    @Test fun sdkFailureRetainsStatusContextAndOriginalCauseAndIsRetryableForNetworkErrors() = runTest {
        val original = ApiException(Status(CommonStatusCodes.NETWORK_ERROR, "Network unavailable"))
        val error = runCatching {
            withNativeDriveAuthorizationDiagnostics(context, selectAccount = false) { throw original }
        }.exceptionOrNull()!!
        assertSame(original, error.cause)
        val text = error.diagnosticMessage()
        for (detail in listOf("statusCode=7", "selectAccount=false", "validated=false", "Network unavailable")) {
            assertTrue(text, text.contains(detail))
        }
        assertTrue(error.isSyncNetworkFailure())
        assertTrue(error.isRetryableBackgroundSyncFailure())
        val denied = runCatching {
            withNativeDriveAuthorizationDiagnostics(context, selectAccount = true) {
                throw ApiException(Status(CommonStatusCodes.DEVELOPER_ERROR))
            }
        }.exceptionOrNull()!!
        assertFalse(denied.isSyncNetworkFailure())
    }

    @Test fun successfulTokenAndCancellationRemainUnchangedAndMissingTokenDoesNotClearConsent() = runTest {
        assertEquals("token", nativeDriveToken("token", false, listOf(GoogleDriveAuth.DriveFileScope), context))
        val missing = runCatching { nativeDriveToken(null, false, emptyList(), context) }.exceptionOrNull()!!
        assertFalse(missing is DriveAuthorizationRequiredException)
        assertTrue(missing.diagnosticMessage().contains("hasAccessToken=false"))
        val cancelled = CancellationException("screen closed")
        assertSame(cancelled, runCatching {
            withNativeDriveAuthorizationDiagnostics(context, false) { throw cancelled }
        }.exceptionOrNull())
        val apiCancelled = ApiException(Status(CommonStatusCodes.CANCELED))
        assertSame(apiCancelled, runCatching {
            withNativeDriveAuthorizationDiagnostics(context, true) { throw apiCancelled }
        }.exceptionOrNull())
    }
}
