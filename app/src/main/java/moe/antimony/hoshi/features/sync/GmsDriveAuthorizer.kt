package moe.antimony.hoshi.features.sync

import android.accounts.Account
import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class GmsDriveAuthorizer(
    context: Context,
) : DriveAuthorizer {
    private val appContext = context.applicationContext
    private val authorizationClient = Identity.getAuthorizationClient(appContext)
    private val scopes = listOf(Scope(DriveFileScope))
    private var lastAuthorizedAccount: Account? = null

    override suspend fun accessToken(): String =
        when (val result = authorize()) {
            is DriveAuthorizationResult.Authorized -> result.accessToken
            is DriveAuthorizationResult.RequiresResolution -> throw DriveAuthorizationRequiredException()
            DriveAuthorizationResult.GooglePlayServicesUnavailable -> throw DriveAuthException(GmsRequiredMessage)
            is DriveAuthorizationResult.Failed -> throw DriveAuthException(result.message)
        }

    override suspend fun clearAccessToken(token: String) {
        if (!isGooglePlayServicesAvailable()) return
        authorizationClient.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
    }

    override suspend fun authorize(): DriveAuthorizationResult {
        if (!isGooglePlayServicesAvailable()) {
            return DriveAuthorizationResult.GooglePlayServicesUnavailable
        }
        return runCatching {
            authorizationClient.authorize(authorizationRequest()).await().toDriveAuthorizationResult()
        }.getOrElse { error ->
            DriveAuthorizationResult.Failed(error.authMessage())
        }
    }

    override fun authorizationResultFromIntent(data: Intent?): DriveAuthorizationResult {
        if (!isGooglePlayServicesAvailable()) {
            return DriveAuthorizationResult.GooglePlayServicesUnavailable
        }
        if (data == null) {
            return DriveAuthorizationResult.Failed("Google Drive authorization was cancelled.")
        }
        return runCatching {
            authorizationClient.getAuthorizationResultFromIntent(data).toDriveAuthorizationResult()
        }.getOrElse { error ->
            DriveAuthorizationResult.Failed(error.authMessage())
        }
    }

    override suspend fun revokeAccess() {
        if (!isGooglePlayServicesAvailable()) return
        val account = lastAuthorizedAccount ?: return
        val builder = RevokeAccessRequest.builder().setScopes(scopes)
        builder.setAccount(account)
        runCatching { authorizationClient.revokeAccess(builder.build()).await() }
        lastAuthorizedAccount = null
    }

    override suspend fun status(): DriveAuthStatus =
        when (val result = authorize()) {
            is DriveAuthorizationResult.Authorized -> DriveAuthStatus.Connected
            is DriveAuthorizationResult.RequiresResolution -> DriveAuthStatus.NotConnected
            DriveAuthorizationResult.GooglePlayServicesUnavailable -> DriveAuthStatus.GooglePlayServicesUnavailable
            is DriveAuthorizationResult.Failed -> DriveAuthStatus.Failed(result.message)
        }

    fun isGooglePlayServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS

    private fun authorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.Builder()
            .setRequestedScopes(scopes)
            .build()

    private fun AuthorizationResult.toDriveAuthorizationResult(): DriveAuthorizationResult {
        if (hasResolution()) {
            val intent = pendingIntent
                ?: return DriveAuthorizationResult.Failed("Google Drive authorization did not return a pending intent.")
            return DriveAuthorizationResult.RequiresResolution(IntentSenderRequest.Builder(intent).build())
        }
        lastAuthorizedAccount = toGoogleSignInAccount()?.account
        val token = accessToken
        return if (token.isNullOrBlank()) {
            DriveAuthorizationResult.Failed("Google Drive authorization did not return an access token.")
        } else {
            DriveAuthorizationResult.Authorized(token)
        }
    }

    private suspend fun <T> Task<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result -> continuation.resume(result) }
            addOnFailureListener { error -> continuation.resumeWithException(error) }
            addOnCanceledListener { continuation.cancel() }
        }

    private fun Throwable.authMessage(): String {
        val status = (this as? ApiException)?.statusCode
        val base = localizedMessage ?: message
        return when {
            status != null -> base ?: "Google Drive authorization failed with status $status."
            else -> base ?: "Google Drive authorization failed."
        }
    }

    companion object {
        const val DriveFileScope = "https://www.googleapis.com/auth/drive.file"
        const val GmsRequiredMessage = "Google Play services is required for Google Drive sync in this version."
    }
}
