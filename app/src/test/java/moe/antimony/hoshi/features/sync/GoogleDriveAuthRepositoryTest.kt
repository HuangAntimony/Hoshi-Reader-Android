package moe.antimony.hoshi.features.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveAuthRepositoryTest {
    @Test
    fun blankClientIdReportsMissingConfiguration() {
        val repository = GoogleDriveAuthRepository(
            clientIdProvider = { "   " },
            tokenProvider = FakeAccessTokenProvider(),
        )

        val result = repository.validateConfiguration()

        assertEquals(GoogleDriveAuthState.MissingClientId, result)
    }

    @Test
    fun invalidClientIdReportsMissingConfiguration() {
        val repository = GoogleDriveAuthRepository(
            clientIdProvider = { "not-a-google-client-id" },
            tokenProvider = FakeAccessTokenProvider(),
        )

        assertEquals(GoogleDriveAuthState.InvalidClientId, repository.validateConfiguration())
    }

    @Test
    fun configuredClientIdIsAccepted() {
        val repository = GoogleDriveAuthRepository(
            clientIdProvider = { "1234567890-abcdef.apps.googleusercontent.com" },
            tokenProvider = FakeAccessTokenProvider(),
        )

        assertEquals(GoogleDriveAuthState.Configured, repository.validateConfiguration())
    }

    @Test
    fun signedOutStateHasNoAccountOrToken() {
        val repository = GoogleDriveAuthRepository(
            clientIdProvider = { "1234567890-abcdef.apps.googleusercontent.com" },
            tokenProvider = FakeAccessTokenProvider(),
        )

        assertFalse(repository.isSignedIn())
        assertEquals(null, repository.accountEmail())
        assertEquals(null, repository.accessTokenOrNull())
    }

    @Test
    fun signedInStateExposesAccountAndToken() {
        val provider = FakeAccessTokenProvider(
            email = "reader@example.com",
            accessToken = "token",
        )
        val repository = GoogleDriveAuthRepository(
            clientIdProvider = { "1234567890-abcdef.apps.googleusercontent.com" },
            tokenProvider = provider,
        )

        assertTrue(repository.isSignedIn())
        assertEquals("reader@example.com", repository.accountEmail())
        assertEquals("token", repository.accessTokenOrNull())
    }
}

private class FakeAccessTokenProvider(
    private val email: String? = null,
    private val accessToken: String? = null,
) : GoogleDriveAccessTokenProvider {
    override fun accountEmail(): String? = email

    override fun accessTokenOrNull(): String? = accessToken
}
