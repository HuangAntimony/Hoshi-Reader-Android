package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderInternalLinkTest {
    private val book = EpubBook(
        title = "Internal Links",
        chapters = listOf(
            EpubChapter(
                id = "toc",
                href = "OPS/nav.xhtml",
                mediaType = "application/xhtml+xml",
                html = "<html><body>目次</body></html>",
            ),
            EpubChapter(
                id = "chapter-1",
                href = "OPS/chapter-1.xhtml",
                mediaType = "application/xhtml+xml",
                html = "<html><body>第一章</body></html>",
            ),
        ),
    )

    @Test
    fun resolvesHoshiEpubUrlToChapterAndFragment() {
        val target = book.resolveInternalReaderLink(
            "https://appassets.androidplatform.net/epub/OPS/chapter-1.xhtml#toc-001",
        )

        assertEquals(ReaderChapterPosition(index = 1, progress = 0.0), target?.position)
        assertEquals("toc-001", target?.fragment)
    }

    @Test
    fun rejectsExternalLinksSoTheyDoNotMutateReaderState() {
        assertNull(book.resolveInternalReaderLink("https://example.com/OPS/chapter-1.xhtml#toc-001"))
    }

}
