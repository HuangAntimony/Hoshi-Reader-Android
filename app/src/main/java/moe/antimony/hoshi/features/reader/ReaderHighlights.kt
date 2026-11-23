package moe.antimony.hoshi.features.reader

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.ReaderHighlight
import moe.antimony.hoshi.epub.HighlightColor

internal object ReaderHighlights {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun applyEdit(
        highlights: List<ReaderHighlight>,
        result: ReaderHighlightResult,
        color: HighlightColor,
    ): List<ReaderHighlight> = when (result) {
        is ReaderHighlightResult.Recolored -> highlights.map {
            if (it.id == result.id) it.copy(color = color) else it
        }
        is ReaderHighlightResult.Removed -> highlights.filterNot { it.id == result.id }
        is ReaderHighlightCreationResult, is ReaderHighlightResult.Batch -> highlights
    }

    fun applySelection(
        highlights: List<ReaderHighlight>,
        result: ReaderHighlightResult,
        color: HighlightColor,
        id: String,
        book: EpubBook,
        fallbackChapter: Int,
        createdAt: Double,
    ): List<ReaderHighlight> {
        var edited = highlights
        val changes = if (result is ReaderHighlightResult.Batch) result.changes else listOf(result)
        for (change in changes) {
            if (change is ReaderHighlightCreationResult) {
                val chapter = book.chapters.getOrNull(change.chapterIndex ?: fallbackChapter) ?: return highlights
                val info = book.bookInfo.chapterInfo[chapter.href] ?: return highlights
                edited = edited + ReaderHighlight(
                    id = change.id ?: id,
                    // A trailing punctuation or space can start after the last counted character.
                    // Raw offset selects the text; the navigation character identifies its source chapter.
                    character = info.currentTotal + change.start.coerceAtMost((info.chapterCount - 1).coerceAtLeast(0)),
                    offset = change.offset,
                    text = change.text,
                    textFurigana = change.textFurigana,
                    color = color,
                    createdAt = createdAt,
                )
            } else {
                edited = applyEdit(edited, change, color)
            }
        }
        return edited
    }

    fun bookHighlightsJson(highlights: List<ReaderHighlight>, book: EpubBook): String? {
        if (highlights.isEmpty()) return null
        val sorted = highlights.sortedBy { it.character }
        val grouped = mutableMapOf<Int, List<ReaderHighlight>>()
        var cursor = 0
        for ((index, chapter) in book.chapters.withIndex()) {
            val range = chapterRange(book.bookInfo, chapter) ?: continue
            while (cursor < sorted.size && sorted[cursor].character < range.start) cursor++
            val first = cursor
            while (cursor < sorted.size && sorted[cursor].character < range.end) cursor++
            if (cursor > first) grouped[index] = sorted.subList(first, cursor)
        }
        return json.encodeToString(grouped)
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
        val labels = ReaderChapterLabels.labels(book)
        val grouped = highlights.groupBy { highlight ->
            ReaderHighlights.positionForCharacter(book.bookInfo, highlight.character).index.let { index ->
                var resolved = index
                while (resolved > 0 && labels[resolved] == null) {
                    resolved -= 1
                }
                resolved
            }
        }
        return grouped.map { (chapterIndex, items) ->
            ReaderHighlightSection(
                chapterIndex = chapterIndex,
                label = ReaderChapterLabels.sectionLabelForIndex(labels, chapterIndex),
                highlights = items.sortedBy { it.character },
            )
        }.sortedBy { it.chapterIndex }
    }
}

internal data class ReaderHighlightSection(
    val chapterIndex: Int,
    val label: String,
    val highlights: List<ReaderHighlight>,
)

@Serializable
@SerialName("created")
internal data class ReaderHighlightCreationResult(
    val start: Int,
    val offset: Int,
    val text: String,
    val textFurigana: String? = null,
    val id: String? = null,
    val chapterIndex: Int? = null,
) : ReaderHighlightResult

@Serializable
internal sealed interface ReaderHighlightResult {
    @Serializable
    @SerialName("batch")
    data class Batch(val changes: List<ReaderHighlightResult>) : ReaderHighlightResult

    @Serializable
    @SerialName("recolored")
    data class Recolored(val id: String) : ReaderHighlightResult

    @Serializable
    @SerialName("removed")
    data class Removed(val id: String) : ReaderHighlightResult

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "action"
        }

        private val ReaderHighlightCreationResult.isValid: Boolean
            get() = start >= 0 && offset >= 0 && text.isNotEmpty() && (chapterIndex == null || chapterIndex >= 0)

        fun fromWebViewResult(result: String?): ReaderHighlightResult? = runCatching {
            json.decodeFromString<ReaderHighlightResult>(result ?: return null).takeIf {
                when (it) {
                    is Batch -> it.changes.isNotEmpty() && it.changes.all { change ->
                        when (change) {
                            is ReaderHighlightCreationResult -> change.isValid && change.chapterIndex != null && !change.id.isNullOrBlank()
                            is Removed -> change.id.isNotBlank()
                            is Recolored -> change.id.isNotBlank()
                            is Batch -> false
                        }
                    }
                    is ReaderHighlightCreationResult -> it.isValid
                    is Recolored -> it.id.isNotBlank()
                    is Removed -> it.id.isNotBlank()
                }
            }
        }.getOrNull()
    }
}
