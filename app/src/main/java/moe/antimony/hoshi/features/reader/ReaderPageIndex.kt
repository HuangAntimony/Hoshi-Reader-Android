package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.EpubBook

data class ReaderPageProgress(val current: Int, val total: Int, val chapterCurrent: Int, val chapterTotal: Int)

/** Layout-derived positions only; persisted reading positions remain character based. */
internal class ReaderPageIndex private constructor(
    private val book: EpubBook,
    val starts: List<List<Int>>,
) {
    private val firstPages = starts.runningFold(0) { total, spine -> total + spine.size }
    private val characterStarts = book.chapters.map { book.bookInfo.chapterInfo.getValue(it.href).currentTotal }
    private val absoluteStarts = starts.flatMapIndexed { index, offsets -> offsets.map { characterStarts[index] + it } }
    val totalPages: Int get() = firstPages.last()

    fun pageAt(character: Int, spineIndex: Int? = null, preferSpineStart: Boolean = false): Int {
        val spine = spineIndex?.takeIf { it in starts.indices } ?: book.chapters.indices.firstOrNull { index ->
            val info = book.bookInfo.chapterInfo.getValue(book.chapters[index].href)
            character >= info.currentTotal && character < info.currentTotal + info.chapterCount
        } ?: starts.lastIndex
        val local = character - characterStarts[spine]
        // Blank/image pages carry the next text offset. Text labels select that
        // text's page; a TOC spine entry still names the first physical page.
        val page = if (preferSpineStart && local <= 0) 0
            else starts[spine].indexOfLast { it <= local }.coerceAtLeast(0)
        return firstPages[spine] + page + 1
    }

    fun progress(position: ReaderChapterPosition, currentPage: Int?, range: ReaderTocRange = book.tocRangeAt(position)): ReaderPageProgress {
        val spine = position.index.coerceIn(starts.indices)
        val character = book.characterCountAt(spine, position.progress)
        val localPage = currentPage ?: when {
            position.progress <= 0.0 -> 0
            position.progress >= 1.0 -> starts[spine].lastIndex
            else -> null
        }
        val page = localPage?.let { firstPages[spine] + it.coerceIn(starts[spine].indices) + 1 }
            ?: pageAt(character, spine)
        val first = absoluteStarts.indexOfFirst { it == range.startCharacter }.takeIf { it >= 0 }
            ?: absoluteStarts.indexOfLast { it < range.startCharacter }.coerceAtLeast(0)
        val last = if (range.endCharacter == book.bookInfo.characterCount) totalPages - 1
            else absoluteStarts.indexOfLast { it < range.endCharacter }.coerceAtLeast(first)
        val count = (last - first + 1).coerceAtLeast(1)
        return ReaderPageProgress(page, totalPages, (page - first).coerceIn(1, count), count)
    }

    companion object {
        fun create(book: EpubBook, starts: List<List<Int>>): ReaderPageIndex? {
            if (book.chapters.isEmpty() || starts.size != book.chapters.size) return null
            for ((index, offsets) in starts.withIndex()) {
                val info = book.bookInfo.chapterInfo[book.chapters[index].href] ?: return null
                if (offsets.isEmpty() || offsets.any { it !in 0..info.chapterCount } ||
                    offsets.zipWithNext().any { (a, b) -> a > b }) return null
            }
            return ReaderPageIndex(book, starts.map { it.toList() })
        }
    }
}

internal data class ReaderPageCounts(val index: ReaderPageIndex? = null, val failed: Boolean = false)
