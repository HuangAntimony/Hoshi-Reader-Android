package moe.antimony.hoshi.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubTocIndexTest {
    @Test
    fun firstIndexBuildDoesNotScanChaptersForEveryTocEntry() {
        val count = 1_000
        val original = book((0 until count).map { "Text/$it.xhtml" })
        val chapters = CountingList(original.chapters)
        val book = original.copy(chapters = chapters)

        assertEquals(count, book.tocChapterStarts.size)
        assertEquals(9_990, book.tocChapterStarts.last())
        assertTrue("Directory construction read ${chapters.reads} chapters", chapters.reads <= count * 3)
    }

    @Test
    fun matchingKeepsTheFirstSpineCandidateEvenBeforeAnExactMatch() {
        val book = book(listOf("one.xhtml", "Text/one.xhtml", "Other/one.xhtml"))

        assertEquals(0, book.tocCharacterStart("Text/one.xhtml"))
        assertEquals(2, book.tocCharacterStart("Other/one.xhtml#part"))
        assertEquals(0, book.tocCharacterStart("Prefix/Text/one.xhtml"))
        val longerFirst = book(listOf("Prefix/Text/one.xhtml", "one.xhtml"))
        assertEquals(0, longerFirst.tocCharacterStart("Text/one.xhtml"))
    }

    @Test
    fun matchingSupportsEitherPathSuffixWithoutMatchingOnlyACommonFilename() {
        val book = book(listOf("Text/one.xhtml", "A/Text/one.xhtml", "Other/one.xhtml"))

        val cases = listOf(
            "one.xhtml" to 0,
            "A/Text/one.xhtml" to 0,
            "Prefix/A/Text/one.xhtml" to 0,
            "Other/one.xhtml" to 20,
            " /Text\\one.xhtml?view=1#part" to 2,
            "Text/one.xhtml#missing" to 0,
        )
        for ((href, expected) in cases) assertEquals(href, expected, book.tocCharacterStart(href))
        assertNull(book.tocCharacterStart("Elsewhere/one.xhtml"))
        assertNull(book.tocCharacterStart("text/one.xhtml"))
        assertNull(book.tocCharacterStart("#part"))
    }

    @Test
    fun normalizedDuplicatePathsKeepTheFirstChapter() {
        val book = book(listOf(" /Text\\one.xhtml#old", "Text/one.xhtml"))
        assertEquals(2, book.tocCharacterStart("Text/one.xhtml#part"))
    }

    private fun book(paths: List<String>): EpubBook = EpubBook(
        title = "Book",
        chapters = paths.mapIndexed { index, href ->
            EpubChapter(index.toString(), href, "application/xhtml+xml", "")
        },
        toc = paths.map { EpubTocItem(it, it) },
        bookInfo = BookInfo(
            characterCount = paths.size * 10,
            chapterInfo = paths.mapIndexed { index, href ->
                href to BookInfo.ChapterInfo(index, index * 10, 10, mapOf("part" to 2))
            }.toMap(),
        ),
    )

    private class CountingList<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
            private set
        override val size: Int get() = values.size
        override fun get(index: Int): T = values[index].also { reads++ }
    }
}
