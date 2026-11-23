package moe.antimony.hoshi.features.reader

import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.readEpubRasterDimensions
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.jsoup.parser.Tag

internal const val READER_BOOK_URL = "https://appassets.androidplatform.net/reader-book/index.html"

private val epubRoot = URI("https://appassets.androidplatform.net/epub/")

/** Resolve EPUB resources only within the book. */
internal fun URI.epubPath(): String? = path?.takeIf {
    scheme == epubRoot.scheme && rawAuthority == epubRoot.rawAuthority && it.startsWith("/epub/")
}?.removePrefix("/epub/")?.takeIf(::safeEntryName)

private fun safeEntryName(path: String): Boolean =
    path.isNotBlank() && path == path.trim() && !path.startsWith('/') && path.none { it == '\\' || it == '\u0000' || it == '?' || it == '#' } &&
        path.split('/').none { it == "." || it == ".." }

internal fun EpubBook.canReadEntry(path: String): Boolean {
    if (!safeEntryName(path)) return false
    val directory = rootDirectory ?: return true
    return try {
        val root = directory.canonicalFile
        root.resolve(path).canonicalFile.path.startsWith(root.path + File.separator)
    } catch (_: IOException) {
        false
    }
}

internal fun resolveBookUrl(base: URI, value: String, allowExternalLink: Boolean = false): URI? {
    val resolved = runCatching { base.resolve(value.trim().replace(" ", "%20")).normalize() }.getOrNull()
    return resolved?.takeIf {
        it.epubPath() != null || (it.toString().endsWith('/') && it.resolve("entry").epubPath() != null) ||
            (allowExternalLink && isExternalReaderWebLink(it.scheme, it.host))
    }
}

internal data class ReaderBookDocument(val html: ByteArray, val css: ByteArray)

internal fun EpubBook.buildReaderBookDocument(
    dimensions: (File) -> Pair<Int, Int>? = ::readEpubRasterDimensions,
): ReaderBookDocument {
    val styles = ReaderBookStyles(this)
    val imageSizes = mutableMapOf<String, Pair<Int, Int>?>()
    val bytes = ByteArrayOutputStream()
    bytes.bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.append("<!doctype html><html><head><meta charset=\"utf-8\">")
        writer.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        writer.append("<link rel=\"stylesheet\" href=\"publisher.css\">")
        writer.append(Element("title").text(title).outerHtml()).append("</head><body>")
        // Release each chapter DOM after writing it. Keep only the merged bytes and shared styles.
        chapters.forEachIndexed { index, chapter ->
            val section = prepareBookSection(chapter, index, styles)
            for (image in section.select("img, svg image")) {
                val source = if (image.normalName() == "img") {
                    image.attr("loading", "lazy").attr("decoding", "async")
                    image.attr("src")
                } else {
                    val href = image.attr("href").ifEmpty { image.attr("xlink:href") }
                    // Definitions may paint a different SVG via <use> or url(#...), even when their own viewport is hidden.
                    if (href.isNotEmpty() && !href.startsWith('#') && image.closest("defs, symbol, pattern, mask, filter, clipPath, marker") == null) {
                        image.attr("data-hoshi-image-href", href).removeAttr("href").removeAttr("xlink:href")
                    }
                    href
                }
                if (image.normalName() != "img" && image.hasAttr("width") && image.hasAttr("height")) continue
                if (!source.startsWith(epubRoot.toString())) continue
                val path = URI(source).epubPath() ?: continue
                if (!imageSizes.containsKey(path)) {
                    imageSizes[path] = imageDimensions(path, dimensions)
                }
                imageSizes[path]?.let { (width, height) ->
                    reserveImageSize(image, width, height)
                    // Use the decoded illustration's layout even while a distant lazy image has no pixels.
                    if (image.normalName() == "img" && (width > 256 || height > 256) &&
                        image.classNames().none { it.contains("gaiji", ignoreCase = true) }
                    ) {
                        image.addClass("block-img").attr("data-hoshi-sized-art", "")
                        val ratio = width.toDouble() / height
                        image.attr("style", image.attr("style") +
                            ";--hoshi-art-width:${width}px;--hoshi-art-height:${height}px;--hoshi-art-ratio:$ratio;")
                    }
                }
            }
            section.outerHtml(writer)
        }
        writer.append("</body></html>")
    }
    return ReaderBookDocument(bytes.toByteArray(), styles.render().encodeToByteArray())
}

