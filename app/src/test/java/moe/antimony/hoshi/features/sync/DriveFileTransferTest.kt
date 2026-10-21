package moe.antimony.hoshi.features.sync

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.SocketPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DriveFileTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private val auth = object : DriveAccessTokenProvider { override suspend fun accessToken() = "test-token" }
    private val request = DriveUploadRequest("root", "book", 1, SyncFileType.epub, 100, "parent", "book.epub")
    private val completed = """{"id":"uploaded","name":"book.epub","mimeType":"application/epub+zip","createdTime":""}"""

    private fun transport(server: MockWebServer, directory: File) = GoogleDriveFileTransfer(
        auth, DriveUploadSessions(directory), Dispatchers.IO,
        OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build(),
        endpoint = server.url("/upload").toString(), checkInternet = {}, retryDelay = {},
    )

    @Test fun chunksUseConfirmedRangeAndOnlyReturnAfterFinalResponse() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Location", server.url("/session")))
            server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-524287"))
            server.enqueue(MockResponse().setBody(completed))
            val file = temporary.newFile().apply { writeBytes(ByteArray(1048576 + 123) { (it % 251).toByte() }) }
            val updates = mutableListOf<DriveTransferUpdate>()
            val result = transport(server, temporary.newFolder()).upload(file, request) { updates += it }
            assertEquals("uploaded", result.id)
            val init = server.takeRequest()
            assertTrue(init.path!!.contains("uploadType=resumable"))
            val first = server.takeRequest()
            assertEquals("bytes 0-1048575/1048699", first.getHeader("Content-Range"))
            assertEquals(1048576, first.bodySize)
            val last = server.takeRequest()
            assertEquals("bytes 524288-1048698/1048699", last.getHeader("Content-Range"))
            assertEquals(524411, last.bodySize)
            assertEquals(1048699L, updates.last().confirmedBytes)
            assertTrue(updates.any { it.deltaBytes > 0 })
        }
    }

    @Test fun newInstanceQueriesPersistedSessionAndReusesCompletedResultUntilAcknowledged() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val file = temporary.newFile().apply { writeBytes(ByteArray(32)) }
            server.enqueue(MockResponse().setHeader("Location", server.url("/session")))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"quota","errors":[{"reason":"storageQuotaExceeded"}]}}"""))
            assertTrue(runCatching { transport(server, directory).upload(file, request) {} }.exceptionOrNull() is GoogleDriveApiException)
            server.takeRequest(); server.takeRequest()
            server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-15"))
            server.enqueue(MockResponse().setBody(completed))
            assertEquals("uploaded", transport(server, directory).upload(file, request) {}.id)
            assertEquals("bytes */32", server.takeRequest().getHeader("Content-Range"))
            assertEquals("bytes 16-31/32", server.takeRequest().getHeader("Content-Range"))
            val recovered = transport(server, directory)
            assertEquals("uploaded", recovered.upload(file, request) {}.id)
            assertEquals(4, server.requestCount)
            recovered.acknowledge(request)
            server.enqueue(MockResponse().setHeader("Location", server.url("/new-session")))
            server.enqueue(MockResponse().setBody(completed))
            recovered.upload(file, request) {}
            assertTrue(server.takeRequest().path!!.contains("uploadType=resumable"))
        }
    }

    @Test fun interruptedFinalResponseQueriesCompletionInsteadOfReuploading() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Location", server.url("/secret-session")))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody(completed))
            val file = temporary.newFile().apply { writeText("payload") }
            assertEquals("uploaded", transport(server, temporary.newFolder()).upload(file, request) {}.id)
            server.takeRequest(); server.takeRequest()
            val probe = server.takeRequest()
            assertEquals("bytes */7", probe.getHeader("Content-Range"))
            assertEquals(0L, probe.bodySize)
        }
    }

    @Test fun expiredSessionRestartsAndMissingRangeResumesAtZero() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val file = temporary.newFile().apply { writeText("payload") }
            server.enqueue(MockResponse().setHeader("Location", server.url("/expired")))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setHeader("Location", server.url("/fresh")))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(308))
            server.enqueue(MockResponse().setBody(completed))
            transport(server, directory).upload(file, request) {}
            repeat(4) { server.takeRequest() }
            assertEquals("bytes */7", server.takeRequest().getHeader("Content-Range"))
            assertEquals("bytes 0-6/7", server.takeRequest().getHeader("Content-Range"))
        }
    }

    @Test fun failedSessionCannotLeakItsUrlOrTokenInDiagnostics() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val session = server.url("/private-session?upload_id=private-capability")
            server.enqueue(MockResponse().setHeader("Location", session))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"Denied test-token $session","errors":[{"reason":"quota"}]}}"""))
            val error = runCatching { transport(server, temporary.newFolder()).upload(temporary.newFile().apply { writeText("x") }, request) {} }.exceptionOrNull()!!
            val details = error.stackTraceToString()
            assertFalse(details, details.contains("test-token"))
            assertFalse(details, details.contains("private-capability"))
            assertTrue(error is GoogleDriveApiException)
            assertEquals(403, (error as GoogleDriveApiException).statusCode)
        }
    }

    @Test fun cancellationClosesStalledRequestAndKeepsSessionForNextInstance() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val file = temporary.newFile().apply { writeText("payload") }
            server.enqueue(MockResponse().setHeader("Location", server.url("/session")))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val transfer = async(Dispatchers.IO) { transport(server, directory).upload(file, request) {} }
            server.takeRequest(3, TimeUnit.SECONDS)!!
            server.takeRequest(3, TimeUnit.SECONDS)!!
            withTimeout(2000) { transfer.cancelAndJoin() }
            server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-2"))
            server.enqueue(MockResponse().setBody(completed))
            transport(server, directory).upload(file, request) {}
            assertEquals("bytes */7", server.takeRequest().getHeader("Content-Range"))
            assertEquals("bytes 3-6/7", server.takeRequest().getHeader("Content-Range"))
        }
    }

    @Test fun sourceChangesAndResetDiscardSavedCompletion() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val file = temporary.newFile().apply { writeText("first") }
            val transport = transport(server, directory)
            repeat(3) {
                server.enqueue(MockResponse().setHeader("Location", server.url("/session-$it")))
                server.enqueue(MockResponse().setBody(completed))
                transport.upload(file, request) {}
                assertTrue(server.takeRequest().path!!.contains("uploadType=resumable"))
                server.takeRequest()
                if (it == 0) file.writeText("changed-content") else transport.reset()
            }
        }
    }

    @Test fun authorizationRefreshKeepsSessionAndRetriesWithNewToken() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            var refreshes = 0
            val tokens = object : DriveAccessTokenProvider {
                override suspend fun accessToken() = if (refreshes == 0) "expired" else "fresh"
                override suspend fun clearAccessToken(token: String) { assertEquals("expired", token); refreshes++ }
            }
            val engine = GoogleDriveFileTransfer(tokens, DriveUploadSessions(temporary.newFolder()), Dispatchers.IO, OkHttpClient(),
                endpoint = server.url("/upload").toString(), checkInternet = {}, retryDelay = {})
            server.enqueue(MockResponse().setHeader("Location", server.url("/session")))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody(completed))
            engine.upload(temporary.newFile().apply { writeText("x") }, request) {}
            server.takeRequest()
            assertEquals("Bearer expired", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
            assertEquals(1, refreshes)
        }
    }

    @Test fun retryBudgetIsBoundedAndConfirmedProgressResetsIt() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Location", server.url("/session")))
            repeat(4) { server.enqueue(MockResponse().setResponseCode(429)) }
            server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-3"))
            repeat(5) { server.enqueue(MockResponse().setResponseCode(503)) }
            val error = runCatching { transport(server, temporary.newFolder()).upload(temporary.newFile().apply { writeText("payload") }, request) {} }.exceptionOrNull()
            assertTrue(error.toString(), error is GoogleDriveApiException)
            assertEquals(503, (error as GoogleDriveApiException).statusCode)
            assertEquals(11, server.requestCount)
        }
    }

    @Test fun readTimeoutEventuallyExitsWithoutLosingSession() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Location", server.url("/session")))
            repeat(5) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
            val directory = temporary.newFolder()
            val retries = mutableListOf<Int>()
            val engine = GoogleDriveFileTransfer(auth, DriveUploadSessions(directory), Dispatchers.IO,
                OkHttpClient.Builder().readTimeout(250, TimeUnit.MILLISECONDS).build(),
                endpoint = server.url("/upload").toString(), checkInternet = {}, retryDelay = { retries += it })
            val error = withTimeout(5000) { runCatching { engine.upload(temporary.newFile().apply { writeText("payload") }, request) {} }.exceptionOrNull() }
            assertTrue(error.toString(), error is GoogleDriveUnavailableException)
            assertEquals(listOf(0, 1, 2, 3), retries)
            assertTrue(File(directory, "sessions.json").isFile)
            assertTrue(server.requestCount >= 2)
        }
    }

    @Test fun pruningDeletedOrReplacedBooksNeverReusesOldSession() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val file = temporary.newFile().apply { writeText("payload") }
            val engine = transport(server, directory)
            repeat(2) {
                server.enqueue(MockResponse().setHeader("Location", server.url("/session-$it")))
                server.enqueue(MockResponse().setBody(completed))
                engine.upload(file, request) {}
                assertTrue(server.takeRequest().path!!.contains("uploadType=resumable"))
                server.takeRequest()
                engine.discardObsolete { false }
            }
        }
    }

    @Test fun cancellationJoinsResponseConsumerBeforeReturningToFileCleanup() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("payload"))
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val directory = temporary.newFolder()
            val file = temporary.newFile()
            val task = async(Dispatchers.IO) {
                transport(server, directory).download(server.url("/file").toString(), file, 7) {
                    if (it.deltaBytes > 0) { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
                }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            try {
                task.cancel()
                kotlinx.coroutines.delay(100)
                assertFalse("Cancellation must wait for the body consumer before callers clean up files", task.isCompleted)
            } finally {
                release.countDown()
                withTimeout(2000) { task.join() }
            }
        }
    }

    @Test fun downloadRetriesRateLimitsAndServerFailures() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("payload"))
            val destination = temporary.newFile()
            transport(server, temporary.newFolder()).download(server.url("/file").toString(), destination, 7) {}
            assertEquals("payload", destination.readText())
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun downloadTimeoutRemainsRetryableForBackgroundSync() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(5) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
            val retries = mutableListOf<Int>()
            val engine = GoogleDriveFileTransfer(auth, DriveUploadSessions(temporary.newFolder()), Dispatchers.IO,
                OkHttpClient.Builder().readTimeout(250, TimeUnit.MILLISECONDS).build(),
                endpoint = server.url("/upload").toString(), checkInternet = {}, retryDelay = { retries += it })
            val error = withTimeout(5000) {
                runCatching { engine.download(server.url("/file").toString(), temporary.newFile(), 7) {} }.exceptionOrNull()
            }
            assertTrue(error.toString(), error is GoogleDriveUnavailableException)
            assertTrue(error!!.isRetryableBackgroundSyncFailure())
            assertEquals(listOf(0, 1, 2, 3), retries)
        }
    }

    @Test fun downloadReportsBytesAndWritesOnlyToRequestedTemporaryFile() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("download-data"))
            val destination = temporary.newFile()
            val updates = mutableListOf<DriveTransferUpdate>()
            transport(server, temporary.newFolder()).download(server.url("/file").toString(), destination, 13) { updates += it }
            assertEquals("download-data", destination.readText())
            assertEquals(13L, updates.sumOf { it.deltaBytes })
            assertEquals(13L, updates.last().bytes)
        }
    }
}
