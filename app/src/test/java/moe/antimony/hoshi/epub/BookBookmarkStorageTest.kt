package moe.antimony.hoshi.epub

import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class BookBookmarkStorageTest {
    @Test
    fun saveBookmarkWritesIosCompatibleBookmarkJson() = runBlocking {
        val root = Files.createTempDirectory("hoshi-bookmark").toFile()
        val storage = BookStorage(root)
        val bookRoot = storage.currentBookFile.apply { mkdirs() }

        val bookmark = Bookmark(
            chapterIndex = 3,
            progress = 0.625,
            characterCount = 0,
            lastModified = 798715800.0,
        )

        storage.saveBookmark(bookRoot, bookmark)

        val saved = Json.parseToJsonElement(bookRoot.resolve("bookmark.json").readText()).jsonObject
        assertEquals(3, saved.getValue("chapterIndex").jsonPrimitive.int)
        assertEquals(0.625, saved.getValue("progress").jsonPrimitive.double, 0.0)
        assertEquals(0, saved.getValue("characterCount").jsonPrimitive.int)
        assertEquals(798715800.0, saved.getValue("lastModified").jsonPrimitive.double, 0.0)
    }

    @Test
    fun loadBookmarkReturnsNullWhenBookmarkJsonIsMissing() = runBlocking {
        val root = Files.createTempDirectory("hoshi-bookmark-missing").toFile()
        val storage = BookStorage(root)
        val bookRoot = storage.currentBookFile.apply { mkdirs() }

        assertNull(storage.loadBookmark(bookRoot))
    }

    @Test
    fun loadBookmarkReadsSavedBookmark() = runBlocking {
        val root = Files.createTempDirectory("hoshi-bookmark-load").toFile()
        val storage = BookStorage(root)
        val bookRoot = storage.currentBookFile.apply { mkdirs() }
        bookRoot.resolve("bookmark.json").writeText(
            """
            {
                "chapterIndex": 2,
                "progress": 0.5,
                "characterCount": 18,
                "lastModified": 798717600.0
            }
            """.trimIndent(),
        )

        assertEquals(
            Bookmark(
                chapterIndex = 2,
                progress = 0.5,
                characterCount = 18,
                lastModified = 798717600.0,
            ),
            storage.loadBookmark(bookRoot),
        )
    }

    @Test
    fun bookProgressUsesBookmarkCharacterCountOverBookInfoCharacterCount() = runBlocking {
        val root = Files.createTempDirectory("hoshi-book-progress").toFile()
        val storage = BookStorage(root)
        val bookRoot = storage.currentBookFile.apply { mkdirs() }
        storage.saveBookmark(
            bookRoot,
            Bookmark(
                chapterIndex = 1,
                progress = 0.25,
                characterCount = 40,
                lastModified = 798717600.0,
            ),
        )
        storage.saveBookInfo(
            bookRoot,
            BookInfo(
                characterCount = 200,
                chapterInfo = emptyMap(),
            ),
        )

        assertEquals(0.2, storage.loadReadingProgress(bookRoot), 0.0)
    }
}
