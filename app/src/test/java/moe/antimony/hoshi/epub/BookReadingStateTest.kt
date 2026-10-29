package moe.antimony.hoshi.epub

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.sync.SyncBook
import moe.antimony.hoshi.features.sync.SyncStorage
import moe.antimony.hoshi.features.sync.Timestamped
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookReadingStateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val repository by lazy { BookRepository(temporary.root) }

    private suspend fun book(folder: String = "book", epub: String? = null): File {
        val root = repository.createBookDirectory(folder)
        repository.saveMetadata(root, BookMetadata("B40C56CC-7766-4C7B-85FA-6F3E6FA0D27D", "Book",
            folder = folder, lastAccess = 0.0, epub = epub, characterCount = 200))
        return root
    }

    @Test fun cachedBookInfoAllowsMarkReadWithoutALocalEpub() = runBlocking {
        val root = book()
        repository.saveBookInfo(root, BookInfo(200, mapOf("one" to BookInfo.ChapterInfo(3, 0, 100),
            "two" to BookInfo.ChapterInfo(7, 100, 100))))
        val sessions = mapOf("A" to Timestamped<ReadingSession?>(1, ReadingSession(0, 10, 50)))
        repository.statisticsStore.applySessions(root, sessions)

        assertTrue(repository.loadReadingState(root).canMarkRead)
        repository.markRead(root)

        val bookmark = repository.loadBookmark(root)!!
        assertEquals(7, bookmark.chapterIndex)
        assertEquals(1.0, bookmark.progress, 0.0)
        assertEquals(200, bookmark.characterCount)
        assertNotNull(bookmark.lastModified)
        assertEquals(sessions, repository.loadSessions(root))
        assertEquals(BookReadingState(1.0, canMarkRead = true, canMarkUnread = true), repository.loadReadingState(root))
    }

    @Test fun markReadRequiresBookInfoEvenWhenAnEpubOrTotalCountExists() = runBlocking {
        for (epub in listOf(null, "book.epub")) {
            val root = book(if (epub == null) "cloud" else "local", epub)
            repository.saveBookmark(root, Bookmark(0, 0.5, 100, 10.0))
            val before = repository.loadBookmark(root)
            assertEquals(BookReadingState(0.5, canMarkRead = false, canMarkUnread = true), repository.loadReadingState(root))
            repository.markRead(root)
            assertEquals(before, repository.loadBookmark(root))
        }
    }

    @Test fun unreadActionRecognizesEachBookmarkCoordinateAndLiveStatistics() = runBlocking {
        val bookmarks = listOf(null, Bookmark(0, 0.0, 0), Bookmark(1, 0.0, 0), Bookmark(0, 0.1, 0), Bookmark(0, 0.0, 1))
        for ((index, bookmark) in bookmarks.withIndex()) {
            val root = book("book-$index")
            bookmark?.let { repository.saveBookmark(root, it) }
            assertEquals("bookmark=$bookmark", index >= 2, repository.loadReadingState(root).canMarkUnread)
        }
        val root = book("statistics-only")
        repository.statisticsStore.applySessions(root, mapOf("A" to Timestamped(1, ReadingSession(0, 10, 0, 1.0))))
        assertTrue(repository.loadReadingState(root).canMarkUnread)
        repository.statisticsStore.applySessions(root, mapOf("A" to Timestamped(2, null)))
        assertFalse(repository.loadReadingState(root).canMarkUnread)
    }

    @Test fun unreadResetSurvivesReloadStaleReaderSavesAndRemoteMerge() = runBlocking {
        val root = book()
        val sync = SyncStorage(temporary.root, repository, Dispatchers.IO)
        repository.saveBookInfo(root, BookInfo(200, mapOf("one" to BookInfo.ChapterInfo(0, 0, 200))))
        repository.saveBookmark(root, Bookmark(0, 0.75, 150, 10.0))
        val session = ReadingSession(0, 10, 150, 5.0)
        val sessions = mapOf("A" to Timestamped<ReadingSession?>(1, session), "DELETED" to Timestamped<ReadingSession?>(2, null))
        repository.statisticsStore.applySessions(root, sessions)
        val highlight = ReaderHighlight("H", 10, 0, "text", HighlightColor.Yellow, 0.0)
        repository.saveHighlights(root, listOf(highlight))
        sync.prepareLibrary()
        val oldRemote = sync.loadBook("book")!!
        sync.updateRecord("book") { it.copy(pending = false) }
        val before = repository.currentAppleReferenceDateSeconds()

        repository.markUnread(root)

        val reloaded = BookRepository(temporary.root)
        val bookmark = reloaded.loadBookmark(root)!!
        assertEquals(Bookmark(0, 0.0, 0, bookmark.lastModified), bookmark)
        assertTrue(bookmark.lastModified!! >= before)
        assertEquals(BookReadingState(0.0, true, false), reloaded.loadReadingState(root))
        assertTrue(sync.state.books.getValue("book").pending)
        assertNull(reloaded.loadSessions(root).getValue("A").value)
        assertEquals(sessions.getValue("DELETED"), reloaded.loadSessions(root).getValue("DELETED"))
        assertEquals(listOf(highlight), reloaded.loadHighlights(root))
        repository.statisticsStore.saveTrackedSession(root, "A", session.copy(charactersRead = 190))
        val merged = SyncBook.merge(oldRemote, sync.loadBook("book")!!)
        sync.applyBook("book", merged)
        assertEquals(0, repository.loadBookmark(root)!!.characterCount)
        assertTrue(repository.loadSessions(root).values.all { it.value == null })
        assertFalse(repository.loadReadingState(root).canMarkUnread)
    }

    @Test fun unreadResetIncludesLegacyAndArchivedSessionsWithoutChangingOtherBooks() = runBlocking {
        val root = book()
        root.resolve("statistics.json").writeText("""[{"title":"Book","dateKey":"2026-10-01","charactersRead":20,"readingTime":10.0}]""")
        val archive = temporary.root.resolve("Books/statistics_archive/book").apply { mkdirs() }
        repository.statisticsStore.applySessions(archive, mapOf("ARCHIVED" to Timestamped(1, ReadingSession(0, 10, 30))))
        val other = book("other")
        val otherSessions = mapOf("OTHER" to Timestamped<ReadingSession?>(1, ReadingSession(0, 10, 40)))
        repository.statisticsStore.applySessions(other, otherSessions)

        repository.markUnread(root)

        val sessions = repository.loadSessions(root)
        assertEquals(2, sessions.size)
        assertTrue(sessions.values.all { it.value == null })
        assertFalse(archive.exists())
        assertEquals(otherSessions, repository.loadSessions(other))
    }

    @Test fun unreadResetRetainsTombstonesThatExistOnlyInTheArchive() = runBlocking {
        val root = book()
        repository.saveBookmark(root, Bookmark(0, 0.5, 100, 10.0))
        val archive = temporary.root.resolve("Books/statistics_archive/book").apply { mkdirs() }
        val tombstones = mapOf("ARCHIVED" to Timestamped<ReadingSession?>(2, null))
        repository.statisticsStore.applySessions(archive, tombstones)

        repository.markUnread(root)

        assertEquals(tombstones, repository.loadSessions(root))
        assertFalse(archive.exists())
        val stale = mapOf("ARCHIVED" to Timestamped<ReadingSession?>(1, ReadingSession(0, 10, 100)))
        assertEquals(tombstones, SyncBook.mergeRecords(stale, repository.loadSessions(root)))
    }

    @Test fun removedBooksCannotBeRecreatedByStaleReadActions() = runBlocking {
        val root = book()
        root.deleteRecursively()
        repository.markUnread(root)
        repository.markRead(root)
        assertEquals(BookReadingState(), repository.loadReadingState(root))
        assertFalse(root.exists())
    }

    @Test fun corruptStatisticsDoNotBlockShelfStateOrGetOverwrittenByReset() = runBlocking {
        ErrorDiagnosticEvents.buffer.drain()
        try {
            val root = book()
            repository.saveBookInfo(root, BookInfo(200, emptyMap()))
            root.resolve("statistics.json").writeText("broken statistics")

            assertEquals(BookReadingState(canMarkRead = true), repository.loadReadingState(root))
            assertEquals(1, ErrorDiagnosticEvents.buffer.drain().size)
            assertTrue(runCatching { repository.markUnread(root) }.isFailure)
            assertEquals("broken statistics", root.resolve("statistics.json").readText())
            assertNull(repository.loadBookmark(root))

            repository.saveBookmark(root, Bookmark(0, 0.5, 100, 10.0))
            assertTrue(repository.loadReadingState(root).canMarkUnread)
            assertTrue(runCatching { repository.markUnread(root) }.isFailure)
            assertEquals(100, repository.loadBookmark(root)!!.characterCount)
        } finally {
            ErrorDiagnosticEvents.buffer.drain()
        }
    }

    @Test fun resetFinishesBothSidecarsWhenTheCallerIsCancelled() = runBlocking {
        val root = book()
        repository.saveBookmark(root, Bookmark(0, 0.5, 100, 10.0))
        repository.statisticsStore.applySessions(root, mapOf("A" to Timestamped(1, ReadingSession(0, 10, 100))))
        val deleted = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        repository.statisticsStore.onSave = { _, _ -> deleted.complete(Unit); resume.await() }
        val reset = launch { repository.markUnread(root) }
        deleted.await()
        reset.cancel()
        resume.complete(Unit)
        reset.join()

        assertEquals(0, repository.loadBookmark(root)!!.characterCount)
        assertNull(repository.loadSessions(root).getValue("A").value)
    }
}