private fun EpubBook.prepareBookSection(chapter: EpubChapter, index: Int, styles: ReaderBookStyles): Element {
    val chapterUrl = URI("https", epubRoot.host, "/epub/${chapter.href}", null, null)
    val source = if (canReadEntry(chapter.href)) chapter.html.ifBlank {
        readResource(chapter.href)?.decodeToString().orEmpty()
    } else ""
    val doc = parseBookChapter(source, chapter.mediaType)
    doc.outputSettings().syntax(Document.OutputSettings.Syntax.html).prettyPrint(false)
    val base = doc.selectFirst("base[href]")?.let { resolveBookUrl(chapterUrl, it.attr("href")) } ?: chapterUrl
    val group = styles.collect(doc, base)
    doc.select("script, iframe, frame, frameset, object, embed, base, meta, link, style, template, noscript").remove()
    val html = doc.selectFirst("html")!!
    val body = doc.body()
    // Raw offsets begin at the first body text node. Whitespace around head/body must stay outside the chapter.
    body.remove()
    body.tagName("hoshi-body")
    html.tagName("hoshi-html").empty().appendChild(body)
    val section = Element("section").attr("data-hoshi-chapter", index.toString())
        .attr("data-hoshi-href", chapter.href).attr("data-hoshi-styles", group.token)
    val idPrefix = "hoshi-chapter-$index-"
    for (element in html.getAllElements()) rewriteBookElement(element, base, group, idPrefix)
    html.attr("data-hoshi-root", "")
    group.fragmentVariables(idPrefix).takeIf(String::isNotEmpty)?.let { section.attr("style", it) }
    section.appendChild(html)
    doc.appendChild(section)
    return section
}

private fun parseBookChapter(source: String, mediaType: String): Document {
    if (!mediaType.substringBefore(';').trim().equals("application/xhtml+xml", true)) return Jsoup.parse(source)
    // EPUB XHTML allows <script src="..."/>. An HTML parser treats the rest of that chapter as script data.
    val xml = Jsoup.parse(source, "", Parser.xmlParser())
    if (xml.selectFirst("html > body") != null) return xml
    // Recover common fragments and missing structural tags with HTML's implied head/body insertion.
    // Remove active/raw-text placeholders before reparsing so they cannot consume following content.
    xml.select("script, style:empty").remove()
    return Jsoup.parse(xml.outerHtml())
}

private fun rewriteBookElement(element: Element, base: URI, group: BookStyleGroup, idPrefix: String) {
    // The merged document uses HTML syntax. Empty XHTML anchors need an end tag, while <br> and
    // other void elements must never acquire one (HTML would interpret </br> as another line break).
    if (element.childNodeSize() == 0 && element.tag().namespace() in htmlNamespaces) {
        element.tag(Tag.valueOf(element.tagName()))
    }
    if (element.attributesSize() == 0) return
    if (element.hasAttr("xml:lang") && !element.hasAttr("lang")) element.attr("lang", element.attr("xml:lang"))
    element.attributes().asList().forEach { attribute ->
        val name = attribute.key.lowercase()
        val value = attribute.value
        when {
            name.startsWith("on") || name.startsWith("data-hoshi-") || name in discardedAttributes -> element.removeAttr(attribute.key)
            name == "style" -> element.attr(name, group.inlineStyle(value, base, idPrefix))
            name == "srcset" -> element.attr(name, rebaseSrcset(value, base))
            name in urlAttributes -> {
                val rewritten = when {
                    name.endsWith("href") && value.startsWith('#') && element.normalName() != "a" -> "#$idPrefix${value.drop(1)}"
                    value.startsWith("data:image/", ignoreCase = true) && element.normalName() in imageTags -> value
                    else -> resolveBookUrl(base, value, allowExternalLink = element.normalName() == "a" && name == "href")?.toASCIIString()
                }
                if (rewritten != null) element.attr(attribute.key, rewritten) else element.removeAttr(attribute.key)
            }
            name in svgPaintAttributes -> element.attr(attribute.key, rebaseCssUrls(value, base) { "url(\"#${cssString(idPrefix + it)}\")" })
            name in idListAttributes -> element.attr(name, value.trim().split(whitespace).joinToString(" ") { "$idPrefix$it" })
        }
    }
    if (element.hasAttr("id")) {
        val original = element.id()
        element.attr("data-hoshi-id", original).attr("id", idPrefix + original)
    }
}

