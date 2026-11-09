package moe.antimony.hoshi.features.reader

import java.io.File
import java.net.URI
import java.util.UUID
import moe.antimony.hoshi.epub.EpubBook

/** URLs belong to this immutable book/font measurement snapshot, never another layout. */
internal class ReaderPageMeasurementResources(book: EpubBook, fontFileForRequest: (String) -> File?) {
    private val bridge = ReaderWebResourceBridge(book, fontFileForRequest)
    private val prefix = "/reader-pages/${UUID.randomUUID()}"

    fun url(path: String): String = URI("https", "appassets.androidplatform.net", prefix + path, null, null).toASCIIString()

    fun font(spec: ReaderFontRenderSpec?): ReaderFontRenderSpec? = spec?.copy(faces = spec.faces.map { face ->
        val source = URI(face.url)
        require(source.scheme == "https" && source.host == "appassets.androidplatform.net" && source.path.startsWith("/fonts/"))
        face.copy(url = URI(source.scheme, source.authority, prefix + source.path, source.query, source.fragment).toASCIIString())
    })

    fun resourceForUrl(value: String): ReaderWebResource? {
        val uri = runCatching { URI(value).normalize() }.getOrNull() ?: return ReaderWebResource.notFound()
        // Match the visible Reader for root-absolute publisher URLs and remote
        // resources. Only this snapshot's namespaced responses become cacheable.
        if (uri.scheme != "https" || uri.rawAuthority != "appassets.androidplatform.net" ||
            !uri.path.orEmpty().startsWith("/reader-pages/")) return bridge.resourceForUrl(value)
        if (!uri.path.orEmpty().startsWith("$prefix/")) {
            return ReaderWebResource.notFound()
        }
        val source = URI(uri.scheme, uri.authority, uri.path.removePrefix(prefix), uri.query, uri.fragment).toASCIIString()
        val resource = bridge.resourceForUrl(source) ?: return ReaderWebResource.notFound()
        // Blink's resource cache can reuse decoded fonts/images and parsed CSS across
        // chapter navigations. The unique prefix prevents reuse after layout/font changes.
        val type = resource.mediaType.substringBefore(';')
        return if (resource.statusCode == 200 && (type == "text/css" || type.startsWith("image/") ||
                type.startsWith("font/") || uri.path.removePrefix(prefix).startsWith("/fonts/"))) {
            resource.copy(headers = mapOf("Cache-Control" to "private, max-age=31536000"))
        } else resource
    }
}
