package moe.antimony.hoshi.features.reader

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.IoDispatcher

data class ReaderFontDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
)

class ReaderFontDownloadException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class ReaderFontRemoteResponse(
    val statusCode: Int,
    val contentLength: Long,
    val input: InputStream,
    private val closeAction: () -> Unit = {},
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching(input::close)
            closeAction()
        }
    }
}

interface ReaderFontRemoteDataSource {
    suspend fun open(remoteFile: ReaderRemoteFontFile): ReaderFontRemoteResponse
}

@Singleton
class HttpReaderFontRemoteDataSource @Inject constructor() : ReaderFontRemoteDataSource {
    override suspend fun open(remoteFile: ReaderRemoteFontFile): ReaderFontRemoteResponse {
        val url = remoteFile.url
        if (!url.startsWith(ReaderRemoteFontFile.RAW_GOOGLE_FONTS_ROOT + "/")) {
            throw ReaderFontDownloadException("Font URL is not in the approved catalog.")
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.requestMethod = "GET"
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                connection.connect()
                val status = connection.responseCode
                val input = if (status in 200..299) connection.inputStream else connection.errorStream
                continuation.resumeWith(
                    Result.success(
                        ReaderFontRemoteResponse(
                            statusCode = status,
                            contentLength = connection.contentLengthLong,
                            input = input ?: ByteArrayInputStream(ByteArray(0)),
                            closeAction = connection::disconnect,
                        ),
                    ),
                )
            } catch (error: Exception) {
                connection.disconnect()
                continuation.resumeWith(
                    Result.failure(ReaderFontDownloadException("Unable to connect to font source.", error)),
                )
            }
        }
    }
}

class ReaderFontDownloader(
    private val destinationDirectory: File,
    private val remoteDataSource: ReaderFontRemoteDataSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val downloadMutex = Mutex()

    suspend fun download(
        remoteFile: ReaderRemoteFontFile,
        onProgress: (ReaderFontDownloadProgress) -> Unit = {},
    ): File = downloadMutex.withLock {
        try {
            withContext(ioDispatcher) {
                validateCatalogEntry(remoteFile)
                destinationDirectory.mkdirs()
                val destination = destinationFile(remoteFile)
                if (isInstalled(remoteFile)) return@withContext destination
                val part = File(destinationDirectory, "${remoteFile.fileName}.${UUID.randomUUID()}.part")
                try {
                    remoteDataSource.open(remoteFile).useCancellable { response ->
                        if (response.statusCode !in 200..299) {
                            throw ReaderFontDownloadException("Font server returned HTTP ${response.statusCode}.")
                        }
                        if (response.contentLength >= 0 && response.contentLength != remoteFile.expectedSize) {
                            throw ReaderFontDownloadException("Font download size does not match the catalog.")
                        }
                        copyVerified(response.input, part, remoteFile, onProgress)
                    }
                    Files.move(
                        part.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    destination
                } finally {
                    part.delete()
                }
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            throw error
        }
    }

    fun isInstalled(remoteFile: ReaderRemoteFontFile): Boolean {
        val destination = runCatching { destinationFile(remoteFile) }.getOrNull() ?: return false
        return destination.isFile &&
            destination.length() == remoteFile.expectedSize &&
            destination.sha256File().equals(remoteFile.sha256, ignoreCase = true)
    }

    private fun copyVerified(
        input: InputStream,
        part: File,
        remoteFile: ReaderRemoteFontFile,
        onProgress: (ReaderFontDownloadProgress) -> Unit,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileOutputStream(part).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw CancellationException("Font download cancelled.")
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > remoteFile.expectedSize) {
                    throw ReaderFontDownloadException("Font download exceeds the catalog size.")
                }
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
                onProgress(ReaderFontDownloadProgress(total, remoteFile.expectedSize))
            }
            output.fd.sync()
        }
        if (total != remoteFile.expectedSize) {
            throw ReaderFontDownloadException("Font download is incomplete.")
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(remoteFile.sha256, ignoreCase = true)) {
            throw ReaderFontDownloadException("Font download failed integrity verification.")
        }
    }

    private fun destinationFile(remoteFile: ReaderRemoteFontFile): File {
        validateCatalogEntry(remoteFile)
        val root = destinationDirectory.canonicalFile
        val file = File(root, remoteFile.fileName).canonicalFile
        if (file.parentFile != root) throw ReaderFontDownloadException("Invalid font destination.")
        return file
    }

    private fun validateCatalogEntry(remoteFile: ReaderRemoteFontFile) {
        if (remoteFile.fileName.isBlank() || '/' in remoteFile.fileName || '\\' in remoteFile.fileName ||
            remoteFile.expectedSize <= 0 || !remoteFile.sha256.matches(Regex("[0-9a-fA-F]{64}"))
        ) {
            throw ReaderFontDownloadException("Invalid font catalog entry.")
        }
    }
}

internal suspend fun <T> ReaderFontRemoteResponse.useCancellable(
    block: (ReaderFontRemoteResponse) -> T,
): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { close() }
    try {
        continuation.resumeWith(Result.success(use(block)))
    } catch (error: Throwable) {
        continuation.resumeWith(Result.failure(error))
    }
}

@Singleton
class ReaderFontDownloaderFactory @Inject constructor(
    private val remoteDataSource: ReaderFontRemoteDataSource,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val downloaders = ConcurrentHashMap<String, ReaderFontDownloader>()

    fun create(destinationDirectory: File): ReaderFontDownloader =
        downloaders.computeIfAbsent(destinationDirectory.canonicalPath) {
            ReaderFontDownloader(destinationDirectory.canonicalFile, remoteDataSource, ioDispatcher)
        }
}

internal fun File.sha256File(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
