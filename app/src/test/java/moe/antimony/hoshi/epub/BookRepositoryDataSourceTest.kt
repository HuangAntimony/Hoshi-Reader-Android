package moe.antimony.hoshi.epub

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BookRepositoryDataSourceTest {
    @Test
    fun fileDataSourceRejectsPathTraversalBookFolders() = runBlocking {
        val filesDir = Files.createTempDirectory("hoshi-book-files").toFile()
        val dataSource = BookFileDataSource(filesDir)

        val result = runCatching { dataSource.createBookDirectory("../escaped") }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)

        assertFalse(filesDir.parentFile!!.resolve("escaped").exists())
    }

    @Test
    fun repositoryPreservesSidecarNamesAndProgressCalculation() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-book-repository").toFile())
        val bookRoot = repository.createBookDirectory("book-a")
        val metadata = BookMetadata(
            id = "book-a",
            title = "Book A",
            cover = null,
            folder = "book-a",
            lastAccess = 1.0,
        )
        val bookmark = Bookmark(
            chapterIndex = 1,
            progress = 0.5,
            characterCount = 25,
        )
        val bookInfo = BookInfo(characterCount = 100, chapterInfo = emptyMap())

        repository.saveMetadata(bookRoot, metadata)
        repository.saveBookmark(bookRoot, bookmark)
        repository.saveBookInfo(bookRoot, bookInfo)

        assertTrue(bookRoot.resolve("metadata.json").isFile)
        assertTrue(bookRoot.resolve("bookmark.json").isFile)
        assertTrue(bookRoot.resolve("bookinfo.json").isFile)
        assertEquals(metadata, repository.loadMetadata(bookRoot))
        assertEquals(bookmark, repository.loadBookmark(bookRoot))
        assertEquals(bookInfo, repository.loadBookInfo(bookRoot))
        assertEquals(0.25, repository.loadReadingProgress(bookRoot), 0.0)
    }

    @Test
    fun fileDataSourceHidesDotPrefixedBookFolders() = runBlocking {
        val filesDir = Files.createTempDirectory("hoshi-book-hidden").toFile()
        val dataSource = BookFileDataSource(filesDir)
        val visible = dataSource.createBookDirectory("visible")
        dataSource.createBookDirectory(".hidden")

        assertEquals(listOf(visible.canonicalFile), dataSource.loadAllBooks().map { it.canonicalFile })
    }
}
