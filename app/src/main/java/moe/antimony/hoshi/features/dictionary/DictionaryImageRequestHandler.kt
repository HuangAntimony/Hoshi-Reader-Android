package moe.antimony.hoshi.features.dictionary

import android.net.Uri
import android.webkit.WebResourceResponse
import de.manhhao.hoshi.HoshiDicts
import java.io.ByteArrayInputStream

internal class DictionaryImageRequestHandler(
    private val loadMedia: (dictionary: String, path: String) -> ByteArray? = { dictionary, path ->
        HoshiDicts.getMediaFile(HoshiDicts.lookupObject, dictionary, path)
    },
) {
    fun handleImageRequest(uri: Uri): WebResourceResponse? {
        val isIosImageScheme = uri.scheme == "image"
        val isAndroidImageEndpoint = uri.scheme == "https" &&
            uri.host == "appassets.androidplatform.net" &&
            uri.path == "/image"
        if (!isIosImageScheme && !isAndroidImageEndpoint) return null
        val dictionary = uri.getQueryParameter("dictionary").orEmpty()
        val mediaPath = uri.getQueryParameter("path").orEmpty()
        if (dictionary.isBlank() || mediaPath.isBlank()) return null
        val data = loadMedia(dictionary, mediaPath)?.takeIf { it.isNotEmpty() } ?: return null

        return WebResourceResponse(
            dictionaryImageMimeType(mediaPath),
            null,
            ByteArrayInputStream(data),
        ).apply {
            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
        }
    }
}

internal fun dictionaryImageMimeType(path: String): String =
    when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "heic" -> "image/heic"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }
