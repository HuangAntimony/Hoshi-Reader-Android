package moe.antimony.hoshi.epub

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubCoverCandidatesTest {
    @get:Rule val temp = TemporaryFolder()
    private val parser by lazy {
        EpubBookParser(temp.newFolder("cache")) { file -> ImageIO.read(file)?.let { it.width to it.height } }
    }

    @Test fun emptyOrMissingSvgFallsBackToExplicitMetadataCoverIncludingCachedOpening() {
        for (contents in listOf("", null)) {
            val root = fixture()
            if (contents != null) root.resolve("OPS/preferred.svg").writeText(contents)
            val book = parser.parse(root)
            assertEquals("OPS/images/alternate.jpg", book.coverHref)
            assertArrayEquals(root.resolve("OPS/images/alternate.jpg").readBytes(), book.readResource(book.coverHref!!))
            val cached = parser.parse(root, cachedBookInfo = book.bookInfo)
            assertEquals(book.coverHref, cached.coverHref)
            assertSame(book.bookInfo, cached.bookInfo)
        }
    }

    @Test fun brokenBitmapFallsBackToAnotherDeclaredCover() {
        val root = fixture(preferredName = "broken.jpg", preferredType = "image/jpeg")
        root.resolve("OPS/broken.jpg").writeText("not an image")
        assertEquals("OPS/images/alternate.jpg", parser.parse(root).coverHref)
    }

    @Test fun validPreferredBitmapAndSimpleSvgKeepPriorityOverMetadataCover() {
        val bitmap = fixture(preferredName = "preferred.jpg", preferredType = "image/jpeg")
        writeRaster(bitmap.resolve("OPS/preferred.jpg"))
        assertEquals("OPS/preferred.jpg", parser.parse(bitmap).coverHref)
        val svg = fixture()
        writeRaster(svg.resolve("OPS/images/wrapped.jpg"))
        svg.resolve("OPS/preferred.svg").writeText("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 20"><image href="images/wrapped.jpg" width="10" height="20"/></svg>""")
        assertEquals("OPS/images/wrapped.jpg", parser.parse(svg).coverHref)
    }

    @Test fun triesRemainingCoverImageDeclarationsBeforeLegacyMetadata() {
        val root = fixture()
        root.resolve("OPS/preferred.svg").writeText("")
        writeRaster(root.resolve("OPS/images/second.jpg"))
        root.resolve("OPS/package.opf").apply {
            writeText(readText().replace("</manifest>", """<item id="second" href="images/second.jpg" media-type="image/jpeg" properties="cover-image"/></manifest>"""))
        }
        assertEquals("OPS/images/second.jpg", parser.parse(root).coverHref)
    }

    @Test fun keepsDecodablePreferredCoversWithOtherRasterMimeTypes() {
        for ((extension, type) in listOf("jpg" to "image/jpg", "bmp" to "image/bmp")) {
            val root = fixture(preferredName = "preferred.$extension", preferredType = type)
            ImageIO.write(BufferedImage(10, 20, BufferedImage.TYPE_INT_RGB), extension, root.resolve("OPS/preferred.$extension"))
            assertEquals("OPS/preferred.$extension", parser.parse(root).coverHref)
        }
    }

    @Test fun unusableDeclarationsDoNotPromoteAnUnrelatedIllustration() {
        val root = fixture()
        root.resolve("OPS/preferred.svg").writeText("")
        root.resolve("OPS/images/alternate.jpg").writeText("broken JPEG")
        writeRaster(root.resolve("OPS/images/body.jpg"))
        root.resolve("OPS/package.opf").apply {
            writeText(readText().replace("</manifest>", """<item id="illustration" href="images/body.jpg" media-type="image/jpeg"/></manifest>"""))
        }
        assertNull(parser.parse(root).coverHref)
    }

    @Test fun rejectsExternalAndEscapingAlternativePathsBeforeReadingThem() {
        for (href in listOf("../../outside.jpg", "https://example.com/image.jpg", "//example.com/image.jpg")) {
            val root = fixture(alternateHref = href)
            root.resolve("OPS/preferred.svg").writeText("")
            writeRaster(root.parentFile.resolve("outside.jpg"))
            val inspected = mutableListOf<File>()
            val guarded = EpubBookParser(temp.newFolder()) { file -> inspected += file; 10 to 20 }
            assertNull(href, guarded.parse(root).coverHref)
            assertTrue(href, inspected.isEmpty())
        }
    }

    @Test fun complexSvgWithoutUsableAlternativeRemainsUnchanged() {
        val root = fixture()
        root.resolve("OPS/preferred.svg").writeText("""<svg xmlns="http://www.w3.org/2000/svg"><text>Cover</text></svg>""")
        root.resolve("OPS/images/alternate.jpg").writeText("broken JPEG")
        assertEquals("OPS/preferred.svg", parser.parse(root).coverHref)
    }

    private fun fixture(
        preferredName: String = "preferred.svg",
        preferredType: String = "image/svg+xml",
        alternateHref: String = "images/alternate.jpg",
    ): File = temp.newFolder().also { root ->
        writeMinimalExtractedEpub(root)
        writeRaster(root.resolve("OPS/images/alternate.jpg"))
        root.resolve("OPS/package.opf").writeText("""
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="uid">fixture</dc:identifier><dc:title>Cover candidates</dc:title>
                <meta name="cover" content="alternate"/>
              </metadata>
              <manifest>
                <item id="preferred" href="$preferredName" media-type="$preferredType" properties="cover-image"/>
                <item id="alternate" href="$alternateHref" media-type="image/jpeg"/>
                <item id="chapter" href="text/chapter-1.xhtml" media-type="application/xhtml+xml"/>
                <item id="wrapped" href="images/wrapped.jpg" media-type="image/jpeg"/>
              </manifest>
              <spine><itemref idref="chapter"/></spine>
            </package>
        """.trimIndent())
    }

    private fun writeRaster(file: File) {
        file.parentFile.mkdirs()
        ImageIO.write(BufferedImage(10, 20, BufferedImage.TYPE_INT_RGB), "jpg", file)
    }
}
