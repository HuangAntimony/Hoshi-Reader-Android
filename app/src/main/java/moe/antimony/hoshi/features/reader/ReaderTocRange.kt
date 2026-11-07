package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.EpubBook

internal data class ReaderTocRange(
    val startCharacter: Int,
    val endCharacter: Int,
) {
    val totalCharacters: Int
        get() = (endCharacter - startCharacter).coerceAtLeast(0)

    fun currentCharacter(absoluteCharacter: Int): Int =
        (absoluteCharacter - startCharacter).coerceIn(0, totalCharacters)

    fun remainingCharacters(absoluteCharacter: Int): Int =
        totalCharacters - currentCharacter(absoluteCharacter)
}

internal fun EpubBook.tocRangeAt(position: ReaderChapterPosition): ReaderTocRange {
    val absoluteCharacter = characterCountAt(position.index, position.progress)
    val chapter = chapters.getOrNull(position.index)
    val chapterInfo = chapter?.let { bookInfo.chapterInfo[it.href] }
    val xhtmlEnd = chapterInfo?.let { it.currentTotal + it.chapterCount }
    val rangeAnchor = if (xhtmlEnd != null && xhtmlEnd > (chapterInfo.currentTotal)) {
        minOf(absoluteCharacter, xhtmlEnd - 1)
    } else {
        absoluteCharacter
    }
    val starts = tocChapterStarts
    val startIndex = starts.indexOfLast { it <= rangeAnchor }.coerceAtLeast(0)
    val start = starts.getOrElse(startIndex) { 0 }
    val end = starts.getOrNull(startIndex + 1) ?: bookInfo.characterCount
    return ReaderTocRange(
        startCharacter = start.coerceIn(0, bookInfo.characterCount.coerceAtLeast(0)),
        endCharacter = end.coerceIn(start.coerceAtLeast(0), bookInfo.characterCount.coerceAtLeast(0)),
    )
}
