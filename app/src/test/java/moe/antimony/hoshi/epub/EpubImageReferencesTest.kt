package moe.antimony.hoshi.epub

import org.junit.Assert.*
import org.junit.Test

class EpubImageReferencesTest {
    private fun chapter(href: String, body: String) = EpubChapter(href, href, "application/xhtml+xml", "<html><body>$body</body></html>")

    @Test fun publisherBaseUrlCannotMistakeDifferentArtworkForARepeatedImage() {
        val book = EpubBook("test", chapters = listOf(chapter("a.xhtml", "<img src='divider.jpg'/><img src='divider.jpg'/><img src='divider.jpg'/>")))
        val document = org.jsoup.Jsoup.parse("<html><head><base href='art/'/></head><body><img src='divider.jpg'/></body></html>")
        assertTrue(book.shouldHoldImage("a.xhtml", document.selectFirst("img")!!))
    }

    @Test fun responsiveCandidatesCountOncePerImageOccurrenceAndPreserveUniqueAlternatives() {
        val book = EpubBook("test", chapters = listOf(chapter("a.xhtml", (1..3).joinToString("") { index ->
            "<picture><source srcset='divider.webp 1x, divider.webp 2x'/><img src='divider.jpg' srcset='art$index.jpg 2x'/></picture>"
        })))
        assertEquals(3, book.imageReferenceCounts["divider.webp"])
        assertEquals(3, book.imageReferenceCounts["divider.jpg"])
        assertEquals(1, book.imageReferenceCounts["art1.jpg"])
        val mixed = org.jsoup.Jsoup.parse("<img src='divider.jpg' srcset='art1.jpg 2x'/>").selectFirst("img")!!
        assertTrue(book.shouldHoldImage("a.xhtml", mixed))
    }

    @Test fun countsOriginalReferencesAcrossDistinctReadableDocuments() {
        val first = chapter("Text/a.xhtml", """<img src="../Images/divider.jpg"/><img src="../Images/twice.jpg"/><img src="../Images/once.jpg"/>""")
        val book = EpubBook("test", chapters = listOf(
            first, first.copy(id = "duplicate-spine-entry"),
            chapter("Text/nested/b.xhtml", """<svg><image href="../../Images/divider.jpg"/></svg><img src="../../Images/twice.jpg"/>"""),
            chapter("Text/c.xhtml", """<picture><source srcset="../Images/divider.jpg"/><img src="../Images/divider.jpg"/></picture>"""),
            chapter("nav.xhtml", """<img src="Images/twice.jpg"/>""").copy(properties = "nav"),
            chapter("extra.xhtml", """<img src="Images/once.jpg"/>""").copy(linear = false),
        ))
        assertEquals(mapOf("Images/divider.jpg" to 3, "Images/twice.jpg" to 2, "Images/once.jpg" to 1), book.imageReferenceCounts)
    }

    @Test fun resolvesEncodedPathsAndCountsEachOccurrenceWithinOneDocument() {
        val book = EpubBook("test", chapters = listOf(chapter("Text/a.xhtml", """
            <!-- <img src="../Images/fake.jpg"/> -->
            <img src="../Images/a%20b%2B.jpg?x=1#part"/>
            <img src="../Images/a b+.jpg"/>
            <svg><image xlink:href="../Images/a%20b+.jpg"/></svg>
            <img src="https://other.example/Images/a%20b+.jpg"/>
        """)))
        assertEquals(mapOf("Images/a b+.jpg" to 3), book.imageReferenceCounts)
    }

    @Test fun readsLazyChapterResourcesAndCachesOnlyForThatBookSnapshot() {
        val source = "<html><body><img src='a.jpg'/><img src='a.jpg'/><img src='a.jpg'/></body></html>"
        val book = EpubBook("test", chapters = listOf(chapter("chapter.xhtml", "").copy(html = "")),
            resources = mapOf("chapter.xhtml" to EpubResource("application/xhtml+xml", source.toByteArray())))
        assertEquals(3, book.imageReferenceCounts["a.jpg"])
        assertSame(book.imageReferenceCounts, book.imageReferenceCounts)
        assertTrue(book.copy(chapters = listOf(chapter("chapter.xhtml", "<img src='b.jpg'/>"))).imageReferenceCounts["a.jpg"] == null)
    }
}
