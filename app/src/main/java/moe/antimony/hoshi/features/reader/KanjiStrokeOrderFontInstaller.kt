package moe.antimony.hoshi.features.reader

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.IoDispatcher

internal data class KanjiStrokeOrderFontSpec(
    val url: String,
    val fileName: String,
    val expectedSize: Long,
    val sha256: String,
)

internal interface KanjiStrokeOrderFontRemoteDataSource {
    suspend fun open(url: String): ReaderFontRemoteResponse
}

@Singleton
internal class HttpKanjiStrokeOrderFontRemoteDataSource @Inject constructor() :
    KanjiStrokeOrderFontRemoteDataSource {
    override suspend fun open(url: String): ReaderFontRemoteResponse {
        if (url != KanjiStrokeOrderFontInstaller.defaultSpec.url) {
            throw ReaderFontDownloadException("Font URL is not approved.")
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.requestMethod = "GET"
        return try {
            connection.connect()
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            ReaderFontRemoteResponse(
                statusCode = status,
                contentLength = connection.contentLengthLong,
                input = input ?: ByteArrayInputStream(ByteArray(0)),
                closeAction = connection::disconnect,
            )
        } catch (error: Exception) {
            connection.disconnect()
            throw ReaderFontDownloadException("Unable to connect to font source.", error)
        }
    }
}

@Singleton
internal class KanjiStrokeOrderFontInstaller internal constructor(
    private val fontManager: ReaderFontManager,
    private val remoteDataSource: KanjiStrokeOrderFontRemoteDataSource,
    private val ioDispatcher: CoroutineDispatcher,
    private val spec: KanjiStrokeOrderFontSpec,
) {
    @Inject
    constructor(
        fontManager: ReaderFontManager,
        remoteDataSource: HttpKanjiStrokeOrderFontRemoteDataSource,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(fontManager, remoteDataSource, ioDispatcher, defaultSpec)

    private val installMutex = Mutex()

    fun isInstalled(): Boolean =
        fontManager.fontFamilies().any { family ->
            family.source == ReaderFontSource.USER && family.displayName == FontFamilyName
        }

    suspend fun install() = installMutex.withLock {
        if (isInstalled()) return@withLock
        withContext(ioDispatcher) {
            val downloadDirectory = File(fontManager.managedFontsDirectory(), DownloadDirectoryName)
            val downloadedFont = File(downloadDirectory, spec.fileName)
            try {
                downloadDirectory.mkdirs()
                remoteDataSource.open(spec.url).useCancellable { response ->
                    if (response.statusCode !in 200..299) {
                        throw ReaderFontDownloadException("Font server returned HTTP ${response.statusCode}.")
                    }
                    if (response.contentLength >= 0 && response.contentLength != spec.expectedSize) {
                        throw ReaderFontDownloadException("Font download size does not match the catalog.")
                    }
                    copyVerified(response.input, downloadedFont)
                }
                fontManager.importFont(downloadedFont)
            } finally {
                downloadedFont.delete()
                downloadDirectory.delete()
            }
        }
    }

    private fun copyVerified(input: InputStream, destination: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    throw CancellationException("Font download cancelled.")
                }
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > spec.expectedSize) {
                    throw ReaderFontDownloadException("Font download exceeds the catalog size.")
                }
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        }
        if (total != spec.expectedSize) {
            throw ReaderFontDownloadException("Font download is incomplete.")
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(spec.sha256, ignoreCase = true)) {
            throw ReaderFontDownloadException("Font download failed integrity verification.")
        }
    }

    companion object {
        private const val FontFamilyName = "KanjiStrokeOrders"
        private const val DownloadDirectoryName = ".stroke-order-download"

        internal val defaultSpec = KanjiStrokeOrderFontSpec(
            url = "https://drive.google.com/uc?export=download&id=1TELymEhF0YMK0Ma-fQlpHNmZLg9Xw3zx",
            fileName = "KanjiStrokeOrders_v4.005.ttf",
            expectedSize = 18_076_964L,
            sha256 = "15525b225e9a9f08445eabdb5cac5c145431ac845107e3109b3fdcbfb77ac733",
        )
    }
}
