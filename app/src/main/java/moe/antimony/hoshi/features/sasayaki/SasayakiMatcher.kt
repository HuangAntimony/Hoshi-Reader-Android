package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.filteredReaderText

object SasayakiMatcher {
    private data class ChapterRange(
        val chapterIndex: Int,
        val start: Int,
        val length: Int,
    ) {
        val end: Int get() = start + length
    }

    fun match(book: EpubBook, cues: List<SasayakiCue>, searchWindow: Int): SasayakiMatchData {
        val source = mutableListOf<Int>()
        val chapters = mutableListOf<ChapterRange>()
        book.chapters.forEachIndexed { index, chapter ->
            if (!chapter.linear) return@forEachIndexed
            if (chapter.properties.hasManifestProperty("nav")) return@forEachIndexed
            if (chapter.isGuideToc) return@forEachIndexed
            val codePoints = chapter.html.filteredReaderText().codePointsList()
            chapters += ChapterRange(
                chapterIndex = index,
                start = source.size,
                length = codePoints.size,
            )
            source += codePoints
        }

        var start = 0
        var minStart: Int? = null
        for (cue in cues.take(15)) {
            if (cue.text.startsWith("＊")) continue
            val text = cue.text.filteredReaderText().codePointsList()
            if (text.size < 6) continue
            val index = findText(source, text, start = 0, end = source.size) ?: continue
            minStart = minOf(minStart ?: index, index)
        }
        minStart?.let { start = it }

        val matches = mutableListOf<SasayakiMatch>()
        var unmatched = 0
        var cursor = start

        for (cue in cues) {
            val text = cue.text.filteredReaderText()
            if (text.isEmpty()) {
                unmatched += 1
                continue
            }
            val chars = text.codePointsList()
            val index = findText(source, chars, start = cursor, end = minOf(source.size, cursor + searchWindow))
            if (index == null) {
                unmatched += 1
                continue
            }
            val end = index + chars.size
            val range = chapters.firstOrNull { index >= it.start && index < it.end }
            if (range == null || end > range.end) {
                unmatched += 1
                continue
            }

            cursor = end
            matches += SasayakiMatch(
                id = cue.id,
                startTime = cue.startTime,
                endTime = cue.endTime,
                text = cue.text,
                chapterIndex = range.chapterIndex,
                start = index - range.start,
                length = chars.size,
            )
        }

        return SasayakiMatchData(matches = matches, unmatched = unmatched)
    }

    private fun findText(source: List<Int>, text: List<Int>, start: Int, end: Int): Int? {
        if (text.isEmpty()) return null
        var index = start
        val last = end - text.size
        while (index <= last) {
            var matched = true
            for (i in text.indices) {
                if (source[index + i] != text[i]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
            index += 1
        }
        return null
    }
}

internal fun String.codePointsList(): List<Int> =
    codePoints().toArray().toList()

private fun String?.hasManifestProperty(property: String): Boolean =
    this
        ?.trim()
        ?.splitToSequence(Regex("\\s+"))
        ?.any { it == property } == true
