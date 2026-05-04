package moe.antimony.hoshi.features.reader

import java.io.File
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderWebResourceBridgeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun servesSanitizedEpubCssWithUtf8EncodingOnlyForLocalHost() {
        val bridge = ReaderWebResourceBridge(
            book = bookWithResource(
                path = "styles/main.css",
                mediaType = "text/css",
                bytes = "-epub-line-break: strict;".toByteArray(),
            ),
            fontFileForRequest = { null },
        )

        val resource = bridge.resourceForUrl("https://hoshi.local/epub/styles/main.css")

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

        val resource = bridge.resourceForUrl("https://hoshi.local/fonts/Klee%20One.ttf")

        assertEquals("font/ttf", resource?.mediaType)
        assertEquals(null, resource?.encoding)
        assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), resource?.data?.toList())
    }

    @Test
    fun rejectsMissingAndMalformedResources() {
        val bridge = ReaderWebResourceBridge(
            book = bookWithResource("chapter.xhtml", "application/xhtml+xml", ByteArray(0)),
            fontFileForRequest = { null },
        )

        assertNull(bridge.resourceForUrl("not a url"))
        assertNull(bridge.resourceForUrl("https://hoshi.local/epub/missing.css"))
        assertNull(bridge.resourceForUrl("https://hoshi.local/fonts/Missing.ttf"))
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
