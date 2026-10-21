package moe.antimony.hoshi.features.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in local fixture only. No production transport, credentials, books or settings. */
@RunWith(AndroidJUnit4::class)
class DriveTransferDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root get() = File(context.cacheDir, "drive-transfer-restart-fixture")
    private val source get() = File(root, "generated.epub")
    private val request = DriveUploadRequest("fixture-root", "fixture-book", 1, SyncFileType.epub, 1, "fixture-parent", "generated.epub")
    private fun endpoint(): String {
        val url = InstrumentationRegistry.getArguments().getString("driveFixtureUrl").orEmpty()
        assumeTrue("Requires an explicitly supplied loopback fixture", url.startsWith("http://127.0.0.1:"))
        return url
    }
    private fun engine(url: String) = GoogleDriveFileTransfer(
        object : DriveAccessTokenProvider { override suspend fun accessToken() = "fixture-token" },
        DriveUploadSessions(File(root, "sessions")), Dispatchers.IO,
        OkHttpClient.Builder().build(), endpoint = "$url/upload", checkInternet = {}, retryDelay = {},
    )

    @Test fun prepareInterruptedUpload() = runBlocking {
        val url = endpoint()
        root.mkdirs()
        engine(url).reset()
        source.writeBytes(ByteArray(2 * 1024 * 1024 + 17) { (it % 251).toByte() })
        val confirmed = CompletableDeferred<Unit>()
        val task = async(Dispatchers.IO) {
            engine(url).upload(source, request) { if (it.confirmedBytes >= 1024 * 1024 && it.phase == SyncTransferPhase.Retrying) confirmed.complete(Unit) }
        }
        withTimeout(30_000) { confirmed.await() }
        withTimeout(5_000) { task.cancelAndJoin() }
        assertTrue(File(root, "sessions/sessions.json").isFile)
        File(root, "previous-pid").writeText(android.os.Process.myPid().toString())
    }

    @Test fun resumeAfterProcessRestartAndDownload() = runBlocking {
        val url = endpoint()
        assertTrue(source.isFile)
        assertNotEquals(File(root, "previous-pid").readText(), android.os.Process.myPid().toString())
        val engine = engine(url)
        val result = withTimeout(30_000) { engine.upload(source, request) {} }
        assertEquals("fixture-upload", result.id)
        val downloaded = File(root, "download.tmp")
        var received = 0L
        var samples = 0
        engine.download("$url/download", downloaded, source.length()) { update -> received += update.deltaBytes; samples++ }
        assertEquals(source.length(), received)
        assertTrue(samples > 2)
        fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        assertArrayEquals(hash(source), hash(downloaded))
        engine.acknowledge(request)
        // Clean up only this test's generated files after both instrumentation processes finish.
        root.deleteRecursively()
        Unit
    }
}
