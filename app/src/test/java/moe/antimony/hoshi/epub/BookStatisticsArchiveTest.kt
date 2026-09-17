package moe.antimony.hoshi.epub

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookStatisticsArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun deletingBookPreservesActiveStatisticsAndDisplayMetadata() = runBlocking {
        val files = temporary.newFolder()
        val repository = BookRepository(files)
        val book = repository.createBookDirectory("book")
        repository.saveMetadata(book, BookMetadata(id = "id", title = "Original", renamedTitle = "Display", author = "Author", cover = null, folder = "book", lastAccess = 12.0))
        repository.saveStatistics(book, listOf(ReadingStatistics("Original", "2026-09-01", charactersRead = 25), ReadingStatistics("Original", "2026-09-02")))
        repository.deleteBook(book)
        assertFalse(book.exists())
        val archive = files.resolve("Books/statistics_archive/book")
        assertEquals(listOf(25), repository.loadStatistics(archive).map { it.charactersRead })
        assertEquals("Display", repository.loadMetadata(archive)?.title)
        assertEquals("Author", repository.loadMetadata(archive)?.author)
        assertTrue(repository.loadAllBooks().isEmpty())
    }

    @Test fun corruptStatisticsAbortsDeletionButMissingStatisticsDoesNot() = runBlocking {
        val files = temporary.newFolder()
        val repository = BookRepository(files)
        val corrupt = repository.createBookDirectory("corrupt")
        corrupt.resolve("statistics.json").writeText("broken")
        assertTrue(runCatching { repository.deleteBook(corrupt) }.isFailure)
        assertTrue(corrupt.isDirectory)
        val missing = repository.createBookDirectory("missing")
        repository.deleteBook(missing)
        assertFalse(missing.exists())
    }
}
