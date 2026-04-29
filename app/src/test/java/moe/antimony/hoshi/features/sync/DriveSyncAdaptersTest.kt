package moe.antimony.hoshi.features.sync

import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DriveSyncAdaptersTest {
    @Test
    fun folderCachePersistsRootAndBookFolderIds() {
        val file = tempDir().resolve("drive-cache.json")
        val cache = DriveFolderFileCache(file)
        cache.rootFolderId = "root"
        cache.titleToFolderId["Book"] = "book-id"
        cache.save()

        val reloaded = DriveFolderFileCache(file)

        assertEquals("root", reloaded.rootFolderId)
        assertEquals("book-id", reloaded.titleToFolderId["Book"])
    }

    @Test
    fun folderCacheClearRemovesStoredValues() {
        val file = tempDir().resolve("drive-cache.json")
        val cache = DriveFolderFileCache(file)
        cache.rootFolderId = "root"
        cache.titleToFolderId["Book"] = "book-id"
        cache.save()

        cache.clear()

        val reloaded = DriveFolderFileCache(file)
        assertEquals(null, reloaded.rootFolderId)
        assertTrue(reloaded.titleToFolderId.isEmpty())
    }

    @Test
    fun bookStorageAdapterReadsAndWritesBookSidecars() {
        val root = tempDir()
        val adapter = BookStorageSyncAdapter(root)
        val folder = "Book"
        val bookmark = Bookmark(0, 0.5, 50, 1.0)
        val bookInfo = BookInfo(
            characterCount = 100,
            chapterInfo = mapOf("0" to BookInfo.ChapterInfo(0, 0, 100)),
        )
        root.resolve("Books/$folder").mkdirs()
        root.resolve("Books/$folder/bookinfo.json").writeText(adapterJson.encodeToString(bookInfo))

        adapter.saveBookmark(folder, bookmark)

        assertEquals(bookmark, adapter.loadBookmark(folder))
        assertEquals(bookInfo, adapter.loadBookInfo(folder))
    }

    private fun tempDir(): File = createTempDirectory(prefix = "drive-sync-adapters").toFile()
}
