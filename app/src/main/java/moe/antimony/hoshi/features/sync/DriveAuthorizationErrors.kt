package moe.antimony.hoshi.features.sync

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.withErrorDetails

internal suspend fun runDriveAuthorization(onError: (UiText) -> Unit, action: suspend () -> Unit) {
    try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (error is ApiException && error.statusCode == CommonStatusCodes.CANCELED) return
        onError(UiText.Resource(R.string.sync_google_drive_authorization_failed).withErrorDetails(error))
    }
}
