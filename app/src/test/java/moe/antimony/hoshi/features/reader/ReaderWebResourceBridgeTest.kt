package moe.antimony.hoshi.features.reader

import java.io.File
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderWebResourceBridgeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun servesSanitizedEpubCssWithUtf8EncodingOnlyForAppAssetsHost() {
        val bridge = ReaderWebResourceBridge(
            book = bookWithResource(
                path = "styles/main.css",
                mediaType = "text/css",
                bytes = "-epub-line-break: strict;".toByteArray(),
            ),
            fontFileForRequest = { null },
        )

        val resource = bridge.resourceForUrl("https://appassets.androidplatform.net/epub/styles/main.css")

        assertEquals("text/css", resource?.mediaType)
        assertEquals("UTF-8", resource?.encoding)
        assertTrue(resource!!.data.decodeToString().contains("line-break: strict;"))
        assertNull(bridge.resourceForUrl("https://example.com/epub/styles/main.css"))
    }

    @Test
    fun servesDecodedFontRequestsThroughFontProvider() {
        val fontFile = temporaryFolder.newFile("Klee One.ttf")
        fontFile.writeBytes(byteArrayOf(1, 2, 3))
        val bridge = ReaderWebResourceBridge(
            book = bookWithResource("chapter.xhtml", "application/xhtml+xml", ByteArray(0)),
            fontFileForRequest = { fileName -> if (fileName == "Klee One.ttf") fontFile else null },
        )

        val resource = bridge.resourceForUrl("https://appassets.androidplatform.net/fonts/Klee%20One.ttf")

        assertEquals("font/ttf", resource?.mediaType)
        assertEquals(null, resource?.encoding)
        assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), resource?.data?.toList())
    }

    @Test
    fun servesEpubHtmlWithSingleEarlyViewportMeta() {
        val bridge = ReaderWebResourceBridge(
            book = bookWithResource(
                path = "chapter.xhtml",
                mediaType = "application/xhtml+xml",
                bytes = """
                    <html>
                    <head><meta name="viewport" content="width=320"></head>
                    <body><p>Reader text</p></body>
                    </html>
                """.trimIndent().toByteArray(),
            ),
            fontFileForRequest = { null },
        )

        val resource = bridge.resourceForUrl("https://appassets.androidplatform.net/epub/chapter.xhtml")
        val html = resource!!.data.decodeToString()

        assertEquals("application/xhtml+xml", resource.mediaType)
        assertEquals("UTF-8", resource.encoding)
        assertEquals(1, Regex("""<meta\s+name=["']viewport["']""").findAll(html).count())
        assertTrue(html.contains("width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"))
        assertTrue(html.contains("""user-scalable=no" />"""))
        assertTrue(html.contains("<p>Reader text</p>"))
    }

    @Test
    fun returnsNotFoundForMissingLocalResources() {
        val bridge = ReaderWebResourceBridge(
            book = bookWithResource("chapter.xhtml", "application/xhtml+xml", ByteArray(0)),
            fontFileForRequest = { null },
        )

        val missingEpub = bridge.resourceForUrl("https://appassets.androidplatform.net/epub/missing.css")
        val missingFont = bridge.resourceForUrl("https://appassets.androidplatform.net/fonts/Missing.ttf")

        assertNotNull(missingEpub)
        assertNotNull(missingFont)
        assertEquals(404, missingEpub!!.statusCode)
        assertEquals("Not Found", missingEpub.reasonPhrase)
        assertEquals(404, missingFont!!.statusCode)
        assertEquals("Not Found", missingFont.reasonPhrase)
        assertNull(bridge.resourceForUrl("not a url"))
    }

    @Test
    fun resolvesOnlyLocalEpubImageResourcesForFullscreenViewer() {
        val bridge = ReaderWebResourceBridge(
            book = EpubBook(
                title = "Book",
                chapters = emptyList(),
                resources = mapOf(
                    "OPS/images/cover.jpg" to EpubResource("image/jpeg", byteArrayOf(1, 2, 3)),
                    "OPS/chapter.xhtml" to EpubResource("application/xhtml+xml", "<p>Text</p>".toByteArray()),
                ),
            ),
            fontFileForRequest = { null },
        )

        val image = bridge.imageResourceForUrl("https://appassets.androidplatform.net/epub/OPS/images/cover.jpg")

        assertEquals("image/jpeg", image?.mediaType)
        assertEquals(null, image?.encoding)
        assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), image?.data?.toList())
        assertNull(bridge.imageResourceForUrl("https://appassets.androidplatform.net/epub/OPS/chapter.xhtml"))
        assertNull(bridge.imageResourceForUrl("https://example.com/epub/OPS/images/cover.jpg"))
        assertNull(bridge.imageResourceForUrl("not a url"))
    }

    @Test
    fun normalizesRelativeSegmentsInEpubResourceRequests() {
        val bridge = ReaderWebResourceBridge(
            book = EpubBook(
                title = "Book",
                chapters = emptyList(),
                resources = mapOf(
                    "item/image/cover.jpg" to EpubResource("image/jpeg", byteArrayOf(4, 5, 6)),
                    "item/stylesheet.css" to EpubResource("text/css", "body {}".toByteArray()),
                ),
            ),
            fontFileForRequest = { null },
        )

        val image = bridge.resourceForUrl("https://appassets.androidplatform.net/epub/item/xhtml/../image/cover.jpg")
        val css = bridge.resourceForUrl("https://appassets.androidplatform.net/epub/item/xhtml/../stylesheet.css")
        val fullscreenImage = bridge.imageResourceForUrl("https://appassets.androidplatform.net/epub/item/xhtml/../image/cover.jpg")

        assertEquals("image/jpeg", image?.mediaType)
        assertEquals(listOf(4.toByte(), 5.toByte(), 6.toByte()), image?.data?.toList())
        assertEquals("text/css", css?.mediaType)
        assertEquals("image/jpeg", fullscreenImage?.mediaType)
    }

    private fun bookWithResource(
        path: String,
        mediaType: String,
        bytes: ByteArray,
    ): EpubBook =
        EpubBook(
            title = "Book",
            chapters = emptyList(),
            resources = mapOf(path to EpubResource(mediaType = mediaType, bytes = bytes)),
        )
}
