package moe.antimony.hoshi.features.sync

import android.app.Activity
import androidx.activity.result.IntentSenderRequest

internal data class DriveAuthorizationActivityUiResult(
    val status: DriveAuthStatus,
    val message: String?,
    val resolutionRequest: IntentSenderRequest? = null,
)

internal fun resolveDriveAuthorizationActivityResult(
    resultCode: Int,
    hasData: Boolean,
    authorizationResult: () -> DriveAuthorizationResult,
): DriveAuthorizationActivityUiResult {
    if (resultCode != Activity.RESULT_OK && !hasData) {
        return DriveAuthorizationActivityUiResult(
            status = DriveAuthStatus.NotConnected,
            message = "Google Drive authorization was cancelled.",
        )
    }
    return when (val auth = authorizationResult()) {
        is DriveAuthorizationResult.Authorized ->
            DriveAuthorizationActivityUiResult(status = DriveAuthStatus.Connected, message = null)
        DriveAuthorizationResult.GooglePlayServicesUnavailable ->
            DriveAuthorizationActivityUiResult(
                status = DriveAuthStatus.GooglePlayServicesUnavailable,
                message = GmsDriveAuthorizer.GmsRequiredMessage,
            )
        is DriveAuthorizationResult.RequiresResolution ->
            DriveAuthorizationActivityUiResult(
                status = DriveAuthStatus.NotConnected,
                message = null,
                resolutionRequest = auth.request,
            )
        is DriveAuthorizationResult.Failed ->
            DriveAuthorizationActivityUiResult(
                status = DriveAuthStatus.Failed(auth.message),
                message = auth.message,
            )
    }
}