private val htmlNamespaces = setOf(Parser.NamespaceHtml, Parser.NamespaceXml)
private val urlAttributes = setOf("href", "src", "poster", "xlink:href", "background", "cite")
private val imageTags = setOf("img", "image", "source")
private val discardedAttributes = setOf("srcdoc", "action", "formaction", "ping", "target", "xml:base", "is")
private val idListAttributes = setOf("for", "headers", "aria-labelledby", "aria-describedby", "aria-controls", "list")
private val svgPaintAttributes = setOf("fill", "stroke", "clip-path", "mask", "filter", "marker-start", "marker-mid", "marker-end")
private val whitespace = Regex("\\s+")

private fun EpubBook.imageDimensions(path: String, rasterFileDimensions: (File) -> Pair<Int, Int>?): Pair<Int, Int>? {
    if (!canReadEntry(path)) return null
    if (mediaType(path).substringBefore(';').equals("image/svg+xml", true)) {
        val svg = readResource(path)?.decodeToString()?.let { Jsoup.parse(it, "", Parser.xmlParser()).selectFirst("svg") } ?: return null
        val width = svg.attr("width").removeSuffix("px").toDoubleOrNull()
        val height = svg.attr("height").removeSuffix("px").toDoubleOrNull()
        if (width != null && height != null && width >= 1 && height >= 1) return width.toInt() to height.toInt()
        val box = svg.attr("viewBox").trim().split(svgCoordinates)
        if (box.size != 4) return null
        val boxWidth = box[2].toDoubleOrNull() ?: return null
        val boxHeight = box[3].toDoubleOrNull() ?: return null
        return if (boxWidth >= 1 && boxHeight >= 1) boxWidth.toInt() to boxHeight.toInt() else null
    }
    rootDirectory?.let { return rasterFileDimensions(it.resolve(path)) }
    val bytes = readResource(path) ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
}

private val svgCoordinates = Regex("[\\s,]+")

private fun reserveImageSize(element: Element, width: Int, height: Int) {
    val statedWidth = element.attr("width")
    val statedHeight = element.attr("height")
    if (statedWidth.isEmpty() && statedHeight.isEmpty()) {
        element.attr("width", width.toString()).attr("height", height.toString())
    } else if (statedWidth.isEmpty()) {
        statedHeight.toDoubleOrNull()?.let { element.attr("width", (it * width / height).toInt().coerceAtLeast(1).toString()) }
    } else if (statedHeight.isEmpty()) {
        statedWidth.toDoubleOrNull()?.let { element.attr("height", (it * height / width).toInt().coerceAtLeast(1).toString()) }
    }
}

private val srcsetCandidate = Regex("""(?:^|,\s*)\s*(data:image/[^\s]+|[^,\s]+)(?:\s+([^,]*?))?(?=\s*(?:,|$))""", RegexOption.IGNORE_CASE)

private fun rebaseSrcset(value: String, base: URI): String =
    srcsetCandidate.findAll(value).mapNotNull { candidate ->
        val source = candidate.groupValues[1]
        val url = if (source.startsWith("data:image/", true)) source else resolveBookUrl(base, source)?.toASCIIString()
        url?.let { (it + " " + candidate.groupValues[2]).trimEnd() }
    }.joinToString(", ")
