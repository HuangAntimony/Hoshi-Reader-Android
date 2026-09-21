package moe.antimony.hoshi.features.sasayaki

import java.text.Normalizer
import java.util.Locale
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.filteredReaderText
import moe.antimony.hoshi.epub.isReaderMatchableCodePoint
import moe.antimony.hoshi.epub.visibleReaderText

/** Shared chapter selection and reader code-point coordinates for SRT and ASR. */
object SasayakiSource {
    internal data class Chapter(val index: Int, val html: String, val text: IntArray)

    internal data class Projection(
        val text: IntArray,
        val starts: IntArray,
        val ends: IntArray,
    )

    internal fun chapters(book: EpubBook): List<Chapter> =
        book.chapters.mapIndexedNotNull { index, chapter ->
            if (isIncluded(chapter)) Chapter(index, chapter.html, chapter.html.filteredReaderText().codePointsArray()) else null
        }

    private fun isIncluded(chapter: EpubChapter): Boolean {
        val path = chapter.href.lowercase(Locale.ROOT)
        return chapter.linear && !chapter.isGuideToc &&
            chapter.properties?.splitToSequence(Regex("\\s+"))?.none { it.equals("nav", ignoreCase = true) } != false &&
            listOf("toc", "caution", "colophon").none(path::contains)
    }

    internal fun boundaries(chapter: Chapter): BooleanArray {
        val result = BooleanArray(chapter.text.size)
        var offset = 0
        chapter.html.visibleReaderText(preserveParagraphs = true).codePoints().forEach { point ->
            if (point.isReaderMatchableCodePoint()) {
                offset++
            } else if (offset > 0 && offset <= result.size && point in sentenceEnders) {
                result[offset - 1] = true
            }
        }
        if (result.isNotEmpty()) result[result.lastIndex] = true
        return result
    }

    internal fun projections(chapter: Chapter): List<Projection> {
        val plain = projection(chapter.html.visibleReaderText())
        val body = Regex("(?s)<body.*?</body>").find(chapter.html)?.value ?: chapter.html
        val markup = body.replace(Regex("(?s)<(script|style)[^>]*>.*?</\\1>"), "")
        val text = mutableListOf<Int>()
        val starts = mutableListOf<Int>()
        val ends = mutableListOf<Int>()
        var sourceOffset = 0
        var cursor = 0
        var hasReading = false
        fun append(base: String, reading: String = base) {
            val baseCount = base.codePoints().filter { it.isReaderMatchableCodePoint() }.count().toInt()
            if (baseCount == 0) return
            val spoken = projection(reading).text.takeIf { it.isNotEmpty() } ?: projection(base).text
            spoken.forEachIndexed { index, point ->
                text += point
                starts += sourceOffset + index * baseCount / spoken.size
                ends += sourceOffset + ((index + 1) * baseCount + spoken.size - 1) / spoken.size
            }
            sourceOffset += baseCount
        }
        ruby.findAll(markup).forEach { match ->
            append(markup.substring(cursor, match.range.first).visibleReaderText())
            val base = match.value.visibleReaderText()
            val reading = rt.findAll(match.value).joinToString("") { it.groupValues[1] }.visibleReaderText()
            if (base.isNotEmpty()) {
                append(base, reading.ifEmpty { base })
                hasReading = hasReading || reading.isNotEmpty()
            }
            cursor = match.range.last + 1
        }
        append(markup.substring(cursor).visibleReaderText())
        // ReaderTextFilter owns stored offsets. Malformed markup must never change them.
        if (!hasReading || sourceOffset != chapter.text.size) return listOf(plain)
        return listOf(plain, Projection(text.toIntArray(), starts.toIntArray(), ends.toIntArray()))
    }

    internal fun normalizedText(text: String): IntArray =
        projection(text.visibleReaderText()).text

    private fun projection(visible: String): Projection {
        val text = mutableListOf<Int>()
        val starts = mutableListOf<Int>()
        val ends = mutableListOf<Int>()
        val points = visible.codePointsArray()
        var cursor = 0
        var sourceOffset = 0
        while (cursor < points.size) {
            val point = points[cursor++]
            if (!point.isReaderMatchableCodePoint()) continue
            val cluster = buildString {
                appendCodePoint(point)
                // Dakuten/handakuten are visible but excluded from reader offsets. Compose
                // them with their base before NFKC, retaining the original one-point range.
                while (cursor < points.size && points[cursor] in voicingMarks) appendCodePoint(points[cursor++])
            }
            normalize(cluster).forEach {
                text += it
                starts += sourceOffset
                ends += sourceOffset + 1
            }
            sourceOffset++
        }
        return Projection(text.toIntArray(), starts.toIntArray(), ends.toIntArray())
    }

    private fun normalize(cluster: String): IntArray = Normalizer.normalize(
        cluster, Normalizer.Form.NFKC,
    ).lowercase(Locale.ROOT).codePoints().map { if (it in 0x30A1..0x30F6) it - 0x60 else it }.toArray()

    private val ruby = Regex("(?s)<ruby\\b[^>]*>.*?</ruby>")
    private val rt = Regex("(?s)<rt\\b[^>]*>(.*?)</rt>")
    private val voicingMarks = setOf(0x3099, 0x309A, 0xFF9E, 0xFF9F)
    private val sentenceEnders = "。！？!?…」』「『（\n、，,".codePointsArray().toSet()
}
