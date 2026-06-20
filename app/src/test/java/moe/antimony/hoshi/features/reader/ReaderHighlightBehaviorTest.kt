package moe.antimony.hoshi.features.reader

import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.HighlightColor
import moe.antimony.hoshi.epub.ReaderHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderHighlightBehaviorTest {
    @Test
    fun chapterHighlightsSerializesOnlyCurrentChapterHighlightsSortedByCharacter() {
        val book = readerBook()
        val highlights = listOf(
            highlight(id = "second", character = 7, offset = 3, text = "def", color = HighlightColor.Blue),
            highlight(id = "later", character = 13, offset = 1, text = "ghi", color = HighlightColor.Pink),
            highlight(id = "first", character = 3, offset = 0, text = "abc", color = HighlightColor.Yellow),
        )

        val json = ReaderHighlights.chapterHighlightsJson(
            highlights = highlights,
            bookInfo = book.bookInfo,
            chapter = book.chapters[0],
        )

        assertEquals(
            """[{"id":"first","character":3,"offset":0,"text":"abc","color":"yellow","createdAt":1.0},{"id":"second","character":7,"offset":3,"text":"def","color":"blue","createdAt":1.0}]""",
            json,
        )
    }

    @Test
    fun chapterHighlightsReturnsNullWhenNoHighlightsAreInChapter() {
        val book = readerBook()
        val highlights = listOf(highlight(id = "later", character = 13, offset = 1, text = "ghi"))

        assertNull(
            ReaderHighlights.chapterHighlightsJson(
                highlights = highlights,
                bookInfo = book.bookInfo,
                chapter = book.chapters[0],
            ),
        )
    }

    @Test
    fun targetPositionForHighlightCharacterMapsToChapterProgress() {
        val book = readerBook()

        assertEquals(
            ReaderChapterPosition(index = 1, progress = 0.5),
            ReaderHighlights.positionForCharacter(book.bookInfo, 15),
        )
    }

    @Test
    fun sectionsGroupHighlightsByChapterLabelAndSortByCharacter() {
        val book = readerBook()
        val highlights = listOf(
            highlight(id = "second", character = 13, text = "middle"),
            highlight(id = "first", character = 2, text = "start"),
            highlight(id = "third", character = 19, text = "end"),
        )

        val sections = ReaderHighlightSections.sections(
            book = book,
            highlights = highlights,
        )

        assertEquals(listOf("Chapter One", "Chapter Two"), sections.map { it.label })
        assertEquals(listOf("first"), sections[0].highlights.map { it.id })
        assertEquals(listOf("second", "third"), sections[1].highlights.map { it.id })
    }

    @Test
    fun sectionsGroupUnlabeledSpineHighlightsUnderPreviousLabeledChapter() {
        val book = readerBookWithUnlabeledSpineItem()
        val highlights = listOf(
            highlight(id = "chapter-one", character = 2, text = "start"),
            highlight(id = "split-page", character = 12, text = "middle"),
            highlight(id = "chapter-two", character = 18, text = "end"),
        )

        val sections = ReaderHighlightSections.sections(
            book = book,
            highlights = highlights,
        )

        assertEquals(listOf("Chapter One", "Chapter Two"), sections.map { it.label })
        assertEquals(listOf("chapter-one", "split-page"), sections[0].highlights.map { it.id })
        assertEquals(listOf(2, 12), sections[0].highlights.map { it.character })
        assertEquals(listOf("chapter-two"), sections[1].highlights.map { it.id })
    }

    @Test
    fun sharedChapterLabelsResolveUnlabeledSpineItemsToPreviousChapter() {
        val book = readerBookWithUnlabeledSpineItem()

        assertEquals("Chapter One", ReaderChapterLabels.sectionLabelForIndex(book, 1))
        assertEquals("Chapter Two", ReaderChapterLabels.sectionLabelForIndex(book, 2))
    }

    @Test
    fun sharedChapterLabelsResolveFromPrecomputedLabels() {
        val book = readerBookWithUnlabeledSpineItem()
        val labels = ReaderChapterLabels.labels(book)

        assertEquals("Chapter One", ReaderChapterLabels.sectionLabelForIndex(labels, 1))
        assertEquals("Chapter Two", ReaderChapterLabels.sectionLabelForIndex(labels, 2))
    }

    @Test
    fun creationResultParsesValidWebViewJsonAndRejectsMissingData() {
        val result = ReaderHighlightCreationResult.fromWebViewResult(
            """{"start":4,"offset":9,"text":"食べる"}""",
        )

        assertEquals(ReaderHighlightCreationResult(start = 4, offset = 9, text = "食べる"), result)
        assertNull(ReaderHighlightCreationResult.fromWebViewResult(null))
        assertNull(ReaderHighlightCreationResult.fromWebViewResult("null"))
        assertNull(ReaderHighlightCreationResult.fromWebViewResult("{}"))
        assertNull(ReaderHighlightCreationResult.fromWebViewResult("not json"))
    }

    @Test
    fun highlightColorsExposeIosRgbaValues() {
        assertEquals("rgba(239, 209, 56, 0.35)", HighlightColor.Yellow.cssBackground)
        assertEquals("rgba(152, 220, 129, 0.35)", HighlightColor.Green.cssBackground)
        assertEquals("rgba(149, 185, 255, 0.35)", HighlightColor.Blue.cssBackground)
        assertEquals("rgba(255, 155, 180, 0.35)", HighlightColor.Pink.cssBackground)
        assertEquals("rgba(197, 175, 251, 0.35)", HighlightColor.Purple.cssBackground)
    }

    @Test
    fun invalidHighlightFieldsAreIgnoredWhileLoadingSidecarJson() {
        val json = Json { ignoreUnknownKeys = true }
        val highlight = json.decodeFromString<ReaderHighlight>(
            """
            {
                "id": "id-1",
                "character": 5,
                "offset": 2,
                "text": "猫",
                "color": "purple",
                "createdAt": 801187200.0,
                "futureField": true
            }
            """.trimIndent(),
        )

        assertEquals(highlight(id = "id-1", character = 5, offset = 2, text = "猫", color = HighlightColor.Purple, createdAt = 801187200.0), highlight)
    }

    private fun readerBook(): EpubBook =
        EpubBook(
            title = "Book",
            chapters = listOf(
                EpubChapter(
                    id = "chapter-1",
                    href = "chapter-1.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "abcdefghij",
                ),
                EpubChapter(
                    id = "chapter-2",
                    href = "chapter-2.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "klmnopqrst",
                ),
            ),
            toc = listOf(
                moe.antimony.hoshi.epub.EpubTocItem(label = "Chapter One", href = "chapter-1.xhtml"),
                moe.antimony.hoshi.epub.EpubTocItem(label = "Chapter Two", href = "chapter-2.xhtml"),
            ),
        )

    private fun readerBookWithUnlabeledSpineItem(): EpubBook =
        EpubBook(
            title = "Book",
            chapters = listOf(
                EpubChapter(
                    id = "chapter-1",
                    href = "chapter-1.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "abcdefghij",
                ),
                EpubChapter(
                    id = "chapter-1-split",
                    href = "chapter-1-split.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "klmno",
                ),
                EpubChapter(
                    id = "chapter-2",
                    href = "chapter-2.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "pqrstuvwxy",
                ),
            ),
            toc = listOf(
                moe.antimony.hoshi.epub.EpubTocItem(label = "Chapter One", href = "chapter-1.xhtml"),
                moe.antimony.hoshi.epub.EpubTocItem(label = "Chapter Two", href = "chapter-2.xhtml"),
            ),
        )

    private fun highlight(
        id: String,
        character: Int,
        offset: Int = 0,
        text: String,
        color: HighlightColor = HighlightColor.Yellow,
        createdAt: Double = 1.0,
    ): ReaderHighlight =
        ReaderHighlight(
            id = id,
            character = character,
            offset = offset,
            text = text,
            color = color,
            createdAt = createdAt,
        )
}
