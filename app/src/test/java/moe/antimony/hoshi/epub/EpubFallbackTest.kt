package moe.antimony.hoshi.epub

import java.io.File
import moe.antimony.hoshi.features.reader.resolveInternalReaderLink
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubFallbackTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun resolvesFallbackInSpineOrderAndUsesItsContentAndResourceBase() {
        val root = fixture()
        val book = EpubBookParser().parse(root)
        assertEquals(listOf("OPS/text/front.xhtml", "OPS/text/chapter-1.xhtml", "OPS/text/chapter-2.xhtml"), book.chapters.map { it.href })
        assertEquals(listOf(0, 1, 2), book.chapters.map { it.spineIndex })
        assertFalse(book.chapters.first().linear)
        assertTrue(book.chapters.first().html.contains("../images/cover.jpg"))
        assertEquals(listOf("OPS/images/cover.jpg"), book.bookInfo.images)
        assertEquals(11, book.bookInfo.characterCount)
        for (path in listOf("OPS/front.svg", "OPS/text/front.xhtml")) {
            assertEquals(0, book.resolveInternalReaderLink("https://appassets.androidplatform.net/epub/$path#art")?.position?.index)
        }
        assertEquals("OPS/text/front.xhtml#art", book.toc.single().href)
        assertEquals(0, book.bookInfo.chapterInfo.getValue("OPS/text/front.xhtml").fragmentOffsets?.get("art"))
        val warm = EpubBookParser().parse(root, cachedBookInfo = book.bookInfo)
        assertSame(book.bookInfo, warm.bookInfo)
        assertTrue(warm.chapters.all { it.html.isEmpty() })
        assertTrue(warm.readResource(warm.chapters.first().href)!!.decodeToString().contains("../images/cover.jpg"))
    }

    @Test fun resolvesMultipleFallbacksAndStopsAtCyclesOrMissingTargets() {
        val root = fixture()
        val opf = root.resolve("OPS/package.opf")
        val original = opf.readText()
        opf.writeText(original.replace("fallback=\"front-html\"", "fallback=\"middle\"").replace("</manifest>", "<item id=\"middle\" href=\"middle.bin\" media-type=\"application/octet-stream\" fallback=\"front-html\"/></manifest>"))
        assertEquals(3, EpubBookParser().parse(root).chapters.size)
        for (fallback in listOf("front", "missing", "")) {
            opf.writeText(original.replace("fallback=\"front-html\"", "fallback=\"$fallback\""))
            assertEquals(2, EpubBookParser().parse(root).chapters.size)
        }
        opf.writeText(original.replace("fallback=\"front-html\"", "fallback=\"middle\"").replace("</manifest>", "<item id=\"middle\" href=\"middle.bin\" media-type=\"application/octet-stream\" fallback=\"front\"/></manifest>"))
        assertEquals(2, EpubBookParser().parse(root).chapters.size)
    }

    @Test fun oldFilteredCacheIsRebuiltWithoutChangingTextCount() {
        val root = fixture()
        val opf = root.resolve("OPS/package.opf")
        val complete = opf.readText()
        opf.writeText(complete.replace("fallback=\"front-html\"", ""))
        val old = EpubBookParser().parse(root).bookInfo
        opf.writeText(complete)
        val updated = EpubBookParser().parse(root, cachedBookInfo = old)
        assertNotEquals(old, updated.bookInfo)
        assertEquals(old.characterCount, updated.bookInfo.characterCount)
        assertEquals(3, updated.bookInfo.chapterInfo.size)
        assertEquals(listOf("OPS/images/cover.jpg"), updated.bookInfo.images)
    }

    private fun fixture(): File {
        val root = temp.newFolder()
        writeMinimalExtractedEpub(root)
        root.resolve("OPS/front.svg").writeText("<svg xmlns=\"http://www.w3.org/2000/svg\"><image href=\"images/cover.jpg\"/></svg>")
        root.resolve("OPS/text/front.xhtml").writeText("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p id=\"art\"><img src=\"../images/cover.jpg\"/></p></body></html>")
        root.resolve("OPS/nav.xhtml").writeText("""<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="front.svg#art">Cover</a></li></ol></nav></body></html>""")
        root.resolve("OPS/package.opf").apply {
            writeText(readText().replace("</manifest>", """
                <item id="front" href="front.svg" media-type="image/svg+xml" fallback="front-html"/>
                <item id="front-html" href="text/front.xhtml" media-type="application/xhtml+xml"/>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                </manifest>
            """.trimIndent()).replace("<spine>", "<spine><itemref idref=\"front\" linear=\"no\"/>"))
        }
        return root
    }
}
