package moe.antimony.hoshi.features.bookshelf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BookshelfBookSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun returnSnapshotWaitsForReaderSaveBeforeTakingStorageLock() = runTest {
        val books = BookRepository(temporary.root, StandardTestDispatcher(testScheduler))
        val root = books.createBookDirectory("book")
        val metadata = BookMetadata("book-id", "Book", folder = "book", lastAccess = 1.0, epub = "book.epub")
        books.saveMetadata(root, metadata)
        books.saveBookInfo(root, BookInfo(1_000, emptyMap()))
        books.saveBookmark(root, Bookmark(0, 0.1, 100))
        val saveGate = CompletableDeferred<Unit>()
        val pendingSave = launch {
            saveGate.await()
            books.storageLock.withLock {
                books.saveMetadata(root, metadata.copy(lastAccess = 2.0))
                books.saveBookmark(root, Bookmark(0, 0.65, 650))
            }
        }
        books.trackReaderSave(root, pendingSave)
        val snapshot = async { loadBookshelfBookSnapshot(BookEntry(root, metadata), books) }
        runCurrent()
        assertFalse(snapshot.isCompleted)
        saveGate.complete(Unit)
        val loaded = snapshot.await()!!
        assertEquals(0.65, loaded.readingState.progress, 0.0)
        assertEquals(2.0, loaded.entry.metadata.lastAccess, 0.0)
        assertTrue(loaded.readingState.canMarkUnread)
        books.saveMetadata(root, metadata.copy(id = "replacement"))
        assertNull(loadBookshelfBookSnapshot(BookEntry(root, metadata), books))
        root.deleteRecursively()
        assertNull(loadBookshelfBookSnapshot(BookEntry(root, metadata), books))
        assertFalse(root.exists())
    }
}
