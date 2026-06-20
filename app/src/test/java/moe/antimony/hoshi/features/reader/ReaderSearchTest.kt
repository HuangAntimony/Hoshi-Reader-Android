package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.EpubResource
import moe.antimony.hoshi.epub.EpubTocItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSearchTest {
    @Test
    fun searchReadsChapterResourcesWhenChapterHtmlIsEmptyAndIgnoresRuby() {
        val book = searchBook(
            chapters = listOf(
                chapter("c0.xhtml", ""),
                chapter("c1.xhtml", ""),
            ),
            resources = mapOf(
                "c0.xhtml" to xhtml("<html><body><ruby>漢<rt>かん</rt></ruby>字 A<script>bad</script></body></html>"),
                "c1.xhtml" to xhtml("<html><body>猫と犬</body></html>"),
            ),
            bookInfo = BookInfo(
                characterCount = 6,
                chapterInfo = mapOf(
                    "c0.xhtml" to BookInfo.ChapterInfo(spineIndex = 0, currentTotal = 0, chapterCount = 3),
                    "c1.xhtml" to BookInfo.ChapterInfo(spineIndex = 1, currentTotal = 3, chapterCount = 3),
                ),
            ),
        )

        val results = ReaderSearchEngine(book).search("漢字A")

        assertEquals(1, results.size)
        assertEquals(0, results.single().chapterIndex)
        assertEquals(0, results.single().character)
        assertTrue(results.single().snippet.contains("漢字A"))
    }

    @Test
    fun searchReturnsWholeBookOffsetsAndChapterLabels() {
        val book = searchBook(
            chapters = listOf(chapter("c0.xhtml", ""), chapter("c1.xhtml", "")),
            resources = mapOf(
                "c0.xhtml" to xhtml("<html><body>猫犬</body></html>"),
                "c1.xhtml" to xhtml("<html><body>鳥猫</body></html>"),
            ),
            toc = listOf(
                EpubTocItem(label = "First", href = "c0.xhtml"),
                EpubTocItem(label = "Second", href = "c1.xhtml"),
            ),
            bookInfo = BookInfo(
                characterCount = 4,
                chapterInfo = mapOf(
                    "c0.xhtml" to BookInfo.ChapterInfo(spineIndex = 0, currentTotal = 0, chapterCount = 2),
                    "c1.xhtml" to BookInfo.ChapterInfo(spineIndex = 1, currentTotal = 2, chapterCount = 2),
                ),
            ),
        )

        val results = ReaderSearchEngine(book).search("猫")

        assertEquals(listOf(0, 3), results.map { it.character })
        assertEquals(listOf("First", "Second"), results.map { it.chapterLabel })
    }

    @Test
    fun latinSearchIsCaseInsensitive() {
        val book = searchBook(
            chapters = listOf(chapter("c0.xhtml", "")),
            resources = mapOf("c0.xhtml" to xhtml("<html><body>CATcat</body></html>")),
            bookInfo = BookInfo(
                characterCount = 6,
                chapterInfo = mapOf("c0.xhtml" to BookInfo.ChapterInfo(spineIndex = 0, currentTotal = 0, chapterCount = 6)),
            ),
        )

        val results = ReaderSearchEngine(book).search("cat")

        assertEquals(listOf(0, 3), results.map { it.character })
    }

    @Test
    fun emptyQueryReturnsNoResults() {
        val book = searchBook(
            chapters = listOf(chapter("c0.xhtml", "")),
            resources = mapOf("c0.xhtml" to xhtml("<html><body>猫</body></html>")),
            bookInfo = BookInfo(
                characterCount = 1,
                chapterInfo = mapOf("c0.xhtml" to BookInfo.ChapterInfo(spineIndex = 0, currentTotal = 0, chapterCount = 1)),
            ),
        )

        assertEquals(emptyList<ReaderSearchResult>(), ReaderSearchEngine(book).search(""))
        assertEquals(emptyList<ReaderSearchResult>(), ReaderSearchEngine(book).search(" ! "))
    }

    @Test
    fun punctuationOnlyQueryHasNoMatchableText() {
        assertEquals(false, readerSearchQueryHasMatchableText(" ! "))
        assertEquals(true, readerSearchQueryHasMatchableText("猫!"))
    }

    @Test
    fun crossChapterBoundaryMatchDoesNotSkipNextChapterResult() {
        val book = searchBook(
            chapters = listOf(chapter("c0.xhtml", ""), chapter("c1.xhtml", "")),
            resources = mapOf(
                "c0.xhtml" to xhtml("<html><body>ab</body></html>"),
                "c1.xhtml" to xhtml("<html><body>bc</body></html>"),
            ),
            bookInfo = BookInfo(
                characterCount = 4,
                chapterInfo = mapOf(
                    "c0.xhtml" to BookInfo.ChapterInfo(spineIndex = 0, currentTotal = 0, chapterCount = 2),
                    "c1.xhtml" to BookInfo.ChapterInfo(spineIndex = 1, currentTotal = 2, chapterCount = 2),
                ),
            ),
        )

        val results = ReaderSearchEngine(book).search("bc")

        assertEquals(listOf(2), results.map { it.character })
        assertEquals(listOf(1), results.map { it.chapterIndex })
    }

    @Test
    fun searchLoadRethrowsCancellation() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                loadReaderSearchResults {
                    throw CancellationException("cancelled")
                }
            }
        }
    }

    @Test
    fun searchLoadConvertsUnexpectedFailureToFailureState() = runBlocking {
        val result = loadReaderSearchResults<List<ReaderSearchResult>> {
            error("broken")
        }

        assertEquals(ReaderSearchLoadResult.Failure, result)
    }

    private fun searchBook(
        chapters: List<EpubChapter>,
        resources: Map<String, EpubResource>,
        bookInfo: BookInfo,
        toc: List<EpubTocItem> = emptyList(),
    ): EpubBook =
        EpubBook(
            title = "Search Book",
            chapters = chapters,
            resources = resources,
            bookInfo = bookInfo,
            toc = toc,
        )

    private fun chapter(href: String, html: String): EpubChapter =
        EpubChapter(
            id = href,
            href = href,
            mediaType = "application/xhtml+xml",
            html = html,
        )

    private fun xhtml(value: String): EpubResource =
        EpubResource("application/xhtml+xml", value.toByteArray())
}
