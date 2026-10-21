package moe.antimony.hoshi.features.sync

import androidx.work.ListenableWorker.Result
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleDriveRetryTest {
    @Test fun exhausted403RateLimitsRemainRetryableAndRecoverOnTheNextRun() = runTest {
        for (reason in listOf("userRateLimitExceeded", "rateLimitExceeded")) {
            var requests = 0
            val client = GoogleDriveClient(
                object : DriveAccessTokenProvider { override suspend fun accessToken() = "token" },
                { SyncProvider.Gdrive }, {}, UnconfinedTestDispatcher(testScheduler),
            ) { address ->
                requests++
                response(address, if (requests <= 5) 403 else 200,
                    """{"error":{"message":"Try again later","errors":[{"reason":"$reason"}]}}""")
            }
            assertEquals(Result.retry(), backgroundSyncResult { client.request("files") })
            assertEquals(5, requests)
            assertEquals(Result.success(), backgroundSyncResult { client.request("files") })
            assertEquals(6, requests)
        }
    }

    @Test fun permissionErrorsDoNotRetryAndPostServerErrorsAreNotReplayedImmediately() = runTest {
        for ((status, method, expected) in listOf(Triple(403, "GET", Result.failure()), Triple(503, "POST", Result.retry()))) {
            var requests = 0
            val client = GoogleDriveClient(
                object : DriveAccessTokenProvider { override suspend fun accessToken() = "token" },
                { SyncProvider.Gdrive }, {}, UnconfinedTestDispatcher(testScheduler),
            ) { address ->
                requests++
                response(address, status, """{"error":{"message":"Permission denied","errors":[{"reason":"insufficientFilePermissions"}]}}""")
            }
            assertEquals(expected, backgroundSyncResult { client.request("files", method = method, body = "{}".toByteArray()) })
            assertEquals(1, requests)
        }
    }

    private fun response(address: String, status: Int, body: String) = object : HttpURLConnection(URL(address)) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getErrorStream() = body.byteInputStream()
        override fun getInputStream() = "{}".byteInputStream()
        override fun getOutputStream() = ByteArrayOutputStream()
    }
}
