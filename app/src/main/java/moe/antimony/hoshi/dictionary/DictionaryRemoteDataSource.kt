package moe.antimony.hoshi.dictionary

import kotlinx.serialization.json.Json
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

internal interface DictionaryRemoteDataSource {
    fun fetchIndex(url: String): DictionaryIndex
    fun downloadArchive(url: String): InputStream
}

internal class UrlDictionaryRemoteDataSource(
    private val json: Json,
) : DictionaryRemoteDataSource {
    @Inject
    constructor() : this(Json { ignoreUnknownKeys = true })

    override fun fetchIndex(url: String): DictionaryIndex =
        openConnection(url).use { connection ->
            require(connection.responseCode in 200..299) { "Unable to fetch dictionary index." }
            connection.inputStream.use { input ->
                json.decodeFromString<DictionaryIndex>(input.readBytes().decodeToString())
            }
        }

    override fun downloadArchive(url: String): InputStream =
        openConnection(url).let { connection ->
            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                error("Unable to download dictionary.")
            }
            HttpConnectionInputStream(connection)
        }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
}

private class HttpConnectionInputStream(
    private val connection: HttpURLConnection,
) : InputStream() {
    private val delegate = connection.inputStream

    override fun read(): Int = delegate.read()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length)

    override fun close() {
        try {
            delegate.close()
        } finally {
            connection.disconnect()
        }
    }
}

private inline fun <T : HttpURLConnection, R> T.use(block: (T) -> R): R =
    try {
        block(this)
    } finally {
        disconnect()
    }
