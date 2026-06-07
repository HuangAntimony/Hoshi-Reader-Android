package moe.antimony.hoshi.navigation

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.ReaderRouteBookRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReaderRouteStateHolderTest {
    @Test
    fun loadReadyParsesBookUpdatesSidecarsAndRestoresBookmark() = runBlocking {
        val root = Files.createTempDirectory("hoshi-reader-route-book").toFile()
        root.resolve("cover.jpg").writeText("cover")
        val parsedBook = readerBook(title = "Parsed Title", coverHref = "cover.jpg")
        val bookmark = Bookmark(chapterIndex = 0, progress = 0.5, characterCount = 4, lastModified = 10.0)
        val repository = FakeReaderRouteBookRepository(
            entry = BookEntry(
                root = root,
                metadata = BookMetadata(
                    id = "book-a",
                    title = "Old Title",
                    cover = null,
                    folder = "book-a",
                    lastAccess = 1.0,
                ),
            ),
            bookmark = bookmark,
            now = 42.0,
        )
        val stateHolder = ReaderRouteStateHolder(repository, FakeReaderRouteEpubParser(parsedBook))

        val state = stateHolder.load("book-a")

        assertTrue(state is ReaderRouteLoadState.Ready)
        state as ReaderRouteLoadState.Ready
        assertEquals(root, state.bookRoot)
        assertEquals(parsedBook, state.book)
        assertEquals(root.resolve("cover.jpg"), state.bookCoverFile)
        assertEquals(bookmark, state.bookmark)
        assertEquals(
            BookMetadata(
                id = "book-a",
                title = "Parsed Title",
                cover = "Books/${root.name}/cover.jpg",
                folder = root.name,
                lastAccess = 42.0,
            ),
            repository.savedMetadata,
        )
        assertEquals(parsedBook.bookInfo, repository.savedBookInfo)
    }

    @Test
    fun loadErrorReportsMissingBook() = runBlocking {
        val stateHolder = ReaderRouteStateHolder(
            repository = FakeReaderRouteBookRepository(entry = null),
            parser = FakeReaderRouteEpubParser(readerBook()),
        )

        val state = stateHolder.load("missing")

        assertEquals(ReaderRouteLoadState.Error("Book not found."), state)
    }

    @Test
    fun loadReusesCachedBookInfoWhenPresent() = runBlocking {
        val root = File("book-a")
        val cachedBookInfo = BookInfo(
            characterCount = 10,
            chapterInfo = mapOf(
                "chapter-1.xhtml" to BookInfo.ChapterInfo(
                    spineIndex = 0,
                    currentTotal = 0,
                    chapterCount = 10,
                ),
            ),
        )
        val parser = FakeReaderRouteEpubParser(readerBook(html = "ignored"))
        val repository = FakeReaderRouteBookRepository(
            entry = BookEntry(root, BookMetadata("book-a", "Book", null, "book-a", 0.0)),
            bookInfo = cachedBookInfo,
        )
        val stateHolder = ReaderRouteStateHolder(repository, parser)

        val state = stateHolder.load("book-a")

        assertTrue(state is ReaderRouteLoadState.Ready)
        assertEquals(cachedBookInfo, parser.cachedBookInfo)
        assertEquals(null, repository.savedBookInfo)
    }

    @Test
    fun saveBookmarkCalculatesCharacterCountAndNotifiesRefresh() = runBlocking {
        val root = File("book-a")
        val book = readerBook(html = "1234567890")
        val repository = FakeReaderRouteBookRepository(entry = null, now = 99.0)
        val stateHolder = ReaderRouteStateHolder(repository, FakeReaderRouteEpubParser(book))
        var refreshCount = 0

        stateHolder.saveBookmark(
            state = ReaderRouteLoadState.Ready(
                entry = BookEntry(root, BookMetadata("book-a", "Book", null, "book-a", 0.0)),
                bookRoot = root,
                book = book,
                bookCoverFile = null,
                bookmark = null,
            ),
            chapterIndex = 0,
            progress = 0.5,
            onBookmarkSaved = { refreshCount += 1 },
        )

        assertEquals(
            Bookmark(
                chapterIndex = 0,
                progress = 0.5,
                characterCount = 5,
                lastModified = 99.0,
            ),
            repository.savedBookmark,
        )
        assertEquals(1, refreshCount)
    }

    @Test
    fun saveBookmarkPersistsStatisticsThroughSameRepositoryPath() = runBlocking {
        val root = File("book-a")
        val book = readerBook(html = "1234567890")
        val statistics = listOf(ReadingStatistics(title = "Book", dateKey = "2026-05-13", charactersRead = 5))
        val repository = FakeReaderRouteBookRepository(entry = null, now = 99.0)
        val stateHolder = ReaderRouteStateHolder(repository, FakeReaderRouteEpubParser(book))

        stateHolder.saveBookmark(
            state = ReaderRouteLoadState.Ready(
                entry = BookEntry(root, BookMetadata("book-a", "Book", null, "book-a", 0.0)),
                bookRoot = root,
                book = book,
                bookCoverFile = null,
                bookmark = null,
            ),
            chapterIndex = 0,
            progress = 0.5,
            statistics = statistics,
            onBookmarkSaved = {},
        )

        assertEquals(statistics, repository.savedStatistics)
    }

    private class FakeReaderRouteBookRepository(
        private val entry: BookEntry?,
        private val bookmark: Bookmark? = null,
        private val bookInfo: BookInfo? = null,
        private val now: Double = 1.0,
    ) : ReaderRouteBookRepository {
        var savedMetadata: BookMetadata? = null
            private set
        var savedBookInfo: BookInfo? = null
            private set
        var savedBookmark: Bookmark? = null
            private set
        var savedStatistics: List<ReadingStatistics>? = null
            private set

        override suspend fun loadBookEntry(bookId: String): BookEntry? = entry

        override suspend fun metadataCoverPath(bookRoot: File, coverHref: String?): String? =
            coverHref?.let { "Books/${bookRoot.name}/${File(it).name}" }

        override suspend fun saveMetadata(bookRoot: File, metadata: BookMetadata) {
            savedMetadata = metadata
        }

        override suspend fun loadBookmark(bookRoot: File): Bookmark? = bookmark

        override suspend fun saveBookmark(bookRoot: File, bookmark: Bookmark) {
            savedBookmark = bookmark
        }

        override suspend fun loadStatistics(bookRoot: File): List<ReadingStatistics> = emptyList()

        override suspend fun saveStatistics(bookRoot: File, statistics: List<ReadingStatistics>) {
            savedStatistics = statistics
        }

        override suspend fun loadReaderBookInfo(bookRoot: File): BookInfo? = bookInfo

        override suspend fun saveBookInfo(bookRoot: File, bookInfo: BookInfo) {
            savedBookInfo = bookInfo
        }

        override fun currentAppleReferenceDateSeconds(): Double = now
    }

    private class FakeReaderRouteEpubParser(
        private val book: EpubBook,
    ) : ReaderRouteEpubParser {
        var cachedBookInfo: BookInfo? = null
            private set

        override fun parse(root: File, cachedBookInfo: BookInfo?): EpubBook {
            this.cachedBookInfo = cachedBookInfo
            return cachedBookInfo?.let { book.copy(bookInfo = it) } ?: book
        }
    }

    private fun readerBook(
        title: String = "Book",
        coverHref: String? = null,
        html: String = "abcdefghij",
    ): EpubBook =
        EpubBook(
            title = title,
            coverHref = coverHref,
            chapters = listOf(
                EpubChapter(
                    id = "chapter-1",
                    href = "chapter-1.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = html,
                ),
            ),
        )
}
