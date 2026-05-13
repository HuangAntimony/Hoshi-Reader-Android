package moe.antimony.hoshi.features.sync

import android.content.Intent

interface DriveAccessTokenProvider {
    suspend fun accessToken(): String

    suspend fun clearAccessToken(token: String) = Unit
}

interface DriveAuthorizer : DriveAccessTokenProvider {
    suspend fun authorize(): DriveAuthorizationResult

    fun authorizationResultFromIntent(data: Intent?): DriveAuthorizationResult

    suspend fun revokeAccess()

    suspend fun status(): DriveAuthStatus
}
