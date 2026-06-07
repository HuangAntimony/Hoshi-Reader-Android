package moe.antimony.hoshi.epub

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

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

    @Test
    fun deletingBookRemovesItFromAllShelves() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-book-shelves-delete").toFile())
        val keep = repository.createBookDirectory("keep")
        val remove = repository.createBookDirectory("remove")
        repository.saveMetadata(
            keep,
            BookMetadata(id = "keep-id", title = "Keep", cover = null, folder = "keep", lastAccess = 1.0),
        )
        repository.saveMetadata(
            remove,
            BookMetadata(id = "remove-id", title = "Remove", cover = null, folder = "remove", lastAccess = 2.0),
        )
        repository.saveShelves(
            listOf(
                BookShelf(name = "Shelf A", bookIds = listOf("remove-id", "keep-id")),
                BookShelf(name = "Shelf B", bookIds = listOf("remove-id")),
            ),
        )

        repository.deleteBook(remove)

        assertEquals(
            listOf(
                BookShelf(name = "Shelf A", bookIds = listOf("keep-id")),
                BookShelf(name = "Shelf B", bookIds = emptyList()),
            ),
            repository.loadShelves(),
        )
    }

    @Test
    fun deletingBookReleasesPersistedSasayakiAudioUriBeforeRemovingBookDirectory() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-book-audio-uri-delete").toFile())
        val remove = repository.createBookDirectory("remove-audio-uri")
        remove.resolve("sasayaki_playback.json").writeText(
            """{"lastPosition":0.0,"audioUri":"content://media/external/audio/media/1"}""",
        )
        val released = mutableListOf<String>()

        repository.deleteBook(remove) { released += it }

        assertEquals(listOf("content://media/external/audio/media/1"), released)
        assertFalse(remove.exists())
    }

    @Test
    fun metadataCoverPathCanCacheCoverResourceFromPackedEpubParserResult() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-packed-cover").toFile())
        val root = repository.createBookDirectory("packed-cover")
        writeMinimalEpubArchive(root.resolve("packed-cover.epub"), title = "Packed Cover")
        val parsed = EpubBookParser().parse(root)

        val coverPath = repository.metadataCoverPath(root, parsed)

        assertEquals("Books/packed-cover/cover.jpg", coverPath)
        assertEquals(listOf(1, 2, 3), root.resolve("cover.jpg").readBytes().map(Byte::toInt))
    }

    @Test
    fun legacyExtractedMigrationWritesRootCoverIntoPackedEpubAndPreservesSidecarCopy() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-legacy-root-cover").toFile())
        val root = repository.createBookDirectory("legacy-root-cover")
        writeMinimalExtractedEpub(root, title = "Legacy Root Cover")
        root.resolve("OPS/package.opf").writeText(
            root.resolve("OPS/package.opf").readText().replace("images/cover.jpg", "../cover.jpg"),
        )
        root.resolve("OPS/images/cover.jpg").copyTo(root.resolve("cover.jpg"), overwrite = true)
        root.resolve("OPS/images/cover.jpg").delete()
        repository.saveMetadata(
            root,
            BookMetadata(
                id = "legacy-root-cover",
                title = "Legacy Root Cover",
                cover = "cover.jpg",
                folder = "legacy-root-cover",
                lastAccess = 1.0,
            ),
        )

        val entry = repository.loadBookEntries().single()

        assertEquals("legacy-root-cover.epub", entry.metadata.epub)
        assertTrue(root.resolve("cover.jpg").isFile)
        assertFalse(root.resolve("OPS").exists())
        ZipFile(root.resolve("legacy-root-cover.epub")).use { archive ->
            assertTrue(archive.getEntry("cover.jpg") != null)
        }
    }
}
