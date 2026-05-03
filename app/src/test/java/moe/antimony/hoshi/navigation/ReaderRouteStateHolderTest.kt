package moe.antimony.hoshi.navigation

import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.ReaderRouteBookRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderRouteStateHolderTest {
    @Test
    fun loadReadyParsesBookUpdatesSidecarsAndRestoresBookmark() {
        val root = File("book-a")
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
        assertEquals(bookmark, state.bookmark)
        assertEquals(
            BookMetadata(
                id = "book-a",
                title = "Parsed Title",
                cover = "cover.jpg",
                folder = "book-a",
                lastAccess = 42.0,
            ),
            repository.savedMetadata,
        )
        assertEquals(parsedBook.bookInfo, repository.savedBookInfo)
    }

    @Test
    fun loadErrorReportsMissingBook() {
        val stateHolder = ReaderRouteStateHolder(
            repository = FakeReaderRouteBookRepository(entry = null),
            parser = FakeReaderRouteEpubParser(readerBook()),
        )

        val state = stateHolder.load("missing")

        assertEquals(ReaderRouteLoadState.Error("Book not found."), state)
    }

    @Test
    fun saveBookmarkCalculatesCharacterCountAndNotifiesRefresh() {
        val root = File("book-a")
        val book = readerBook(html = "1234567890")
        val repository = FakeReaderRouteBookRepository(entry = null, now = 99.0)
        val stateHolder = ReaderRouteStateHolder(repository, FakeReaderRouteEpubParser(book))
        var refreshCount = 0

        stateHolder.saveBookmark(
            state = ReaderRouteLoadState.Ready(
                bookRoot = root,
                book = book,
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

    private class FakeReaderRouteBookRepository(
        private val entry: BookEntry?,
        private val bookmark: Bookmark? = null,
        private val now: Double = 1.0,
    ) : ReaderRouteBookRepository {
        var savedMetadata: BookMetadata? = null
            private set
        var savedBookInfo: BookInfo? = null
            private set
        var savedBookmark: Bookmark? = null
            private set

        override fun loadBookEntry(bookId: String): BookEntry? = entry

        override fun saveMetadata(bookRoot: File, metadata: BookMetadata) {
            savedMetadata = metadata
        }

        override fun loadBookmark(bookRoot: File): Bookmark? = bookmark

        override fun saveBookmark(bookRoot: File, bookmark: Bookmark) {
            savedBookmark = bookmark
        }

        override fun saveBookInfo(bookRoot: File, bookInfo: BookInfo) {
            savedBookInfo = bookInfo
        }

        override fun currentAppleReferenceDateSeconds(): Double = now
    }

    private class FakeReaderRouteEpubParser(
        private val book: EpubBook,
    ) : ReaderRouteEpubParser {
        override fun parse(root: File): EpubBook = book
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
