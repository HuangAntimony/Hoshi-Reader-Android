package moe.antimony.hoshi.features.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleDriveBrowserAuthTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = File(context.cacheDir, "browser-auth-${UUID.randomUUID()}.preferences_pb")
    private lateinit var job: Job
    private lateinit var dataStore: DataStore<Preferences>
    private val requests = mutableListOf<TokenRequest>()
    private var tokenResponse: suspend (TokenRequest) -> TokenResponse = { token(it) }

    @Before
    fun setUp() {
        openStore()
    }

    @After
    fun tearDown() = runBlocking {
        job.cancelAndJoin()
        file.delete()
        Unit
    }

    @Test
    fun requestsDriveFileWithOfflineAccessStateAndS256Pkce() {
        val auth = authorizer()
        val request = auth.authorizationRequest()
        val url = request.toUri()
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth", request.configuration.authorizationEndpoint.toString())
        assertEquals(ClientId, request.clientId)
        assertEquals("com.googleusercontent.apps.123-test:/oauth2callback", request.redirectUri.toString())
        assertEquals("code", url.getQueryParameter("response_type"))
        assertEquals(GoogleDriveAuth.DriveFileScope, url.getQueryParameter("scope"))
        assertEquals("offline", url.getQueryParameter("access_type"))
        assertEquals("consent select_account", url.getQueryParameter("prompt"))
        assertEquals("S256", url.getQueryParameter("code_challenge_method"))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(request.codeVerifier!!.toByteArray(Charsets.US_ASCII)),
        )
        assertEquals(challenge, url.getQueryParameter("code_challenge"))
        assertTrue(request.state!!.isNotEmpty())
        val next = auth.authorizationRequest()
        assertNotEquals(request.state, next.state)
        assertNotEquals(request.codeVerifier, next.codeVerifier)
    }

    @Test
    fun exchangesTheCodeWithItsVerifierAndPersistsLoginAcrossRestart() = runBlocking {
        val auth = authorizer()
        val request = auth.authorizationRequest()
        auth.accept(AuthorizationResponse.Builder(request).setAuthorizationCode("authorization-code").build().toIntent())
        val exchange = requests.single()
        assertEquals("authorization_code", exchange.grantType)
        assertEquals("authorization-code", exchange.authorizationCode)
        assertEquals(request.codeVerifier, exchange.codeVerifier)
        assertEquals(request.redirectUri, exchange.redirectUri)
        assertFalse(exchange.requestParameters.containsKey("client_secret"))
        job.cancelAndJoin()
        openStore()
        val restarted = authorizer()
        assertEquals(DriveAuthStatus.Connected, restarted.status())
        assertEquals("access", restarted.accessToken())
        assertEquals(1, requests.size)
    }

    @Test
    fun refreshesExpiredTokensAndKeepsTheRefreshTokenWhenOmitted() = runBlocking {
        tokenResponse = { token(it, expiresAt = 0) }
        val auth = authorizer()
        connect(auth)
        tokenResponse = { token(it, access = "refreshed", refresh = null) }
        assertEquals("refreshed", auth.accessToken())
        assertEquals("refresh_token", requests.last().grantType)
        assertEquals("refresh", requests.last().refreshToken)
        assertFalse(requests.last().requestParameters.containsKey("client_secret"))
        auth.clearAccessToken("refreshed")
        assertEquals("refreshed", auth.accessToken())
        assertEquals("refresh", requests.last().refreshToken)
    }

    @Test
    fun persistsInvalidationAndIgnoresAnOlderRejectedToken() = runBlocking {
        val auth = authorizer()
        connect(auth)
        auth.clearAccessToken("access")
        job.cancelAndJoin()
        openStore()
        val restarted = authorizer()
        tokenResponse = { token(it, access = "refreshed", refresh = null) }
        assertEquals("refreshed", restarted.accessToken())
        restarted.clearAccessToken("access")
        assertEquals("refreshed", restarted.accessToken())
        assertEquals(2, requests.size)
    }

    @Test
    fun rejectedRefreshRequiresSignInAgain() = runBlocking {
        val auth = authorizer()
        connect(auth)
        auth.clearAccessToken("access")
        tokenResponse = { throw AuthorizationException.TokenRequestErrors.INVALID_GRANT }
        assertTrue(runCatching { auth.accessToken() }.exceptionOrNull() is DriveAuthorizationRequiredException)
        assertEquals(DriveAuthStatus.NotConnected, authorizer().status())
    }

    @Test
    fun networkFailureKeepsTheRefreshTokenForTheNextAttempt() = runBlocking {
        val auth = authorizer()
        connect(auth)
        auth.clearAccessToken("access")
        tokenResponse = { throw AuthorizationException.GeneralErrors.NETWORK_ERROR }
        assertTrue(runCatching { auth.accessToken() }.exceptionOrNull() is AuthorizationException)
        assertEquals(DriveAuthStatus.Connected, auth.status())
        tokenResponse = { token(it, access = "refreshed", refresh = null) }
        assertEquals("refreshed", auth.accessToken())
        assertEquals("refresh", requests.last().refreshToken)
    }

    @Test
    fun canceledDeniedAndFailedAuthorizationKeepTheExistingLogin() = runBlocking {
        val auth = authorizer()
        connect(auth)
        assertTrue(runCatching { auth.accept(null) }.exceptionOrNull() is AuthorizationException)
        assertTrue(runCatching {
            auth.accept(AuthorizationException.AuthorizationRequestErrors.ACCESS_DENIED.toIntent())
        }.exceptionOrNull() is AuthorizationException)
        tokenResponse = { throw AuthorizationException.GeneralErrors.NETWORK_ERROR }
        assertTrue(runCatching { connect(auth) }.exceptionOrNull() is AuthorizationException)
        assertEquals(DriveAuthStatus.Connected, auth.status())
        assertEquals("access", auth.accessToken())
    }

    @Test
    fun missingConfigurationAndDifferentClientDoNotReuseTokens() = runBlocking {
        connect(authorizer())
        assertEquals(DriveAuthStatus.MissingConfiguration, authorizer("").status())
        assertTrue(runCatching { authorizer("").authorizationRequest() }.isFailure)
        val different = authorizer("123-other.apps.googleusercontent.com")
        assertEquals(DriveAuthStatus.NotConnected, different.status())
        assertTrue(runCatching { different.accessToken() }.exceptionOrNull() is DriveAuthorizationRequiredException)
    }

    @Test
    fun signOutWaitsForRefreshAndDoesNotRestoreTheLogin() = runBlocking {
        val auth = authorizer()
        connect(auth)
        auth.clearAccessToken("access")
        val finishRefresh = CompletableDeferred<Unit>()
        val refreshStarted = CompletableDeferred<Unit>()
        tokenResponse = {
            refreshStarted.complete(Unit)
            finishRefresh.await()
            token(it, access = "refreshed")
        }
        val refresh = async { auth.accessToken() }
        refreshStarted.await()
        val signOut = launch(start = CoroutineStart.UNDISPATCHED) { auth.revokeAccess() }
        assertFalse(signOut.isCompleted)
        finishRefresh.complete(Unit)
        refresh.await()
        signOut.join()
        assertEquals(DriveAuthStatus.NotConnected, authorizer().status())
        assertTrue(runCatching { auth.accessToken() }.exceptionOrNull() is DriveAuthorizationRequiredException)
        assertEquals(2, requests.size)
    }

    private fun openStore() {
        job = Job()
        dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
    }

    private fun authorizer(clientId: String = ClientId) = GoogleDriveBrowserAuth(context, dataStore, clientId) { request ->
        requests += request
        tokenResponse(request)
    }

    private suspend fun connect(auth: GoogleDriveBrowserAuth) {
        auth.accept(AuthorizationResponse.Builder(auth.authorizationRequest()).setAuthorizationCode("code").build().toIntent())
    }

    private fun token(
        request: TokenRequest,
        access: String = "access",
        refresh: String? = "refresh",
        expiresAt: Long = System.currentTimeMillis() + 3_600_000,
    ): TokenResponse = TokenResponse.Builder(request)
        .setTokenType("Bearer")
        .setAccessToken(access)
        .setRefreshToken(refresh)
        .setAccessTokenExpirationTime(expiresAt)
        .build()

    companion object {
        private const val ClientId = "123-test.apps.googleusercontent.com"
    }
}
