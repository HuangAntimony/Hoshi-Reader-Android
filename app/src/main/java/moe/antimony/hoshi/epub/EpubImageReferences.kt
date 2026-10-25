package moe.antimony.hoshi.epub

import java.io.IOException
import java.net.URI
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** Original spine references only; callers must run the first access off the main thread. */
internal fun EpubBook.countImageReferences(): Map<String, Int> = try {
    val counts = linkedMapOf<String, Int>()
    chapters.asSequence()
        .filter { it.linear && !it.isGuideToc && "nav" !in it.properties.orEmpty().split(Regex("\\s+")) }
        .distinctBy { resolveEpubImageReference("", it.href) ?: it.href }
        .forEach { chapter ->
            val html = chapter.html.ifBlank {
                readResource(chapter.href)?.toString(Charsets.UTF_8)
                    ?: throw IOException("Missing chapter while indexing image references: ${chapter.href}")
            }
            val document = Jsoup.parse(html, "", if (chapter.mediaType == "application/xhtml+xml") Parser.xmlParser() else Parser.htmlParser())
            // A publisher base URL changes resource identity. Keep holds when it cannot be resolved here.
            if (document.selectFirst("base[href]") != null) return@forEach
            val body = document.selectFirst("body") ?: document
            body.getAllElements().filter { it.normalName() in setOf("img", "image") }.forEach image@{ image ->
                if (image.parents().any { it.normalName() in setOf("head", "script", "style", "template") }) return@image
                val references = image.imageReferences() ?: return@image
                references.mapNotNull { resolveEpubImageReference(chapter.href, it) }.toSet().forEach { path ->
                    counts[path] = (counts[path] ?: 0) + 1
                }
            }
        }
    counts
} catch (error: IOException) {
    ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.reader_open_failed), error)
    emptyMap()
}

internal fun resolveEpubImageReference(chapterHref: String, source: String): String? {
    if (source.isBlank()) return null
    val uri = runCatching {
        URI("https", "appassets.androidplatform.net", "/epub/$chapterHref", null)
            .resolve(source.trim().replace(" ", "%20")).normalize()
    }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net" || !uri.path.orEmpty().startsWith("/epub/")) return null
    return uri.path.removePrefix("/epub/")
}

/** Count an image once for each possible resource, not once per responsive descriptor. */
private fun Element.imageReferences(): Set<String>? {
    if (normalName() == "image") return setOf(attr("href").ifBlank { attr("xlink:href") })
    if (normalName() != "img") return null
    val sources = linkedSetOf<String>()
    attr("src").takeIf { it.isNotBlank() }?.let(sources::add)
    val sourceSets = listOf(this) + (parent()?.takeIf { it.normalName() == "picture" }?.children()?.filter { it.normalName() == "source" }.orEmpty())
    for (element in sourceSets) {
        val srcset = element.attr("srcset")
        if (srcset.isBlank()) continue
        // Embedded data URLs and unsupported syntax stay eligible for holds.
        if (srcset.contains("data:", ignoreCase = true)) return null
        for (candidate in srcset.split(',')) {
            val fields = candidate.trim().split(Regex("\\s+"))
            if (fields.isEmpty() || fields[0].isBlank() || fields.size > 2 ||
                (fields.size == 2 && !fields[1].matches(Regex("[0-9]+(?:\\.[0-9]+)?[wx]")))) return null
            sources += fields[0]
        }
    }
    return sources.takeIf { it.isNotEmpty() }
}

internal fun EpubBook.shouldHoldImage(chapterHref: String, element: Element): Boolean {
    if (element.ownerDocument()?.selectFirst("base[href]") != null) return true
    if (element.normalName() == "svg") {
        val images = element.select("image")
        // Retain vector artwork, text and mixed illustrations even if a repeated raster is embedded.
        if (images.isEmpty() || element.select("path, rect, circle, ellipse, line, polyline, polygon, text, use, foreignObject").isNotEmpty()) return true
        return images.any { shouldHoldImage(chapterHref, it) }
    }
    val references = element.imageReferences() ?: return true
    // Background playback cannot choose a responsive candidate, so retain a hold if any may be artwork.
    return references.any { reference ->
        val path = resolveEpubImageReference(chapterHref, reference)
        path == null || (imageReferenceCounts[path] ?: 0) < 3
    }
}
