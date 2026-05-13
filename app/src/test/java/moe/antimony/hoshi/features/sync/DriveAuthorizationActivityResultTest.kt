package moe.antimony.hoshi.features.sync

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveAuthorizationActivityResultTest {
    @Test
    fun cancelledActivityResultWithDataStillParsesAuthorizationPayload() {
        val result = resolveDriveAuthorizationActivityResult(
            resultCode = Activity.RESULT_CANCELED,
            hasData = true,
            authorizationResult = { DriveAuthorizationResult.Authorized("token") },
        )

        assertEquals(DriveAuthStatus.Connected, result.status)
        assertNull(result.message)
    }

    @Test
    fun cancelledActivityResultWithoutDataReportsCancellation() {
        val result = resolveDriveAuthorizationActivityResult(
            resultCode = Activity.RESULT_CANCELED,
            hasData = false,
            authorizationResult = { error("Should not parse without data.") },
        )

        assertEquals(DriveAuthStatus.NotConnected, result.status)
        assertEquals("Google Drive authorization was cancelled.", result.message)
    }
}
