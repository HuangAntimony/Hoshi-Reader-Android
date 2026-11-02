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
import moe.antimony.hoshi.epub.ReaderHighlight
import moe.antimony.hoshi.epub.HighlightColor
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

    @Test fun exactDirectoryLookupDoesNotEnumerateLargeLibrary() {
        val parent = temporary.newFolder("directory-lookup")
        val book = parent.resolve("Book").apply { mkdirs() }
        var listings = 0
        val counted = object : File(parent.path) {
            override fun listFiles(): Array<File>? { listings++; return super.listFiles() }
        }
        assertEquals(book, findSyncBookDirectory(counted, "Book"))
        assertEquals(0, listings)
    }

    @Test fun directoryLookupRetainsUnicodeEquivalentFallback() {
        val parent = temporary.newFolder("unicode-lookup")
        val decomposed = parent.resolve("Cafe\u0301").apply { mkdirs() }
        assertTrue(java.nio.file.Files.isSameFile(decomposed.toPath(), findSyncBookDirectory(parent, "Café").toPath()))
        assertEquals(parent.resolve("missing"), findSyncBookDirectory(parent, "missing"))
    }

    @Test fun readingBackOurOwnSnapshotDoesNotEraseAPendingHighlightBeforeTheNextEdit() = runBlocking {
        val root = local()
        val a = ReaderHighlight("A", 10, 0, "one", HighlightColor.Yellow, 0.0)
        val b = ReaderHighlight("B", 20, 0, "two", HighlightColor.Yellow, 0.0)
        repository.saveHighlights(root, listOf(a))
        storage.prepareLibrary()
        val ownSnapshot = storage.loadBook("book-a")!!
        var displayed = listOf(a)
        storage.applyReaderState = { _, _, _, _, changed -> if (changed) displayed = repository.loadHighlights(root) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sync = async(Dispatchers.Default) {
            storage.transaction {
                entered.complete(Unit)
                release.await()
                storage.applyBook("book-a", ownSnapshot)
            }
        }
        entered.await()
        displayed = displayed + b
        val pending = async(start = CoroutineStart.UNDISPATCHED) { repository.saveHighlights(root, displayed) }
        assertFalse(pending.isCompleted)
        release.complete(Unit)
        sync.await()
        pending.await()
        val recolored = a.copy(color = HighlightColor.Green)
        displayed = displayed.map { if (it.id == "A") recolored else it }
        repository.saveHighlights(root, displayed)

        assertEquals(listOf(recolored, b), repository.loadHighlights(root))
    }

    @Test fun readerHighlightsRefreshOnlyForChangedVisibleRecords() = runBlocking {
        val root = local()
        val a = ReaderHighlight("A", 10, 0, "one", HighlightColor.Yellow, 0.0)
        val b = ReaderHighlight("B", 20, 0, "two", HighlightColor.Blue, 0.0)
        repository.saveHighlights(root, listOf(a))
        storage.prepareLibrary()
        val snapshot = storage.loadBook("book-a")!!
        var displayed = listOf(a)
        val changes = mutableListOf<Boolean>()
        storage.applyReaderState = { _, _, _, _, changed ->
            changes += changed
            if (changed) displayed = repository.loadHighlights(root)
        }
        val retimestamped = snapshot.copy(highlights = snapshot.highlights.mapValues { (_, value) -> value.copy(modified = value.modified + 1) })
        storage.applyBook("book-a", retimestamped)
        val added = retimestamped.copy(highlights = retimestamped.highlights + ("B" to Timestamped(200, SyncHighlight(b))))
        storage.applyBook("book-a", added)
        assertEquals(listOf(a, b), displayed)
        storage.applyBook("book-a", added.copy(highlights = added.highlights + ("A" to Timestamped(300, null))))

        assertEquals(listOf(false, true, true), changes)
        assertEquals(listOf(b), displayed)
    }

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
        local("book-b")
        storage.prepareLibrary()
        assertEquals(record, storage.state.books["book-a"])
        assertTrue(storage.state.books.getValue("book-b").pending)
    }

    @Test fun startupPreparationDefersLiveBookStatisticsUntilTheBookIsSynced() = runBlocking {
        val root = local()
        val statistics = root.resolve("statistics.json")
        statistics.writeText("[]")

        storage.prepareLibrary()

        assertEquals("[]", statistics.readText())
        assertTrue(storage.state.books.getValue("book-a").pending)
        assertTrue(storage.loadBook("book-a")!!.sessions.isEmpty())
        assertEquals("{}", statistics.readText())
    }

    @Test fun startupPreparationStillMigratesAndRemovesEmptyStatisticsArchives() = runBlocking {
        val root = temporary.root.resolve("Books/statistics_archive/archived").apply { mkdirs() }
        repository.saveMetadata(root, BookMetadata("archived", "Archived", folder = "archived", lastAccess = 0.0))
        root.resolve("statistics.json").writeText("[]")

        storage.prepareLibrary()

        assertFalse(root.exists())
        assertTrue(storage.state.books.getValue("archived").deleted)
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

    @Test fun localAudiobookEchoDoesNotRequestAReaderPlaybackChange() = runBlocking {
        val root = local()
        repository.saveSasayakiPlayback(root, SasayakiPlaybackData(12.4, audioFileName = "local.m4b"))
        storage.prepareLibrary()
        val snapshot = storage.loadBook("book-a")!!
        val playbackChanges = mutableListOf<Boolean>()
        storage.applyReaderState = { _, _, _, changed, _ -> playbackChanges += changed }

        // Playback may already have advanced by the time this saved local snapshot is merged.
        storage.applyBook("book-a", snapshot)
        storage.applyBook("book-a", snapshot.copy(sessions = mapOf(
            "SESSION" to Timestamped(20, ReadingSession(10, 20, 10, 2.0)),
        )))

        assertEquals(listOf(false, false), playbackChanges)
    }

    @Test fun audiobookTimestampAndFloatRepresentationChangesDoNotRequestASeek() = runBlocking {
        val root = local()
        repository.applySasayakiPlayback(root, SasayakiPlaybackData(12.4, rate = 1.2f, modified = 100))
        storage.prepareLibrary()
        val snapshot = storage.loadBook("book-a")!!
        val playbackChanges = mutableListOf<Boolean>()
        storage.applyReaderState = { _, _, _, changed, _ -> playbackChanges += changed }

        storage.applyBook("book-a", snapshot.copy(audiobook = Timestamped(200, SyncPlayback(12.4, 0.0, 1.2))))

        assertEquals(listOf(false), playbackChanges)
        assertEquals(200L, repository.loadSasayakiPlayback(root)!!.modified)
    }

    @Test fun changedCloudAudiobookUpdatesTheReaderAndPreservesLocalAudioSelection() = runBlocking {
        val root = local()
        repository.saveSasayakiPlayback(root, SasayakiPlaybackData(12.4, audioUri = "content://audio", audioFileName = "local.m4b"))
        storage.prepareLibrary()
        val snapshot = storage.loadBook("book-a")!!
        val playbackChanges = mutableListOf<Boolean>()
        storage.applyReaderState = { _, _, _, changed, _ -> playbackChanges += changed }

        for (value in listOf(SyncPlayback(5.0, 0.0, 1.0), SyncPlayback(5.0, 2.0, 1.0), SyncPlayback(5.0, 2.0, 1.5))) {
            storage.applyBook("book-a", snapshot.copy(audiobook = Timestamped(200, value)))
        }

        assertEquals(listOf(true, true, true), playbackChanges)
        val applied = repository.loadSasayakiPlayback(root)!!
        assertEquals(5.0, applied.lastPosition, 0.0)
        assertEquals(2.0, applied.delay, 0.0)
        assertEquals(1.5f, applied.rate)
        assertEquals("content://audio", applied.audioUri)
        assertEquals("local.m4b", applied.audioFileName)
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
