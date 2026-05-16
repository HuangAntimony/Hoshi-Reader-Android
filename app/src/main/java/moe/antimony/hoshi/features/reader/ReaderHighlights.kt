package moe.antimony.hoshi.features.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.EpubTocItem
import moe.antimony.hoshi.epub.ReaderHighlight

internal object ReaderHighlights {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun chapterHighlightsJson(
        highlights: List<ReaderHighlight>,
        bookInfo: BookInfo,
        chapter: EpubChapter,
    ): String? {
        val range = chapterRange(bookInfo, chapter) ?: return null
        val chapterHighlights = highlights
            .filter { it.character >= range.start && it.character < range.end }
            .sortedBy { it.character }
        if (chapterHighlights.isEmpty()) return null
        return json.encodeToString(chapterHighlights)
    }

    fun chapterContains(
        highlight: ReaderHighlight,
        bookInfo: BookInfo,
        chapter: EpubChapter,
    ): Boolean {
        val range = chapterRange(bookInfo, chapter) ?: return false
        return highlight.character >= range.start && highlight.character < range.end
    }

    fun positionForCharacter(bookInfo: BookInfo, character: Int): ReaderChapterPosition {
        val chapters = bookInfo.chapterInfo.values
            .mapIndexedNotNull { fallbackIndex, info ->
                val index = info.spineIndex ?: fallbackIndex
                val start = info.currentTotal
                val end = start + info.chapterCount
                ChapterRange(index = index, start = start, end = end, count = info.chapterCount)
            }
            .sortedBy { it.index }
        val target = chapters.firstOrNull { character >= it.start && character < it.end }
            ?: chapters.lastOrNull()
            ?: return ReaderChapterPosition(index = 0, progress = 0.0)
        val progress = if (target.count <= 0) {
            0.0
        } else {
            (character - target.start).toDouble().div(target.count.toDouble()).coerceIn(0.0, 1.0)
        }
        return ReaderChapterPosition(index = target.index, progress = progress)
    }

    private fun chapterRange(bookInfo: BookInfo, chapter: EpubChapter): ChapterRange? {
        val info = bookInfo.chapterInfo[chapter.href] ?: return null
        return ChapterRange(
            index = info.spineIndex ?: 0,
            start = info.currentTotal,
            end = info.currentTotal + info.chapterCount,
            count = info.chapterCount,
        )
    }

    private data class ChapterRange(
        val index: Int,
        val start: Int,
        val end: Int,
        val count: Int,
    )
}

internal object ReaderHighlightSections {
    fun sections(book: EpubBook, highlights: List<ReaderHighlight>): List<ReaderHighlightSection> {
        val labels = chapterLabels(book)
        val grouped = highlights.groupBy { highlight ->
            val position = ReaderHighlights.positionForCharacter(book.bookInfo, highlight.character)
            position.index
        }
        return grouped.map { (chapterIndex, items) ->
            ReaderHighlightSection(
                chapterIndex = chapterIndex,
                label = labels[chapterIndex].orEmpty(),
                highlights = items.sortedBy { it.character },
            )
        }.sortedBy { it.chapterIndex }
    }

    private fun chapterLabels(book: EpubBook): Map<Int, String> {
        val pathToSpine = book.chapters.associate { chapter -> chapter.href to (chapter.spineIndex ?: book.chapters.indexOf(chapter)) }
        val labels = linkedMapOf<Int, String>()
        fun walk(items: List<EpubTocItem>, topLabel: String?) {
            items.forEach { item ->
                val label = topLabel ?: item.label
                val path = item.href?.substringBefore("#")
                val index = path?.let(pathToSpine::get)
                if (index != null && labels[index] == null) {
                    labels[index] = label
                }
                walk(item.children, label)
            }
        }
        walk(book.toc, null)
        return labels
    }
}

internal data class ReaderHighlightSection(
    val chapterIndex: Int,
    val label: String,
    val highlights: List<ReaderHighlight>,
)

@Serializable
internal data class ReaderHighlightCreationResult(
    val start: Int,
    val offset: Int,
    val text: String,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromWebViewResult(result: String?): ReaderHighlightCreationResult? {
            val trimmed = result?.trim().orEmpty()
            if (trimmed.isBlank() || trimmed == "null" || trimmed == "undefined") return null
            return runCatching { json.decodeFromString<ReaderHighlightCreationResult>(trimmed) }.getOrNull()
        }
    }
}
