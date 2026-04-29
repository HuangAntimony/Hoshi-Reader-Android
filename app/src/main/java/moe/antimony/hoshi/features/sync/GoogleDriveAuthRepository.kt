@file:Suppress("DEPRECATION")

package moe.antimony.hoshi.features.sync

import android.app.Activity
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope

enum class GoogleDriveAuthState {
    MissingClientId,
    InvalidClientId,
    Configured,
}

interface GoogleDriveAccessTokenProvider {
    fun accountEmail(): String?
    fun accessTokenOrNull(): String?
}

class GoogleDriveAuthRepository(
    private val clientIdProvider: () -> String,
    private val tokenProvider: GoogleDriveAccessTokenProvider,
) {
    fun validateConfiguration(): GoogleDriveAuthState {
        val clientId = clientIdProvider().trim()
        if (clientId.isBlank()) return GoogleDriveAuthState.MissingClientId
        if (!isValidGoogleClientId(clientId)) return GoogleDriveAuthState.InvalidClientId
        return GoogleDriveAuthState.Configured
    }

    fun isSignedIn(): Boolean =
        accountEmail() != null && accessTokenOrNull() != null

    fun accountEmail(): String? = tokenProvider.accountEmail()

    fun accessTokenOrNull(): String? = tokenProvider.accessTokenOrNull()

    companion object {
        const val DriveFileScope = "https://www.googleapis.com/auth/drive.file"

        fun isValidGoogleClientId(clientId: String): Boolean =
            Regex("^[0-9]+-[a-z0-9]+\\.apps\\.googleusercontent\\.com$").matches(clientId.trim())
    }
}

class GooglePlayServicesDriveTokenProvider(
    private val context: Context,
) : GoogleDriveAccessTokenProvider {
    override fun accountEmail(): String? =
        GoogleSignIn.getLastSignedInAccount(context)?.email

    override fun accessTokenOrNull(): String? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        val email = account.email ?: return null
        val scope = "oauth2:${GoogleDriveAuthRepository.DriveFileScope}"
        return runCatching { GoogleAuthUtil.getToken(context, email, scope) }.getOrNull()
    }
}

fun googleDriveSignInClient(activity: Activity, clientId: String): GoogleSignInClient {
    val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestIdToken(clientId)
        .requestScopes(Scope(GoogleDriveAuthRepository.DriveFileScope))
        .build()
    return GoogleSignIn.getClient(activity, options)
}

fun GoogleSignInAccount.driveAccountEmail(): String? = email
