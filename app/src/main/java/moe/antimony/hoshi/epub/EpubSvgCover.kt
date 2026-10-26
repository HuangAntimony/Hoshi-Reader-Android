package moe.antimony.hoshi.epub

import android.graphics.BitmapFactory
import java.io.File
import java.net.URI
import kotlin.math.abs
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** Resolve only a lossless, single-raster SVG wrapper; this is not an SVG renderer. */
internal fun unwrapEpubSvgCover(root: File, href: String, rasterDimensions: (File) -> Pair<Int, Int>?): String? {
    val canonicalRoot = root.canonicalFile
    val source = canonicalRoot.epubChild(href)?.takeIf { it.isFile } ?: return null
    val xml = source.readText()
    if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<?xml-stylesheet", ignoreCase = true)) return null
    val document = Jsoup.parse(xml, "", Parser.xmlParser())
    val svg = document.children().singleOrNull()?.takeIf { it.normalName() == "svg" } ?: return null
    if (svg.attr("xmlns") != "http://www.w3.org/2000/svg") return null
    if (!svg.hasOnlyAttributes(svgCoverRootAttributes) || !svg.usesUncroppedAspectRatio()) return null
    val image = svg.children().singleOrNull()?.takeIf { it.normalName() == "image" } ?: return null
    if (svg.ownText().isNotBlank() || image.text().isNotBlank() || image.children().isNotEmpty()) return null
    if (!image.hasOnlyAttributes(svgCoverImageAttributes) || !image.usesUncroppedAspectRatio()) return null
    val box = svg.attr("viewBox").trim().split(Regex("[\\s,]+")).map { it.toDoubleOrNull() ?: return null }
    if (box.size != 4 || box.any { !it.isFinite() } || box[0] != 0.0 || box[1] != 0.0 || box[2] <= 0 || box[3] <= 0) return null
    fun fillsCanvas(width: Double, height: Double): Boolean =
        width.isFinite() && height.isFinite() && width > 0 && height > 0 && abs(width / height - box[2] / box[3]) < 1e-9
    if (svg.hasAttr("width") || svg.hasAttr("height")) {
        val width = svg.attr("width").removeSuffix("px").toDoubleOrNull() ?: return null
        val height = svg.attr("height").removeSuffix("px").toDoubleOrNull() ?: return null
        if (!fillsCanvas(width, height)) return null
    }
    if (image.attr("x").ifBlank { "0" }.toDoubleOrNull() != 0.0 || image.attr("y").ifBlank { "0" }.toDoubleOrNull() != 0.0) return null
    if (image.attr("width").toDoubleOrNull() != box[2] || image.attr("height").toDoubleOrNull() != box[3]) return null
    val reference = image.attr("href").ifBlank { image.attr("xlink:href") }.trim()
    val uri = runCatching { URI(reference.replace(" ", "%20")) }.getOrNull() ?: return null
    if (reference.startsWith('/') || uri.isAbsolute || uri.rawQuery != null || uri.rawFragment != null) return null
    val path = resolveEpubImageReference(href, reference) ?: return null
    val raster = canonicalRoot.epubChild(path)?.takeIf { it.isFile } ?: return null
    if (raster.extension.lowercase() !in setOf("jpg", "jpeg", "png", "gif", "webp")) return null
    val dimensions = runCatching { rasterDimensions(raster) }.getOrNull() ?: return null
    if (!fillsCanvas(dimensions.first.toDouble(), dimensions.second.toDouble())) return null
    return path
}

internal fun readEpubRasterDimensions(file: File): Pair<Int, Int>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
}

private fun File.epubChild(href: String): File? =
    resolve(href).canonicalFile.takeIf { it.path.startsWith(path + File.separator) }

private fun Element.hasOnlyAttributes(allowed: Set<String>): Boolean =
    attributes().all { it.key in allowed }

private fun Element.usesUncroppedAspectRatio(): Boolean =
    attr("preserveAspectRatio").trim() in setOf("", "xMidYMid", "xMidYMid meet")

private val svgCoverRootAttributes = setOf(
    "xmlns", "xmlns:xlink", "xmlns:ev", "version", "baseProfile", "viewBox", "width", "height", "preserveAspectRatio", "id",
)
private val svgCoverImageAttributes = setOf("href", "xlink:href", "x", "y", "width", "height", "preserveAspectRatio", "id")
