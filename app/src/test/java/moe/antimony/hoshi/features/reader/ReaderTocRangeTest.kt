package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.EpubTocItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTocRangeTest {
    @Test
    fun trueChapterRangeUsesTocFragmentsWithinOneXhtmlFile() {
        val book = tocBook()

        val range = book.tocRangeAt(ReaderChapterPosition(index = 0, progress = 0.6))

        assertEquals(ReaderTocRange(startCharacter = 4, endCharacter = 8), range)
        assertEquals(4, range.totalCharacters)
        assertEquals(2, range.currentCharacter(6))
        assertEquals(2, range.remainingCharacters(6))
    }

    @Test
    fun trueChapterRangeKeepsSpineEndInsideTheCurrentXhtmlLastTocRange() {
        val book = tocBook()

        val range = book.tocRangeAt(ReaderChapterPosition(index = 0, progress = 1.0))

        assertEquals(ReaderTocRange(startCharacter = 8, endCharacter = 10), range)
        assertEquals(2, range.currentCharacter(10))
        assertEquals(0, range.remainingCharacters(10))
    }

    @Test
    fun trueChapterRangeFallsBackToWholeBookWithoutToc() {
        val book = tocBook(toc = emptyList())

        val range = book.tocRangeAt(ReaderChapterPosition(index = 0, progress = 0.6))

        assertEquals(ReaderTocRange(startCharacter = 0, endCharacter = 10), range)
    }

    @Test
    fun chapterRowsUseFragmentStartsAndSelectTheLastStartedRow() {
        val book = tocBook()

        val rows = book.chapterRows(currentCharacter = 6)

        assertEquals(listOf(0, 4, 8), rows.map { it.characterCount })
        assertEquals(listOf(false, true, false), rows.map { it.isCurrent })
    }

    @Test
    fun contentsHeaderShowsBookAndTrueChapterProgress() {
        val progress = tocBook().contentsProgress(
            position = ReaderChapterPosition(index = 0, progress = 0.6),
            progressDisplay = ReaderProgressDisplay.characters(),
        )

        assertEquals("6 / 10 (60.0%)", progress.book)
        assertEquals("2 / 4 (50.0%)", progress.chapter)
    }

    private fun tocBook(
        toc: List<EpubTocItem> = listOf(
            EpubTocItem(
                label = "Chapter",
                href = "chapter.xhtml",
                children = listOf(
                    EpubTocItem(label = "Part 2", href = "chapter.xhtml#part-2"),
                    EpubTocItem(label = "Part 3", href = "chapter.xhtml#part-3"),
                ),
            ),
        ),
    ): EpubBook {
        val chapter = EpubChapter(
            id = "chapter",
            href = "chapter.xhtml",
            mediaType = "application/xhtml+xml",
            html = "一二三四五六七八九十",
        )
        return EpubBook(
            title = "Book",
            chapters = listOf(chapter),
            toc = toc,
            bookInfo = BookInfo(
                characterCount = 10,
                chapterInfo = mapOf(
                    chapter.href to BookInfo.ChapterInfo(
                        spineIndex = 0,
                        currentTotal = 0,
                        chapterCount = 10,
                        fragmentOffsets = mapOf("part-2" to 4, "part-3" to 8),
                    ),
                ),
                images = emptyList(),
            ),
        )
    }
}
