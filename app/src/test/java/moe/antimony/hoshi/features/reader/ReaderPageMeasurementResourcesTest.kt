package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubResource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderPageMeasurementResourcesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cachesStaticResourcesOnlyInsideOneMeasurementSnapshot() {
        val font = temporary.newFile("font.ttf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val book = EpubBook("fixture", chapters = emptyList(), resources = mapOf(
            "章 1.xhtml" to EpubResource("application/xhtml+xml", "<html/>".toByteArray()),
            "style.css" to EpubResource("text/css", "-epub-line-break: strict;".toByteArray()),
            "image.png" to EpubResource("image/png", byteArrayOf(4, 5)),
        ))
        val first = ReaderPageMeasurementResources(book) { if (it == "font.ttf") font else null }
        val second = ReaderPageMeasurementResources(book) { null }
        val chapter = first.url("/epub/章 1.xhtml")
        assertTrue(chapter.endsWith("/%E7%AB%A0%201.xhtml"))
        assertEquals("<html/>", first.resourceForUrl(chapter)!!.data.decodeToString())
        assertTrue(first.resourceForUrl(chapter)!!.headers.isEmpty())
        for (path in listOf("/epub/style.css", "/epub/image.png", "/fonts/font.ttf")) {
            assertEquals("private, max-age=31536000", first.resourceForUrl(first.url(path))!!.headers["Cache-Control"])
            assertEquals(404, second.resourceForUrl(first.url(path))!!.statusCode)
        }
        assertTrue(first.resourceForUrl(first.url("/fonts/missing.ttf"))!!.headers.isEmpty())
        assertEquals(404, first.resourceForUrl(first.url("/epub/../../other/style.css"))!!.statusCode)
    }

    @Test fun preservesVisibleReaderPolicyForUnscopedAndExternalPublisherResources() {
        val book = EpubBook("fixture", chapters = emptyList(), resources = mapOf(
            "style.css" to EpubResource("text/css", "body {}".toByteArray())))
        val resources = ReaderPageMeasurementResources(book) { null }
        assertNull(resources.resourceForUrl("https://publisher.example/style.css"))
        assertNull(resources.resourceForUrl("https://publisher.example/reader-pages/style.css"))
        val absolute = resources.resourceForUrl("https://appassets.androidplatform.net/epub/style.css")!!
        assertEquals(200, absolute.statusCode)
        assertTrue("unscoped URLs must not introduce cross-book caches", absolute.headers.isEmpty())
    }

    @Test fun scopesFontUrlsWithoutChangingFontSelectionAndInvalidatesAfterReplacement() {
        val book = EpubBook("fixture", chapters = emptyList())
        val file = temporary.newFile("font.ttf").apply { writeBytes(byteArrayOf(1)) }
        val original = ReaderFontRenderSpec("family", "bold", "Fixture", "Fixture", 600, false,
            faces = listOf(ReaderFontFace("https://appassets.androidplatform.net/fonts/font.ttf?v=1", 600, false)))
        val first = ReaderPageMeasurementResources(book) { file }
        val spec = first.font(original)!!
        assertEquals(original.copy(faces = spec.faces), spec)
        assertTrue(spec.faces.single().url.endsWith("/fonts/font.ttf?v=1"))
        assertEquals(listOf(1.toByte()), first.resourceForUrl(spec.faces.single().url)!!.data.toList())
        file.writeBytes(byteArrayOf(2))
        val second = ReaderPageMeasurementResources(book) { file }
        val next = second.font(original)!!
        assertNotEquals(spec.faces.single().url, next.faces.single().url)
        assertEquals(listOf(2.toByte()), second.resourceForUrl(next.faces.single().url)!!.data.toList())
    }
}
