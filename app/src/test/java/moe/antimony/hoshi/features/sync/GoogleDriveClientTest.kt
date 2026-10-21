package moe.antimony.hoshi.features.sync

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleDriveClientTest {
    private class Connection(private val respond: () -> Int) : HttpURLConnection(URL("https://fixture.invalid")) {
        val body = ByteArrayOutputStream()
        var disconnected = false
        override fun setRequestMethod(value: String) { method = value }
        override fun getErrorStream() = "{}".byteInputStream()
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getOutputStream() = body
        override fun getResponseCode() = respond()
        override fun getInputStream() = "{}".byteInputStream()
    }

    @Test fun retriesMetadataTimeoutAndReleasesFailedConnections() = runTest {
        val connections = mutableListOf<Connection>()
        val client = GoogleDriveClient(object : DriveAccessTokenProvider {
            override suspend fun accessToken() = "token"
        }, { SyncProvider.Gdrive }, {}, UnconfinedTestDispatcher(testScheduler), {
            Connection { if (connections.size < 3) throw SocketTimeoutException("timeout") else 200 }
                .also(connections::add)
        })
        assertEquals("{}", client.request("files").decodeToString())
        assertEquals(3, connections.size)
        assertTrue(connections.all { it.disconnected })
    }

    @Test fun boundsRetriesAndDoesNotReplayAmbiguousCreatesOrTtuRequests() = runTest {
        for ((provider, method, expected) in listOf(Triple(SyncProvider.Gdrive, "GET", 5), Triple(SyncProvider.Gdrive, "POST", 1), Triple(SyncProvider.Ttu, "GET", 1))) {
            var attempts = 0
            val original = SocketTimeoutException("timeout")
            val client = GoogleDriveClient(object : DriveAccessTokenProvider {
                override suspend fun accessToken() = "token"
            }, { provider }, {}, UnconfinedTestDispatcher(testScheduler), {
                Connection { attempts++; throw original }
            })
            assertSame(original, runCatching { client.request("files", method = method) }.exceptionOrNull()?.cause)
            assertEquals("$provider $method", expected, attempts)
        }
    }

    @Test fun stopAndResumeDuringBackoffCannotReviveOldRequest() = runTest {
        var attempts = 0
        val client = GoogleDriveClient(object : DriveAccessTokenProvider {
            override suspend fun accessToken() = "token"
        }, { SyncProvider.Gdrive }, {}, UnconfinedTestDispatcher(testScheduler), {
            Connection { attempts++; throw SocketTimeoutException("timeout") }
        })
        val request = async { runCatching { client.request("files") } }
        runCurrent()
        assertFalse(request.isCompleted)
        client.stop()
        client.resume()
        assertTrue(request.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(1, attempts)
    }

    @Test fun updateRetriesSameBodyAndRefreshesAuthentication() = runTest {
        val connections = mutableListOf<Connection>()
        var refreshed = false
        val client = GoogleDriveClient(object : DriveAccessTokenProvider {
            override suspend fun accessToken() = if (refreshed) "new" else "old"
            override suspend fun clearAccessToken(token: String) { refreshed = true }
        }, { SyncProvider.Gdrive }, {}, UnconfinedTestDispatcher(testScheduler), {
            Connection { when (connections.size) { 1 -> throw SocketTimeoutException("timeout"); 2 -> 401; else -> 200 } }
                .also(connections::add)
        })
        client.request("files/id", method = "PATCH", body = "metadata".toByteArray())
        assertEquals(3, connections.size)
        assertTrue(connections.all { it.body.toString() == "metadata" })
        assertTrue(refreshed)
    }
}
