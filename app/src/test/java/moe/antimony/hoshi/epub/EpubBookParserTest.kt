package moe.antimony.hoshi.epub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EpubBookParserTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parsesExtractedEpubUsingContainerManifestAndSpineLikePoc() {
        val root = tempFolder.newFolder("book")
        writeExtractedEpub(root)

        val book = EpubBookParser().parse(root)

        assertEquals("Sample Book", book.title)
        assertEquals(
            listOf("OPS/text/chapter-2.xhtml", "OPS/text/chapter-1.xhtml"),
            book.chapters.map { it.href },
        )
        assertEquals("chapter-2", book.chapters.first().id)
        assertEquals("application/xhtml+xml", book.chapters.first().mediaType)
        assertEquals("<html><body>Second</body></html>", book.chapters.first().html)
        assertEquals("OPS/images/cover.jpg", book.coverHref)
        assertArrayEquals("body {}".toByteArray(), book.readResource("/OPS/styles/book.css"))
        assertEquals("text/css", book.mediaType("OPS/styles/book.css"))
        assertEquals("image/jpeg", book.mediaType("OPS/images/cover.jpg"))
    }

    private fun writeExtractedEpub(root: File) {
        root.resolve("META-INF").mkdirs()
        root.resolve("META-INF/container.xml").writeText(
            """
            <?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
            """.trimIndent(),
        )

        root.resolve("OPS/text").mkdirs()
        root.resolve("OPS/styles").mkdirs()
        root.resolve("OPS/images").mkdirs()
        root.resolve("OPS/text/chapter-1.xhtml").writeText("<html><body>First</body></html>")
        root.resolve("OPS/text/chapter-2.xhtml").writeText("<html><body>Second</body></html>")
        root.resolve("OPS/styles/book.css").writeText("body {}")
        root.resolve("OPS/images/cover.jpg").writeBytes(byteArrayOf(1, 2, 3))
        root.resolve("OPS/package.opf").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Sample Book</dc:title>
              </metadata>
              <manifest>
                <item id="chapter-1" href="text/chapter-1.xhtml" media-type="application/xhtml+xml"/>
                <item id="chapter-2" href="text/chapter-2.xhtml" media-type="application/xhtml+xml"/>
                <item id="style" href="styles/book.css" media-type="text/css"/>
                <item id="cover" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
              </manifest>
              <spine page-progression-direction="rtl">
                <itemref idref="chapter-2"/>
                <itemref idref="chapter-1"/>
              </spine>
            </package>
            """.trimIndent(),
        )
    }
}
