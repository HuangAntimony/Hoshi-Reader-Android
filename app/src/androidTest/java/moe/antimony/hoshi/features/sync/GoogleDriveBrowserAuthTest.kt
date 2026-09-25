package moe.antimony.hoshi.features.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
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
    private val requests = mutableListOf<Map<String, String>>()
    private var tokenResponse: suspend (Map<String, String>) -> BrowserTokenResponse = { token() }

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
    fun requestsDriveFileWithOfflineAccessStateAndS256Pkce() = runBlocking {
        val auth = authorizer()
        val request = auth.authorizationRequest()
        val url = request.toUri()
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth", url.buildUpon().clearQuery().build().toString())
        assertEquals(ClientId, request.clientId)
        assertEquals("com.googleusercontent.apps.123-test:/oauth2callback", request.redirectUri.toString())
        assertEquals("code", url.getQueryParameter("response_type"))
        assertEquals(GoogleDriveAuth.DriveFileScope, url.getQueryParameter("scope"))
        assertEquals("offline", url.getQueryParameter("access_type"))
        assertEquals("consent select_account", url.getQueryParameter("prompt"))
        assertEquals("S256", url.getQueryParameter("code_challenge_method"))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(request.codeVerifier.toByteArray(Charsets.US_ASCII)),
        )
        assertEquals(challenge, url.getQueryParameter("code_challenge"))
        assertTrue(request.state.isNotEmpty())
        val next = auth.authorizationRequest()
        assertNotEquals(request.state, next.state)
        assertNotEquals(request.codeVerifier, next.codeVerifier)
    }

    @Test
    fun exchangesTheCodeWithItsVerifierAndPersistsLoginAcrossRestart() = runBlocking {
        val auth = authorizer()
        val request = auth.authorizationRequest()
        auth.accept(callback(request, "authorization-code"))
        val exchange = requests.single()
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals("authorization-code", exchange["code"])
        assertEquals(request.codeVerifier, exchange["code_verifier"])
        assertEquals(request.redirectUri, exchange["redirect_uri"])
        assertFalse(exchange.containsKey("client_secret"))
        job.cancelAndJoin()
        openStore()
        val restarted = authorizer()
        assertEquals(DriveAuthStatus.Connected, restarted.status())
        assertEquals("access", restarted.accessToken())
        assertEquals(1, requests.size)
    }

    @Test
    fun refreshesExpiredTokensAndKeepsTheRefreshTokenWhenOmitted() = runBlocking {
        tokenResponse = { token(expiresIn = 0) }
        val auth = authorizer()
        connect(auth)
        tokenResponse = { token(access = "refreshed", refresh = null) }
        assertEquals("refreshed", auth.accessToken())
        assertEquals("refresh_token", requests.last()["grant_type"])
        assertEquals("refresh", requests.last()["refresh_token"])
        assertFalse(requests.last().containsKey("client_secret"))
        auth.clearAccessToken("refreshed")
        assertEquals("refreshed", auth.accessToken())
        assertEquals("refresh", requests.last()["refresh_token"])
    }

    @Test
    fun persistsInvalidationAndIgnoresAnOlderRejectedToken() = runBlocking {
        val auth = authorizer()
        connect(auth)
        auth.clearAccessToken("access")
        job.cancelAndJoin()
        openStore()
        val restarted = authorizer()
        tokenResponse = { token(access = "refreshed", refresh = null) }
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
        tokenResponse = { throw BrowserTokenException("invalid_grant") }
        assertTrue(runCatching { auth.accessToken() }.exceptionOrNull() is DriveAuthorizationRequiredException)
        assertEquals(DriveAuthStatus.NotConnected, authorizer().status())
    }

    @Test
    fun networkFailureKeepsTheRefreshTokenForTheNextAttempt() = runBlocking {
        val auth = authorizer()
        connect(auth)
        auth.clearAccessToken("access")
        tokenResponse = { throw IOException() }
        assertTrue(runCatching { auth.accessToken() }.exceptionOrNull() is IOException)
        assertEquals(DriveAuthStatus.Connected, auth.status())
        tokenResponse = { token(access = "refreshed", refresh = null) }
        assertEquals("refreshed", auth.accessToken())
        assertEquals("refresh", requests.last()["refresh_token"])
    }

    @Test
    fun canceledDeniedAndFailedAuthorizationKeepTheExistingLogin() = runBlocking {
        val auth = authorizer()
        connect(auth)
        assertTrue(runCatching { auth.accept(null) }.exceptionOrNull() is DriveAuthorizationRequiredException)
        assertTrue(runCatching {
            val request = auth.authorizationRequest()
            auth.accept(Intent().setData(Uri.parse(request.redirectUri).buildUpon()
                .appendQueryParameter("state", request.state).appendQueryParameter("error", "access_denied").build()))
        }.exceptionOrNull() is DriveAuthorizationRequiredException)
        tokenResponse = { throw IOException() }
        assertTrue(runCatching { connect(auth) }.exceptionOrNull() is IOException)
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
            token(access = "refreshed")
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

    @Test
    fun validatesStateRedirectAndCodeBeforeSendingTokens() = runBlocking {
        val auth = authorizer()
        val invalidResponses: List<(BrowserAuthorizationRequest) -> Uri> = listOf(
            { Uri.parse("${it.redirectUri}?code=code") },
            { Uri.parse("${it.redirectUri}?code=code&state=wrong") },
            { Uri.parse("${it.redirectUri}?code=code&state=${it.state}&state=${it.state}") },
            { Uri.parse("${it.redirectUri}?code=one&code=two&state=${it.state}") },
            { Uri.parse("${it.redirectUri}?state=${it.state}") },
            { Uri.parse("${it.redirectUri}?code=&state=${it.state}") },
            { Uri.parse("${it.redirectUri}?code=code&state=${it.state}#fragment") },
            { Uri.parse("wrong:/oauth2callback?code=code&state=${it.state}") },
            { Uri.parse("${it.redirectUri}/wrong?code=code&state=${it.state}") },
            { Uri.parse("${it.redirectUri}?code=code&state=${it.state}&error=access_denied") },
        )
        for (response in invalidResponses) {
            val request = auth.authorizationRequest()
            assertTrue(runCatching { auth.accept(Intent().setData(response(request))) }.isFailure)
        }
        assertTrue(requests.isEmpty())
        assertEquals(DriveAuthStatus.NotConnected, auth.status())
    }

    @Test
    fun consumesTheRequestOnceAndRejectsUnsolicitedCallbacks() = runBlocking {
        val auth = authorizer()
        val response = callback(auth.authorizationRequest())
        auth.accept(response)
        assertTrue(runCatching { auth.accept(response) }.isFailure)
        assertEquals(1, requests.size)
        val canceled = callback(auth.authorizationRequest())
        assertTrue(runCatching { auth.accept(null) }.isFailure)
        assertTrue(runCatching { auth.accept(canceled) }.isFailure)
        assertEquals(1, requests.size)
    }

    @Test
    fun restoresPendingPkceAfterProcessRestart() = runBlocking {
        val request = authorizer().authorizationRequest()
        job.cancelAndJoin()
        openStore()
        val restarted = authorizer()
        restarted.accept(callback(request))
        assertEquals(request.codeVerifier, requests.single()["code_verifier"])
        assertEquals(DriveAuthStatus.Connected, restarted.status())
    }

    @Test
    fun signOutAndClientChangesInvalidatePendingAuthorization() = runBlocking {
        val auth = authorizer()
        val response = callback(auth.authorizationRequest())
        auth.revokeAccess()
        assertTrue(runCatching { auth.accept(response) }.isFailure)
        val next = callback(auth.authorizationRequest())
        assertTrue(runCatching { authorizer("other-client").accept(next) }.isFailure)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun missingRefreshTokenDoesNotReplaceTheExistingLogin() = runBlocking {
        val auth = authorizer()
        connect(auth)
        tokenResponse = { token(access = "other", refresh = null) }
        assertTrue(runCatching { connect(auth) }.isFailure)
        assertEquals("access", auth.accessToken())
    }

    @Test
    fun readsAppAuthTokensAndPreservesForcedRefreshOnUpgrade() = runBlocking {
        dataStore.edit {
            it[stringPreferencesKey("state")] = """
                {"refreshToken":"legacy-refresh",
                 "lastAuthorizationResponse":{"request":{"clientId":"$ClientId"}},
                 "mLastTokenResponse":{"access_token":"legacy-access","expires_at":${System.currentTimeMillis() + 3_600_000}}}
            """.trimIndent()
        }
        val auth = authorizer()
        assertEquals(DriveAuthStatus.Connected, auth.status())
        assertEquals("legacy-access", auth.accessToken())
        assertTrue(requests.isEmpty())
        dataStore.edit { it[booleanPreferencesKey("needsTokenRefresh")] = true }
        tokenResponse = { token(access = "new-access", refresh = null) }
        assertEquals("new-access", auth.accessToken())
        assertEquals("legacy-refresh", requests.single()["refresh_token"])
        job.cancelAndJoin()
        openStore()
        val restarted = authorizer()
        restarted.clearAccessToken("new-access")
        assertEquals("new-access", restarted.accessToken())
        assertEquals("legacy-refresh", requests.last()["refresh_token"])
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
        auth.accept(callback(auth.authorizationRequest()))
    }

    private fun callback(request: BrowserAuthorizationRequest, code: String = "code"): Intent = Intent().setData(
        Uri.parse(request.redirectUri).buildUpon().appendQueryParameter("state", request.state)
            .appendQueryParameter("code", code).build(),
    )

    private fun token(
        access: String = "access",
        refresh: String? = "refresh",
        expiresIn: Long = 3600,
    ) = BrowserTokenResponse(access, expiresIn, refresh)

    companion object {
        private const val ClientId = "123-test.apps.googleusercontent.com"
    }
}
