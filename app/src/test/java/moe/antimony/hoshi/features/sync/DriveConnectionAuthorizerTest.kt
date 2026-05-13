package moe.antimony.hoshi.features.sync

import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveConnectionAuthorizerTest {
    @Test
    fun disconnectedStatusDoesNotSilentlyReconnectFromGoogleGrant() = runBlocking {
        val store = FakeDriveConnectionStore(connected = false)
        val delegate = FakeDriveAuthorizer(status = DriveAuthStatus.Connected)
        val authorizer = DriveConnectionAuthorizer(delegate, store)

        assertEquals(DriveAuthStatus.NotConnected, authorizer.status())
        assertEquals(0, delegate.statusCalls)
    }

    @Test
    fun authorizeMarksLocalConnectionAfterTokenIsGranted() = runBlocking {
        val store = FakeDriveConnectionStore(connected = false)
        val delegate = FakeDriveAuthorizer(authorizeResult = DriveAuthorizationResult.Authorized("token"))
        val authorizer = DriveConnectionAuthorizer(delegate, store)

        assertEquals(DriveAuthorizationResult.Authorized("token"), authorizer.authorize())
        assertTrue(store.isConnected())
    }

    @Test
    fun accessTokenRequiresLocalConnectionBeforeCallingGoogle() = runBlocking {
        val store = FakeDriveConnectionStore(connected = false)
        val delegate = FakeDriveAuthorizer(accessToken = "token")
        val authorizer = DriveConnectionAuthorizer(delegate, store)

        val error = runCatching { authorizer.accessToken() }.exceptionOrNull()

        assertTrue(error is DriveAuthorizationRequiredException)
        assertEquals(0, delegate.accessTokenCalls)
    }

    @Test
    fun revokeAccessClearsLocalConnectionEvenWhenGoogleRevokeFails() = runBlocking {
        val store = FakeDriveConnectionStore(connected = true)
        val delegate = FakeDriveAuthorizer(revokeError = IllegalStateException("boom"))
        val authorizer = DriveConnectionAuthorizer(delegate, store)

        authorizer.revokeAccess()

        assertFalse(store.isConnected())
        assertEquals(1, delegate.revokeCalls)
    }

    @Test
    fun authorizationIntentResultMarksLocalConnectionAfterTokenIsGranted() {
        val store = FakeDriveConnectionStore(connected = false)
        val delegate = FakeDriveAuthorizer(intentResult = DriveAuthorizationResult.Authorized("token"))
        val authorizer = DriveConnectionAuthorizer(delegate, store)

        assertEquals(DriveAuthorizationResult.Authorized("token"), authorizer.authorizationResultFromIntent(Intent()))
        assertTrue(store.isConnected())
    }
}

private class FakeDriveConnectionStore(
    connected: Boolean,
) : DriveConnectionStore {
    private var connected = connected

    override fun isConnected(): Boolean = connected

    override fun setConnected(connected: Boolean) {
        this.connected = connected
    }
}

private class FakeDriveAuthorizer(
    private val accessToken: String = "token",
    private val authorizeResult: DriveAuthorizationResult = DriveAuthorizationResult.Authorized(accessToken),
    private val intentResult: DriveAuthorizationResult = authorizeResult,
    private val status: DriveAuthStatus = DriveAuthStatus.Connected,
    private val revokeError: Throwable? = null,
) : DriveAuthorizer {
    var accessTokenCalls = 0
    var revokeCalls = 0
    var statusCalls = 0

    override suspend fun accessToken(): String {
        accessTokenCalls += 1
        return accessToken
    }

    override suspend fun clearAccessToken(token: String) = Unit

    override suspend fun authorize(): DriveAuthorizationResult = authorizeResult

    override fun authorizationResultFromIntent(data: Intent?): DriveAuthorizationResult = intentResult

    override suspend fun revokeAccess() {
        revokeCalls += 1
        revokeError?.let { throw it }
    }

    override suspend fun status(): DriveAuthStatus {
        statusCalls += 1
        return status
    }
}
