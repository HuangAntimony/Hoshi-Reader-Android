package moe.antimony.hoshi.features.reader

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.io.path.createTempDirectory

class ReaderFontDownloaderTest {
    @Test
    fun verifiedDownloadIsAtomicallyInstalledAndReportsProgress() = runBlocking {
        val root = createTempDirectory().toFile()
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val remote = remote(bytes)
        val progress = mutableListOf<ReaderFontDownloadProgress>()
        val downloader = ReaderFontDownloader(root, FakeReaderFontRemoteDataSource(bytes))

        val installed = downloader.download(remote, progress::add)

        assertArrayEquals(bytes, installed.readBytes())
        assertEquals(File(root, remote.fileName).canonicalFile, installed.canonicalFile)
        assertEquals(ReaderFontDownloadProgress(bytes.size.toLong(), bytes.size.toLong()), progress.last())
        assertFalse(root.hasPartFile())
    }

    @Test
    fun hashMismatchDeletesPartAndLeavesExistingFontUntouched() = runBlocking {
        val root = createTempDirectory().toFile()
        val bytes = byteArrayOf(1, 2, 3)
        val remote = remote(bytes).copy(sha256 = "0".repeat(64))
        val existing = File(root, remote.fileName).apply { writeBytes(byteArrayOf(9)) }
        val downloader = ReaderFontDownloader(root, FakeReaderFontRemoteDataSource(bytes))

        assertThrows(ReaderFontDownloadException::class.java) {
            runBlocking { downloader.download(remote) }
        }

        assertArrayEquals(byteArrayOf(9), existing.readBytes())
        assertFalse(root.hasPartFile())
    }

    @Test
    fun cancelledDownloadDeletesPartFile() {
        val root = createTempDirectory().toFile()
        val bytes = byteArrayOf(1, 2, 3)
        val remote = remote(bytes)
        val downloader = ReaderFontDownloader(
            root,
            object : ReaderFontRemoteDataSource {
                override suspend fun open(remoteFile: ReaderRemoteFontFile): ReaderFontRemoteResponse {
                    throw CancellationException("cancel")
                }
            },
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { downloader.download(remote) }
        }
        assertFalse(root.hasPartFile())
    }

    @Test
    fun installedCheckRejectsCorruptExistingFile() {
        val root = createTempDirectory().toFile()
        val bytes = byteArrayOf(1, 2, 3)
        val remote = remote(bytes)
        val downloader = ReaderFontDownloader(root, FakeReaderFontRemoteDataSource(bytes))

        File(root, remote.fileName).writeBytes(byteArrayOf(1, 2, 4))

        assertFalse(downloader.isInstalled(remote))
        File(root, remote.fileName).writeBytes(bytes)
        assertTrue(downloader.isInstalled(remote))
    }

    @Test
    fun factoryReusesDownloaderForSameManagedDirectory() {
        val root = createTempDirectory().toFile()
        val factory = ReaderFontDownloaderFactory(HttpReaderFontRemoteDataSource(), Dispatchers.Unconfined)

        assertSame(factory.create(root), factory.create(root))
    }

    @Test
    fun repeatedRequestReusesVerifiedInstalledFileWithoutOpeningNetworkAgain() = runBlocking {
        val root = createTempDirectory().toFile()
        val bytes = byteArrayOf(1, 2, 3)
        val opens = AtomicInteger(0)
        val downloader = ReaderFontDownloader(
            root,
            object : ReaderFontRemoteDataSource {
                override suspend fun open(remoteFile: ReaderRemoteFontFile): ReaderFontRemoteResponse {
                    opens.incrementAndGet()
                    return ReaderFontRemoteResponse(200, bytes.size.toLong(), ByteArrayInputStream(bytes))
                }
            },
        )

        downloader.download(remote(bytes))
        downloader.download(remote(bytes))

        assertEquals(1, opens.get())
    }

    @Test
    fun cancellationInterruptsBlockedReadClosesResponseAndDeletesOwnedPart() = runBlocking {
        val root = createTempDirectory().toFile()
        val bytes = byteArrayOf(1)
        val remote = remote(bytes)
        val readStarted = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val downloader = ReaderFontDownloader(
            root,
            object : ReaderFontRemoteDataSource {
                override suspend fun open(remoteFile: ReaderRemoteFontFile): ReaderFontRemoteResponse =
                    ReaderFontRemoteResponse(
                        statusCode = 200,
                        contentLength = bytes.size.toLong(),
                        input = CloseOnlyBlockingInputStream(readStarted),
                        closeAction = { closed.set(true) },
                    )
            },
        )
        val job = launch(Dispatchers.Default) { downloader.download(remote) }
        readStarted.await()

        job.cancelAndJoin()

        assertTrue(closed.get())
        assertFalse(root.hasPartFile())
    }
}

private class CloseOnlyBlockingInputStream(
    private val readStarted: CountDownLatch,
) : InputStream() {
    private val closed = AtomicBoolean(false)

    override fun read(): Int {
        readStarted.countDown()
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!closed.get() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                // Deliberately ignore interrupts: only close() may unblock this stream.
            }
        }
        throw IOException(if (closed.get()) "closed" else "timed out")
    }

    override fun close() {
        closed.set(true)
    }
}

private class FakeReaderFontRemoteDataSource(
    private val bytes: ByteArray,
) : ReaderFontRemoteDataSource {
    override suspend fun open(remoteFile: ReaderRemoteFontFile): ReaderFontRemoteResponse =
        ReaderFontRemoteResponse(
            statusCode = 200,
            contentLength = bytes.size.toLong(),
            input = ByteArrayInputStream(bytes),
        )
}

private fun remote(bytes: ByteArray): ReaderRemoteFontFile = ReaderRemoteFontFile(
    path = "ofl/test/Test-Regular.ttf",
    fileName = "Test-Regular.ttf",
    expectedSize = bytes.size.toLong(),
    sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) },
)

private fun File.hasPartFile(): Boolean = listFiles().orEmpty().any { it.name.endsWith(".part") }
