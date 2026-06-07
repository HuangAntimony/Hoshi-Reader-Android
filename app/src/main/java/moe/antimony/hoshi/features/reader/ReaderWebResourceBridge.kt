package moe.antimony.hoshi.features.reader

import android.webkit.WebResourceResponse
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import moe.antimony.hoshi.epub.EpubBook

internal data class ReaderWebResource(
    val mediaType: String,
    val encoding: String?,
    val data: ByteArray,
    val statusCode: Int = HttpURLConnection.HTTP_OK,
    val reasonPhrase: String = "OK",
) {
    fun toWebResourceResponse(): WebResourceResponse =
        WebResourceResponse(
            mediaType,
            encoding,
            statusCode,
            reasonPhrase,
            emptyMap(),
            data.inputStream(),
        )

    companion object {
        fun notFound(): ReaderWebResource =
            ReaderWebResource(
                mediaType = "text/plain",
                encoding = "UTF-8",
                data = ByteArray(0),
                statusCode = HttpURLConnection.HTTP_NOT_FOUND,
                reasonPhrase = "Not Found",
            )
    }
}

internal class ReaderWebResourceBridge(
    private val book: EpubBook,
    private val fontFileForRequest: (String) -> File?,
) {
    constructor(
        book: EpubBook,
        fontManager: ReaderFontManager,
    ) : this(book, fontManager::fontFileForRequest)

    fun resourceForUrl(url: String): ReaderWebResource? {
        val uri = runCatching { URI(url).normalize() }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net") return null
        val path = uri.path.orEmpty()
        return when {
            path.startsWith("/fonts/") -> fontResource(path.removePrefix("/fonts/")) ?: ReaderWebResource.notFound()
            path.startsWith("/epub/") -> epubResource(path.removePrefix("/epub/")) ?: ReaderWebResource.notFound()
            else -> ReaderWebResource.notFound()
        }
    }

    fun imageResourceForUrl(url: String): ReaderWebResource? {
        val uri = runCatching { URI(url).normalize() }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net") return null
        val path = uri.path.orEmpty().removePrefix("/epub/")
        if (path.isBlank() || path == uri.path.orEmpty()) return null
        val mediaType = book.mediaType(path).substringBefore(';').trim()
        if (!mediaType.startsWith("image/", ignoreCase = true)) return null
        val data = book.readResource(path) ?: return null
        return ReaderWebResource(
            mediaType = mediaType,
            encoding = null,
            data = data,
        )
    }

    private fun fontResource(fileName: String): ReaderWebResource? {
        val fontFile = fontFileForRequest(fileName) ?: return null
        return ReaderWebResource(
            mediaType = fontFile.mediaType(),
            encoding = null,
            data = fontFile.readBytes(),
        )
    }

    private fun epubResource(path: String): ReaderWebResource? {
        val mediaType = book.mediaType(path)
        val rawData = book.readResource(path) ?: return null
        val normalizedMediaType = mediaType.substringBefore(';').trim()
        val data = if (normalizedMediaType.isReaderHtmlMediaType()) {
            readerHtmlWithEarlyViewport(rawData.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        } else {
            sanitizeReaderResource(mediaType, rawData)
        }
        val encoding = if (
            normalizedMediaType.equals("text/css", ignoreCase = true) ||
            normalizedMediaType.isReaderHtmlMediaType()
        ) {
            "UTF-8"
        } else {
            null
        }
        return ReaderWebResource(
            mediaType = mediaType,
            encoding = encoding,
            data = data,
        )
    }
}

private fun String.isReaderHtmlMediaType(): Boolean =
    equals("application/xhtml+xml", ignoreCase = true) ||
        equals("text/html", ignoreCase = true) ||
        endsWith("+html", ignoreCase = true)
