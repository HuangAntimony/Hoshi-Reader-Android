package moe.antimony.hoshi.epub

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class BookCoverStorageTest {
    @Test
    fun coverFileResolvesMetadataCoverInsideBookRoot() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-cover").toFile())
        val root = storage.createBookDirectory("book")
        root.resolve("OPS/images").mkdirs()
        val cover = root.resolve("OPS/images/cover.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val entry = BookEntry(
            root = root,
            metadata = BookMetadata(
                id = "book",
                title = "Book",
                cover = "OPS/images/cover.jpg",
                folder = "book",
                lastAccess = 1.0,
            ),
        )

        assertEquals(cover.canonicalFile, storage.coverFile(entry)?.canonicalFile)
    }

    @Test
    fun coverFileRejectsPathsOutsideBookRoot() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-cover-unsafe").toFile())
        val root = storage.createBookDirectory("book")
        val entry = BookEntry(
            root = root,
            metadata = BookMetadata(
                id = "book",
                title = "Book",
                cover = "../outside.jpg",
                folder = "book",
                lastAccess = 1.0,
            ),
        )

        assertNull(storage.coverFile(entry))
    }
}
