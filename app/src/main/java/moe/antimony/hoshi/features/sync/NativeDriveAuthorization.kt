package moe.antimony.hoshi.features.sync

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.CancellationException

/** Only typed status flags enter diagnostics, never a result, Intent, account or token dump. */
internal data class NativeDriveAuthorizationContext(
    val connected: Boolean,
    val playServicesStatus: Int,
    val hasNetwork: Boolean,
    val hasInternet: Boolean,
    val validated: Boolean,
) {
    fun details(): String = "nativeConnected=$connected, playServicesStatus=$playServicesStatus, " +
        "hasNetwork=$hasNetwork, hasInternet=$hasInternet, validated=$validated"
}

internal class NativeDriveAuthorizationException(message: String, cause: Throwable? = null) : DriveAuthException(message, cause)

internal class NativeDriveAuthorizationRecoveryException(cause: Throwable) :
    DriveAuthException("Google Drive authorization needs recovery; the connection has been retained.", cause)

internal suspend fun <T> withNativeDriveAuthorizationDiagnostics(
    context: NativeDriveAuthorizationContext,
    selectAccount: Boolean?,
    authorize: suspend () -> T,
): T = try {
    authorize()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    if (error is ApiException && error.statusCode == CommonStatusCodes.CANCELED) throw error
    throw NativeDriveAuthorizationException(
        "Native Google Drive authorization failed: " +
            (if (selectAccount == null) "stage=resolutionResult, " else "stage=request, selectAccount=$selectAccount, ") +
            "statusCode=${(error as? ApiException)?.statusCode}, ${context.details()}",
        error,
    )
}

internal fun nativeDriveToken(
    token: String?,
    requiresResolution: Boolean,
    grantedScopes: List<String>,
    context: NativeDriveAuthorizationContext,
): String {
    val details = "Native Google Drive authorization result: requiresResolution=$requiresResolution, " +
        "driveScopeGranted=${GoogleDriveAuth.DriveFileScope in grantedScopes}, " +
        "hasAccessToken=${!token.isNullOrEmpty()}, ${context.details()}"
    if (requiresResolution) throw NativeDriveAuthorizationRecoveryException(NativeDriveAuthorizationException(details))
    return token?.takeIf { it.isNotEmpty() } ?: throw NativeDriveAuthorizationException(details)
}
