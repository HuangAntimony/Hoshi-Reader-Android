package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import org.junit.Assert.*
import org.junit.Test

class ReaderPageIndexTest {
    private val book = EpubBook("Book", chapters = listOf(
        EpubChapter("a", "a.xhtml", "application/xhtml+xml", ""),
        EpubChapter("b", "b.xhtml", "application/xhtml+xml", ""),
        EpubChapter("c", "c.xhtml", "application/xhtml+xml", ""),
    ), bookInfo = BookInfo(characterCount = 200, chapterInfo = mapOf(
        "a.xhtml" to BookInfo.ChapterInfo(0, 0, 100),
        "b.xhtml" to BookInfo.ChapterInfo(1, 100, 0),
        "c.xhtml" to BookInfo.ChapterInfo(2, 100, 100),
    )))

    @Test fun firstTextAfterLeadingImagePagesUsesTheTextPage() {
        val index = requireNotNull(ReaderPageIndex.create(book,
            listOf(listOf(0, 0, 0, 40), listOf(0, 0), listOf(0, 0, 50))))
        assertEquals(3, index.pageAt(0, 0))
        assertEquals(8, index.pageAt(100, 2))
        assertEquals(8, index.pageAt(100))
        assertEquals(1, index.pageAt(0, 0, preferSpineStart = true))
        assertEquals(7, index.pageAt(100, 2, preferSpineStart = true))
    }

    @Test fun chapterNavigationUsesFirstAndLastPhysicalPagesBeforeWebViewReports() {
        val index = requireNotNull(ReaderPageIndex.create(book,
            listOf(listOf(0, 0, 40, 100), listOf(0, 0), listOf(0, 0, 50))))
        assertEquals(1, index.progress(ReaderChapterPosition(0, 0.0), null, ReaderTocRange(0, 100)).current)
        assertEquals(6, index.progress(ReaderChapterPosition(1, 1.0), null, ReaderTocRange(100, 200)).current)
        assertEquals(7, index.progress(ReaderChapterPosition(2, 0.0), null, ReaderTocRange(100, 200)).current)
        assertEquals(8, index.progress(ReaderChapterPosition(2, 0.0), 1, ReaderTocRange(100, 200)).current)
    }

    @Test fun knownPageProgressStaysVisibleDuringChapterRestore() {
        val index = requireNotNull(ReaderPageIndex.create(book,
            listOf(listOf(0, 40, 80), listOf(0, 0), listOf(0, 50))))
        val settings = ReaderSettings(progressCount = ReaderProgressCount.Pages)
        val holder = ReaderWebViewStateHolder(settings, ReaderChapterPosition(0, 0.8))
        holder.markWebViewRestored()
        assertEquals(3, holder.pageProgress(index, 2)?.current)
        holder.goToNextChapter(book.chapters.lastIndex)
        assertTrue(holder.isWebViewRestoring)
        assertEquals(4, holder.pageProgress(index, null)?.current)
        assertEquals(7, holder.pageProgress(index, null)?.total)
        holder.markWebViewRestored()
        assertEquals(5, holder.pageProgress(index, 1)?.current)
        holder.goToNextChapter(book.chapters.lastIndex)
        assertEquals(6, holder.pageProgress(index, null)?.current)
        holder.markWebViewRestored()
        holder.goToPreviousChapter()
        assertEquals(5, holder.pageProgress(index, null)?.current)
        assertNull(holder.pageProgress(null, null))
        holder.syncSettings(settings.copy(progressCount = ReaderProgressCount.Off))
        assertNull(holder.pageProgress(index, null))
        holder.syncSettings(settings.copy(viewMode = ReaderViewMode.Continuous))
        assertNull(holder.pageProgress(index, null))
    }

    @Test fun imagesKeepSeparatePagesAndSpineIdentityResolvesRepeatedOffsets() {
        val index = requireNotNull(ReaderPageIndex.create(book, listOf(listOf(0, 40, 40, 80), listOf(0, 0), listOf(0, 50))))
        assertEquals(8, index.totalPages)
        assertEquals(3, index.pageAt(40, 0))
        assertEquals(5, index.pageAt(100, 1, preferSpineStart = true))
        assertEquals(7, index.pageAt(100, 2))
        assertEquals(8, index.pageAt(200, 2))
        assertEquals(ReaderPageProgress(6, 8, 2, 4), index.progress(ReaderChapterPosition(1, 0.0), 1, ReaderTocRange(100, 200)))
        assertEquals(ReaderPageProgress(2, 8, 2, 4), index.progress(ReaderChapterPosition(0, 0.4), 1, ReaderTocRange(0, 100)))
        assertEquals(ReaderPageProgress(7, 8, 6, 6), index.progress(ReaderChapterPosition(2, 0.25), 0, ReaderTocRange(40, 150)))
        assertEquals(ReaderPageProgress(3, 8, 3, 4), index.progress(ReaderChapterPosition(0, 0.4), null, ReaderTocRange(0, 100)))
        assertEquals("7 (62.50%)", ReaderProgressDisplay.word().countWithPercentText(125, 200, index.pageAt(125, 2).toString()))
    }

    @Test fun incompleteOrInvalidMeasurementsCannotBecomeTotals() {
        assertNull(ReaderPageIndex.create(book, listOf(listOf(0))))
        for (invalid in listOf(emptyList(), listOf(-1), listOf(40, 20), listOf(101))) {
            assertNull(ReaderPageIndex.create(book, listOf(invalid, listOf(0), listOf(0))))
        }
    }
}
