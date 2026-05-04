package moe.antimony.hoshi.features.reader

import android.webkit.WebResourceResponse
import java.io.File
import java.net.URI
import moe.antimony.hoshi.epub.EpubBook

internal data class ReaderWebResource(
    val mediaType: String,
    val encoding: String?,
    val data: ByteArray,
) {
    fun toWebResourceResponse(): WebResourceResponse =
        WebResourceResponse(mediaType, encoding, data.inputStream())
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
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host != "hoshi.local") return null
        val path = uri.path.orEmpty()
        return when {
            path.startsWith("/fonts/") -> fontResource(path.removePrefix("/fonts/"))
            path.startsWith("/epub/") -> epubResource(path.removePrefix("/epub/"))
            else -> null
        }
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
        val data = book.readResource(path)?.let { sanitizeReaderResource(mediaType, it) } ?: return null
        val encoding = if (mediaType.substringBefore(';').trim().equals("text/css", ignoreCase = true)) {
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
