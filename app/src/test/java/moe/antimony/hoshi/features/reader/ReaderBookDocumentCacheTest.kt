package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.EpubBook
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderBookDocumentCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private val book = EpubBook("Book", chapters = emptyList(), packedExtractionKey = "first extraction")

    @Test fun reusesMatchingHtmlAndCssAndRebuildsAfterSourceReplacement() {
        val cache = ReaderBookDocumentCache(temp.root)
        val first = ReaderBookDocument("original".toByteArray(), "first styles".toByteArray())
        val replacement = ReaderBookDocument("revised".toByteArray(), "new styles".toByteArray())
        cache.document(book) { first }
        val reopened = cache.document(book.copy()) { error("Unchanged book was rebuilt") }
        assertArrayEquals(first.html, reopened.html)
        assertArrayEquals(first.css, reopened.css)
        val revised = book.copy(packedExtractionKey = "replacement extraction")
        cache.document(revised) { replacement }
        val hit = cache.document(revised) { error("Revised book was rebuilt") }
        assertArrayEquals(replacement.html, hit.html)
        assertArrayEquals(replacement.css, hit.css)
    }

    @Test fun looseSourcesAlwaysBuildFromCurrentContent() {
        val loose = book.copy(packedExtractionKey = null)
        val cache = ReaderBookDocumentCache(temp.root)
        var builds = 0
        repeat(2) {
            cache.document(loose) {
                builds++
                loose.buildReaderBookDocument()
            }
        }
        assertEquals(2, builds)
    }
}
