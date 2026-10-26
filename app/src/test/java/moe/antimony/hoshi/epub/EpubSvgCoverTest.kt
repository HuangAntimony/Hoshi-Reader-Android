package moe.antimony.hoshi.epub

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubSvgCoverTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun extractsSingleFullCanvasRasterWithEitherHrefSyntax() {
        for (attribute in listOf("href", "xlink:href")) {
            assertEquals("OPS/images/cover.jpg", cover("<$image $attribute=\"images/cover.jpg\"/>"))
        }
    }

    @Test fun detectsSvgByManifestTypeAndRejectsAbsoluteResourceUrls() {
        assertEquals("OPS/images/cover.jpg", cover("<$image href=\"images/cover.jpg\"/>", svgName = "artwork"))
        assertEquals("OPS/cover.svg", cover("<$image href=\"https://appassets.androidplatform.net/epub/OPS/images/cover.jpg\"/>"))
        assertEquals("OPS/cover.svg", cover("<$image href=\"//appassets.androidplatform.net/epub/OPS/images/cover.jpg\"/>"))
    }

    @Test fun keepsComplexUnsafeOrIncompleteSvgCoversUnchanged() {
        for (content in listOf(
            "<$image href=\"images/cover.jpg\"/><text>Title</text>",
            "<$image href=\"images/cover.jpg\"/><rect width=\"100\" height=\"100\"/>",
            "<$image href=\"images/cover.jpg\" transform=\"rotate(20)\"/>",
            "<$image href=\"images/cover.jpg\" filter=\"url(#blur)\"/>",
            "<$image href=\"images/cover.jpg\" clip-path=\"url(#clip)\"/>",
            "<$image href=\"images/cover.jpg\" opacity=\"0.5\"/>",
            "<$image href=\"images/cover.jpg\" style=\"display:none\"/>",
            "<$image href=\"images/cover.jpg\"/><$image href=\"images/cover.jpg\"/>",
            "<image href=\"images/cover.jpg\" x=\"10\" y=\"0\" width=\"100\" height=\"200\"/>",
            "<$image href=\"https://example.com/cover.jpg\"/>",
            "<$image href=\"images/cover.jpg#xywh=0,0,20,20\"/>",
            "<$image href=\"images/cover.jpg?variant=cropped\"/>",
            "<$image href=\"../../outside.jpg\"/>",
            "<$image href=\"images/missing.jpg\"/>",
        )) assertEquals(content, "OPS/cover.svg", cover(content))
    }

    @Test fun keepsStyledOrCroppedRootUnchanged() {
        for (attribute in listOf("style=\"opacity:0.5\"", "preserveAspectRatio=\"xMidYMid slice\"", "transform=\"scale(2)\"")) {
            assertEquals("OPS/cover.svg", cover("<$image href=\"images/cover.jpg\"/>", attribute))
        }
    }

    @Test fun keepsLetterboxedOrUncertainCanvasGeometryUnchanged() {
        assertEquals("OPS/cover.svg", cover("<$image href=\"images/cover.jpg\"/>", rasterWidth = 200, rasterHeight = 100))
        for (attributes in listOf("width=\"200\" height=\"100\"", "width=\"0\" height=\"200\"", "width=\"100%\" height=\"200\"")) {
            assertEquals(attributes, "OPS/cover.svg", cover("<$image href=\"images/cover.jpg\"/>", attributes))
        }
        assertEquals("OPS/images/cover.jpg", cover("<$image href=\"images/cover.jpg\"/>", "width=\"200px\" height=\"400px\""))
    }

    private fun cover(content: String, rootAttributes: String = "", svgName: String = "cover.svg", rasterWidth: Int = 100, rasterHeight: Int = 200): String? {
        val root = temp.newFolder()
        writeMinimalExtractedEpub(root)
        ImageIO.write(BufferedImage(rasterWidth, rasterHeight, BufferedImage.TYPE_INT_RGB), "jpg", root.resolve("OPS/images/cover.jpg"))
        root.parentFile.resolve("outside.jpg").writeBytes(byteArrayOf(1, 2, 3))
        root.resolve("OPS/$svgName").writeText("""<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 100 200" $rootAttributes>$content</svg>""")
        root.resolve("OPS/package.opf").apply {
            writeText(readText().replace("href=\"images/cover.jpg\" media-type=\"image/jpeg\"", "href=\"$svgName\" media-type=\"image/svg+xml\""))
        }
        return EpubBookParser(temp.root) { file -> ImageIO.read(file)?.let { it.width to it.height } }.parse(root).coverHref
    }

    private val image = "image x=\"0\" y=\"0\" width=\"100\" height=\"200\""
}
