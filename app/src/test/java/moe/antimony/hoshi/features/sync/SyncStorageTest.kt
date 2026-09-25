package moe.antimony.hoshi.features.sync

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.ReadingSession
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val repository by lazy { BookRepository(temporary.root) }
    private val storage by lazy { SyncStorage(temporary.root, repository, Dispatchers.IO) }

    private suspend fun local(folder: String = "book-a", epub: Boolean = true): File {
        val root = repository.createBookDirectory(folder)
        repository.saveMetadata(root, BookMetadata("ABC", "Title", folder = folder, lastAccess = 0.0, epub = if (epub) "$folder.epub" else null))
        if (epub) root.resolve("$folder.epub").writeText("epub")
        return root
    }

    private fun remote(generation: Int = 1, deleted: Boolean = false): SyncBook = SyncBook(
        generation, deleted, Timestamped(1000, SyncMetadata("Remote", "Author")), characterCount = 500,
        files = mapOf(SyncFileType.epub to Timestamped(100, "book-a.epub")),
        bookmark = Timestamped(100, SyncBookmark(70)),
        shelves = mapOf("Shelf" to Timestamped(100, true)),
    ).let { if (deleted) it.delete() else it }

    @Test fun preparesAndReloadsExactBookkeeping() = runBlocking {
        local()
        storage.prepareLibrary()
        val record = storage.state.books.getValue("book-a")
        assertEquals(1, record.generation)
        assertFalse(record.attached)
        assertTrue(record.pending)
        assertTrue(record.sources.getValue(SyncFileType.epub) > 0)
        assertEquals(record, SyncFormat.json.decodeFromString<SyncState>(temporary.root.resolve("Books/.sync.json").readText()).books["book-a"])
        storage.reload()
        assertEquals(record, storage.state.books["book-a"])
    }

    @Test fun appliesCloudPlaceholderWithoutClaimingLocalFiles() = runBlocking {
        val book = remote().copy(audiobook = Timestamped(90, SyncPlayback(5.0, 2.0, 1.5)))
        storage.applyBook("book-a", book)
        val root = storage.bookDirectory("book-a")
        val metadata = repository.loadMetadata(root)!!
        assertNull(metadata.epub)
        assertEquals("Remote", metadata.title)
        assertEquals(500, metadata.characterCount)
        assertEquals(70, repository.loadBookmark(root)!!.characterCount)
        assertEquals(100L, repository.loadBookmark(root)!!.lastModified!!.appleDateMilliseconds())
        assertEquals(book, storage.loadBook("book-a"))
        assertTrue(storage.state.books.getValue("book-a").attached)
        assertTrue(storage.state.books.getValue("book-a").sources.isEmpty())
    }

    @Test fun applyingSyncedMetadataPreservesNativeIdentityAndLocalSettings() = runBlocking {
        val root = local()
        repository.saveMetadata(root, repository.loadMetadata(root)!!.copy(profileId = "profile", bookLanguage = "ja"))
        storage.prepareLibrary()
        storage.applyBook("book-a", remote())
        val metadata = repository.loadMetadata(root)!!
        assertEquals("ABC", metadata.id)
        assertEquals("Title", metadata.title)
        assertEquals("Remote", metadata.renamedTitle)
        assertEquals("profile", metadata.profileId)
        assertEquals("ja", metadata.bookLanguage)
        assertEquals(1000L, metadata.modified)
    }

    @Test fun deleteLocalPreservesRemoteReferenceAndReadingState() = runBlocking {
        val root = local()
        storage.prepareLibrary()
        storage.applyBook("book-a", remote())
        storage.deleteLocalBook("book-a")
        assertFalse(root.resolve("book-a.epub").exists())
        assertNull(repository.loadMetadata(root)!!.epub)
        assertEquals(remote().files, storage.state.books.getValue("book-a").files)
        assertNull(storage.state.books.getValue("book-a").sources[SyncFileType.epub])
        assertEquals(70, storage.loadBook("book-a")!!.bookmark!!.value.characterCount)
    }

    @Test fun deletionKeepsSessionsAndReimportAdvancesGeneration() = runBlocking {
        val root = local()
        storage.prepareLibrary()
        val sessions = mapOf("SESSION" to Timestamped<ReadingSession?>(20, ReadingSession(10, 20, 10, 2.0)))
        storage.applyBook("book-a", remote().copy(sessions = sessions))
        storage.deleteBook("book-a")
        assertFalse(root.exists())
        val deleted = storage.loadBook("book-a")!!
        assertTrue(deleted.deleted)
        assertEquals(sessions, deleted.sessions)
        assertNull(deleted.bookmark)
        assertNull(deleted.files[SyncFileType.epub])
        assertEquals(setOf(1), storage.state.books.getValue("book-a").cleanup)
        val reimported = local()
        storage.handleBookImport(repository.loadMetadata(reimported)!!, reimported)
        val live = storage.loadBook("book-a")!!
        assertEquals(2, live.generation)
        assertFalse(live.deleted)
        assertEquals(sessions, live.sessions)
        assertTrue(storage.state.books.getValue("book-a").attached)
        assertFalse(storage.bookDirectory("book-a", true).exists())
    }

    @Test fun deletionJoinsBookWorkBeforeLockingStorageOrRemovingFiles() = runBlocking {
        withTimeout(5000) {
            for (localOnly in listOf(true, false)) {
                val root = local("book-$localOnly")
                storage.prepareLibrary()
                var stopped = false
                val work = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            repository.storageLock.withLock {
                                assertTrue(root.resolve("${root.name}.epub").exists())
                                stopped = true
                            }
                        }
                    }
                }
                repository.workRegistry.register(root.resolve(".")) { work.cancelAndJoin() }.use {
                    if (localOnly) storage.deleteLocalBook(root.name) else storage.deleteBook(root.name)
                }
                assertTrue(stopped)
                assertFalse(root.resolve("${root.name}.epub").exists())
                assertEquals(localOnly, root.exists())
            }
        }
    }

    @Test fun generationReplacementRetainsDeviceAudioSelection() = runBlocking {
        val root = local()
        repository.saveSasayakiPlayback(root, SasayakiPlaybackData(20.0, 4.0, 1.5f, audioUri = "content://audio", audioFileName = "audio.m4b"))
        root.resolve("highlights.json").writeText("{}")
        storage.prepareLibrary()
        storage.removeBookFiles("book-a")
        val playback = repository.loadSasayakiPlayback(root)!!
        assertEquals("content://audio", playback.audioUri)
        assertEquals("audio.m4b", playback.audioFileName)
        assertEquals(0.0, playback.lastPosition, 0.0)
        assertEquals(0.0, playback.delay, 0.0)
        assertEquals(1f, playback.rate)
        assertNull(playback.modified)
        assertFalse(root.resolve("highlights.json").exists())
        assertNull(repository.loadMetadata(root)!!.epub)
        assertTrue(storage.state.books.getValue("book-a").sources.isEmpty())
    }

    @Test fun localShelvesAndSessionEditsMarkPending() = runBlocking {
        val root = local()
        storage.prepareLibrary()
        storage.updateRecord("book-a") { it.copy(pending = false) }
        repository.saveShelves(listOf(BookShelf("Shelf", listOf("ABC"))))
        assertTrue(storage.state.shelvesPending)
        assertTrue(storage.state.books.getValue("book-a").pending)
        storage.updateRecord("book-a") { it.copy(pending = false) }
        repository.statisticsStore.saveTrackedSession(root, "SESSION", ReadingSession(10, 20, 1, 1.0))
        assertTrue(storage.state.books.getValue("book-a").pending)
    }

    @Test fun nativeEditsCannotInterleaveASyncTransaction() = runBlocking {
        val root = local()
        storage.prepareLibrary()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transaction = async(Dispatchers.Default) {
            storage.transaction {
                entered.complete(Unit)
                release.await()
                storage.applyBook("book-a", remote())
            }
        }
        entered.await()
        val edit = async(Dispatchers.Default) {
            repository.storageLock.withLock {
                repository.saveMetadata(root, repository.loadMetadata(root)!!.copy(renamedTitle = "Local edit", modified = 2000))
            }
        }
        release.complete(Unit)
        transaction.await()
        edit.await()
        assertEquals("Local edit", storage.loadBook("book-a")!!.metadata.value.title)
        assertTrue(storage.state.books.getValue("book-a").pending)
    }
}
