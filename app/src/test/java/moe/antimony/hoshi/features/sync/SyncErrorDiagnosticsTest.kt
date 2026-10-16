package moe.antimony.hoshi.features.sync

import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.resolve
import moe.antimony.hoshi.ui.withErrorDetails
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncErrorDiagnosticsTest {
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
