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
        assertTrue(results.single().snippet.contains("漢字 A"))
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
    fun searchSnippetKeepsDisplayPunctuationWithoutChangingCharacterOffset() {
        val book = searchBook(
            chapters = listOf(chapter("c0.xhtml", "")),
            resources = mapOf("c0.xhtml" to xhtml("<html><body>吾輩は「猫、です。」犬</body></html>")),
            bookInfo = BookInfo(
                characterCount = 7,
                chapterInfo = mapOf("c0.xhtml" to BookInfo.ChapterInfo(spineIndex = 0, currentTotal = 0, chapterCount = 7)),
            ),
        )

        val result = ReaderSearchEngine(book).search("猫です").single()

        assertEquals(3, result.character)
        assertTrue(result.snippet.contains("「猫、です。」"))
        assertEquals("猫、です", result.highlightedText())
    }

    @Test
    fun searchQueryPunctuationRemainsIgnoredWhenSnippetKeepsDisplayPunctuation() {
        val book = searchBook(
            chapters = listOf(chapter("c0.xhtml", "")),
            resources = mapOf("c0.xhtml" to xhtml("<html><body>吾輩は「猫、です。」犬</body></html>")),
            bookInfo = BookInfo(
                characterCount = 7,
                chapterInfo = mapOf("c0.xhtml" to BookInfo.ChapterInfo(spineIndex = 0, currentTotal = 0, chapterCount = 7)),
            ),
        )

        val result = ReaderSearchEngine(book).search("猫!です").single()

        assertEquals(3, result.character)
        assertEquals("猫、です", result.highlightedText())
    }

    @Test
    fun searchSnippetExcludesRubyAndScriptsButKeepsVisiblePunctuation() {
        val book = searchBook(
            chapters = listOf(chapter("c0.xhtml", "")),
            resources = mapOf(
                "c0.xhtml" to xhtml(
                    "<html><body><ruby>漢<rt>かん</rt></ruby>、字<script>bad。</script></body></html>",
                ),
            ),
            bookInfo = BookInfo(
                characterCount = 2,
                chapterInfo = mapOf("c0.xhtml" to BookInfo.ChapterInfo(spineIndex = 0, currentTotal = 0, chapterCount = 2)),
            ),
        )

        val result = ReaderSearchEngine(book).search("漢字").single()

        assertEquals(0, result.character)
        assertTrue(result.snippet.contains("漢、字"))
        assertTrue(!result.snippet.contains("かん"))
        assertTrue(!result.snippet.contains("bad"))
        assertEquals("漢、字", result.highlightedText())
    }

    @Test
    fun searchHighlightMappingHandlesNonBmpDisplayCodePointsBetweenMatchedCharacters() {
        val book = searchBook(
            chapters = listOf(chapter("c0.xhtml", "")),
            resources = mapOf("c0.xhtml" to xhtml("<html><body>A🙂B</body></html>")),
            bookInfo = BookInfo(
                characterCount = 2,
                chapterInfo = mapOf("c0.xhtml" to BookInfo.ChapterInfo(spineIndex = 0, currentTotal = 0, chapterCount = 2)),
            ),
        )

        val result = ReaderSearchEngine(book).search("AB").single()

        assertEquals(0, result.character)
        assertEquals("A🙂B", result.snippet)
        assertEquals("A🙂B", result.highlightedText())
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

private fun ReaderSearchResult.highlightedText(): String =
    snippet.substringByCodePoints(snippetMatchStart, snippetMatchEnd)

private fun String.substringByCodePoints(start: Int, end: Int): String {
    val startIndex = offsetByCodePoints(0, start)
    val endIndex = offsetByCodePoints(0, end)
    return substring(startIndex, endIndex)
}
