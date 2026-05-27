package moe.antimony.hoshi.epub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubBookModelTest {
    @Test
    fun exposesOnlyReaderResourcesNeededByWebView() {
        val css = "body {}".toByteArray()
        val book = EpubBook(
            title = "Title",
            chapters = listOf(
                EpubChapter(
                    id = "reading-order-0",
                    href = "item/xhtml/p-001.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html></html>",
                ),
            ),
            resources = mapOf("item/style/book.css" to EpubResource("text/css", css)),
        )

        assertEquals("Title", book.title)
        assertEquals("item/xhtml/p-001.xhtml", book.chapters.single().href)
        assertArrayEquals(css, book.readResource("/item/style/book.css"))
        assertEquals("text/css", book.mediaType("item/style/book.css"))
        assertNull(book.readResource("missing.css"))
    }

    @Test
    fun bookInfoCountsFilteredChapterCharactersLikeIos() {
        val book = EpubBook(
            title = "Title",
            chapters = listOf(
                EpubChapter(
                    id = "a",
                    href = "item/xhtml/a.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html><body><ruby>漢<rt>かん</rt></ruby>字<script>bad</script> A&nbsp;!</body></html>",
                ),
                EpubChapter(
                    id = "b",
                    href = "item/xhtml/b.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html><body>猫と犬</body></html>",
                ),
            ),
        )

        assertEquals(6, book.bookInfo.characterCount)
        assertEquals(
            BookInfo.ChapterInfo(spineIndex = 0, currentTotal = 0, chapterCount = 3),
            book.bookInfo.chapterInfo.getValue("item/xhtml/a.xhtml"),
        )
        assertEquals(
            BookInfo.ChapterInfo(spineIndex = 1, currentTotal = 3, chapterCount = 3),
            book.bookInfo.chapterInfo.getValue("item/xhtml/b.xhtml"),
        )
        assertEquals(4, book.characterCountAt(chapterIndex = 1, progress = 0.5))
    }

    @Test
    fun bookInfoMatchesFilteredChapterOrderWhenRawSpineSkipsEntries() {
        val bookInfo = BookInfo(
            characterCount = 6,
            chapterInfo = mapOf(
                "item/xhtml/a.xhtml" to BookInfo.ChapterInfo(spineIndex = 0, currentTotal = 0, chapterCount = 3),
                "item/xhtml/b.xhtml" to BookInfo.ChapterInfo(spineIndex = 1, currentTotal = 3, chapterCount = 3),
            ),
        )
        val chapterShells = listOf(
            EpubChapter(
                id = "a",
                href = "item/xhtml/a.xhtml",
                mediaType = "application/xhtml+xml",
                html = "",
                spineIndex = 1,
            ),
            EpubChapter(
                id = "b",
                href = "item/xhtml/b.xhtml",
                mediaType = "application/xhtml+xml",
                html = "",
                spineIndex = 3,
            ),
        )

        assertTrue(bookInfo.matchesChapterShells(chapterShells))
    }
}
