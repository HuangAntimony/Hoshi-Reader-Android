package moe.antimony.hoshi.epub

import kotlinx.coroutines.Dispatchers
import moe.antimony.hoshi.features.sync.Timestamped
import moe.antimony.hoshi.features.sync.TtuStatistics
import moe.antimony.hoshi.features.sync.SyncFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookStatisticsStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun archiveAndRestoreKeepLiveSessionsFirstOnEqualStampsLikeIos() = runBlocking {
        val files = temporary.newFolder()
        val repository = BookRepository(files)
        val store = repository.statisticsStore
        val active = repository.createBookDirectory("book")
        repository.saveMetadata(active, BookMetadata("active", "Book", folder = "book", lastAccess = 0.0))
        val archive = files.resolve("Books/statistics_archive/book").apply { mkdirs() }
        repository.saveMetadata(archive, BookMetadata("archived", "Book", folder = "book", lastAccess = 0.0))
        store.applySessions(active, mapOf("same" to Timestamped(10, ReadingSession(0, 1, 20))))
        store.applySessions(archive, mapOf("same" to Timestamped(10, ReadingSession(0, 1, 10))))
        repository.deleteBook(active)
        assertEquals(20, store.loadSessions(archive).getValue("same").value!!.charactersRead)
        val restored = repository.createBookDirectory("book")
        store.applySessions(restored, mapOf("same" to Timestamped(10, ReadingSession(0, 1, 30))))
        store.restore("book")
        assertEquals(30, store.loadSessions(restored).getValue("same").value!!.charactersRead)
        assertFalse(archive.exists())
    }

    @Test fun failedRestoreKeepsArchiveAndExistingActiveFile() = runBlocking {
        val files = temporary.newFolder()
        val repository = BookRepository(files)
        val root = repository.createBookDirectory("book")
        repository.saveStatistics(root, listOf(day(10)))
        repository.deleteBook(root)
        repository.createBookDirectory("book").resolve("statistics.json").writeText("broken")
        assertTrue(runCatching { repository.statisticsStore.restore("book") }.isFailure)
        assertTrue(files.resolve("Books/statistics_archive/book/statistics.json").isFile)
        assertEquals("broken", root.resolve("statistics.json").readText())
    }

    @Test fun sessionEditsChangeOnlyRequestedFieldsAndDeletionPersists() = runBlocking {
        val repository = BookRepository(temporary.newFolder())
        val root = repository.createBookDirectory("book")
        val store = repository.statisticsStore
        val original = ReadingSession(1000, 2000, 10, 1.5)
        store.applySessions(root, mapOf("A" to Timestamped(10, original), "B" to Timestamped(20, original)))
        store.edit("A", "book", 100, null)
        assertEquals(original.copy(charactersRead = 100), store.loadSessions(root).getValue("A").value)
        assertEquals(Timestamped(20, original), store.loadSessions(root).getValue("B"))
        store.delete(listOf("A"), "book")
        store.edit("A", "book", 42, 60.0)
        assertNull(store.loadSessions(root).getValue("A").value)
        assertNull(BookRepository(root.parentFile!!.parentFile!!).loadSessions(root).getValue("A").value)
    }

    @Test fun emptyArchiveRetainsMetadataAndTombstonesAndLosesCover() = runBlocking {
        val files = temporary.newFolder()
        val repository = BookRepository(files)
        val root = repository.createBookDirectory("book")
        repository.saveStatistics(root, listOf(day(10)))
        repository.deleteBook(root)
        val archive = files.resolve("Books/statistics_archive/book")
        archive.resolve("cover.jpg").writeText("cover")
        repository.statisticsStore.clearArchive()
        assertTrue(archive.resolve("metadata.json").isFile)
        assertFalse(archive.resolve("cover.jpg").exists())
        assertTrue(repository.loadSessions(archive).values.all { it.value == null })
        assertEquals(0, repository.statisticsStore.loadArchiveSummary())
    }

    @Test fun requiredArchiveWriteFailurePreservesBook() = runBlocking {
        val files = temporary.newFolder()
        val repository = BookRepository(files)
        val root = repository.createBookDirectory("book")
        repository.saveStatistics(root, listOf(day(10)))
        files.resolve("Books/statistics_archive").writeText("blocked")
        assertTrue(runCatching { repository.deleteBook(root) }.isFailure)
        assertTrue(root.exists())
        assertEquals(10, repository.loadStatistics(root).single().charactersRead)
    }

    @Test fun corruptDestinationStatisticsAndFailedMetadataWritePreserveSourceBook() = runBlocking {
        val files = temporary.newFolder()
        val repository = BookRepository(files)
        val root = repository.createBookDirectory("book")
        repository.saveStatistics(root, listOf(day(10)))
        val archive = files.resolve("Books/statistics_archive/book").apply { mkdirs() }
        archive.resolve("statistics.json").writeText("broken")
        assertTrue(runCatching { repository.deleteBook(root) }.isFailure)
        assertTrue(root.exists())
        assertEquals("broken", archive.resolve("statistics.json").readText())
        archive.resolve("statistics.json").delete()
        archive.resolve("metadata.json").mkdirs()
        assertTrue(runCatching { repository.deleteBook(root) }.isFailure)
        assertTrue(root.exists())
        assertEquals(10, repository.loadStatistics(root).single().charactersRead)
    }

    @Test fun iosArchiveMetadataAllowsOmittedOptionalCover() = runBlocking {
        val files = temporary.newFolder()
        val archive = files.resolve("Books/statistics_archive/book").apply { mkdirs() }
        archive.resolve("metadata.json").writeText("""{"id":"ios-id","title":"iOS title","author":"Author","folder":"book","lastAccess":0}""")
        archive.resolve("statistics.json").writeText("""[{"title":"Book","dateKey":"2026-09-01","charactersRead":42}]""")
        val stored = BookRepository(files).statisticsStore.loadBook("book")!!
        assertEquals("iOS title", stored.metadata.title)
        assertNull(stored.coverPath)
        assertEquals(42, stored.days.single().total.charactersRead)
    }

    @Test fun duplicateDaysSortByDateAndKeepFirstOnEqualTimestamp() {
        val days = listOf(day(1).copy(dateKey = "2026-09-03"), day(2), day(3), day(4).copy(lastStatisticModified = 9))
            .deduplicateReadingStatistics()
        assertEquals(listOf("2026-09-01", "2026-09-03"), days.map { it.dateKey })
        assertEquals(listOf(2, 1), days.map { it.charactersRead })
    }

    @Test fun reservedImportNameIsStableAndNotAnArchiveDirectory() = runBlocking {
        val repository = BookRepository(temporary.newFolder())
        val first = repository.createBookDirectoryForImportedTitle("statistics_archive")
        assertNotEquals("statistics_archive", first.name)
        assertEquals(first, repository.createBookDirectoryForImportedTitle("statistics_archive"))
    }

    @Test fun oldReservedTitleBookRemainsVisibleAfterMigration() = runBlocking {
        val files = temporary.newFolder()
        val legacy = files.resolve("Books/statistics_archive").apply { mkdirs() }
        legacy.resolve("metadata.json").writeText("""{"id":"legacy","title":"statistics_archive","cover":"Books/statistics_archive/cover.png","folder":"statistics_archive","lastAccess":0,"epub":"statistics_archive.epub"}""")
        legacy.resolve("statistics_archive.epub").writeText("packed")
        legacy.resolve("cover.png").writeText("cover")
        val repository = BookRepository(files)
        val migrated = repository.loadAllBooks().single()
        assertNotEquals("statistics_archive", migrated.name)
        assertEquals(migrated.name, repository.loadMetadata(migrated)?.folder)
        assertEquals("Books/${migrated.name}/cover.png", repository.loadMetadata(migrated)?.cover)
        assertTrue(migrated.resolve("statistics_archive.epub").isFile)
    }

    @Test fun unicodeEquivalentArchiveAndCurrentFolderShareOneIdentityAndRestore() = runBlocking {
        val files = temporary.newFolder()
        val repository = BookRepository(files)
        val composed = "ビブリア"
        val decomposed = java.text.Normalizer.normalize(composed, java.text.Normalizer.Form.NFD)
        val archive = files.resolve("Books/statistics_archive/$decomposed").apply { mkdirs() }
        repository.saveStatistics(archive, listOf(day(10)))
        val active = repository.createBookDirectory(composed)
        repository.saveStatistics(active, listOf(day(20)))
        val snapshot = repository.statisticsStore.loadSnapshot()
        assertEquals(1, snapshot.books.size)
        assertEquals(20, snapshot.books.single().days.single().total.charactersRead)
        repository.restoreArchivedStatistics(composed)
        assertFalse(archive.exists())
        assertEquals(20, repository.loadStatistics(active).single().charactersRead)
        assertEquals(composed.repeat(100).toImportedBookStorageName(), decomposed.repeat(100).toImportedBookStorageName())
    }

    @Test fun readerSaveOverwritesAnEditButCannotUndoDeletion() = runBlocking {
        val repository = BookRepository(temporary.newFolder())
        val root = repository.createBookDirectory("book")
        val original = ReadingSession(0, 1000, 10, 1.0)
        val store = repository.statisticsStore
        store.saveTrackedSession(root, "A", original)
        store.edit("A", "book", 100, null)
        store.saveTrackedSession(root, "A", original.copy(charactersRead = 15))
        assertEquals(15, store.loadSessions(root).getValue("A").value!!.charactersRead)
        store.delete(listOf("A"), "book")
        store.saveTrackedSession(root, "A", original)
        assertNull(store.loadSessions(root).getValue("A").value)
    }

    @Test fun deletingKnownSessionsPreservesAnUnseenOfflineSession() = runBlocking {
        val repository = BookRepository(temporary.newFolder())
        val root = repository.createBookDirectory("book")
        val store = repository.statisticsStore
        val session = ReadingSession(0, 1000, 10, 1.0)
        store.saveTrackedSession(root, "A", session)
        val known = store.loadSessions(root).keys
        store.saveTrackedSession(root, "B", session)
        store.delete(known, "book")
        assertNull(store.loadSessions(root).getValue("A").value)
        assertEquals(session, store.loadSessions(root).getValue("B").value)
    }

    @Test fun transactionalUpdateUsesLatestPersistedDay() = runBlocking {
        val repository = BookRepository(temporary.newFolder())
        val root = repository.createBookDirectory("book")
        repository.saveStatistics(root, listOf(day(10)))
        repository.statisticsStore.edit(repository.loadSessions(root).keys.single(), "book", 100, 600.0)
        repository.updateStatistics(root) { latest ->
            assertEquals(100, latest.single().charactersRead)
            latest + day(30).copy(dateKey = "2026-09-02")
        }
        assertEquals(listOf(100, 30), repository.loadStatistics(root).map { it.charactersRead })
    }

    @Test fun importingCanonicalTitleReusesLegacyEquivalentPhysicalFolder() = runBlocking {
        val files = temporary.newFolder()
        val composed = "ビブリア"
        val decomposed = java.text.Normalizer.normalize(composed, java.text.Normalizer.Form.NFD)
        val legacy = files.resolve("Books/$decomposed").apply { mkdirs() }
        val repository = BookRepository(files)
        val imported = repository.createBookDirectoryForImportedTitle(composed)
        assertEquals(legacy.canonicalFile, imported.canonicalFile)
        assertEquals(1, repository.loadAllBooks().size)
    }

    private fun day(characters: Int) = ReadingStatistics("Book", "2026-09-01", charactersRead = characters, lastStatisticModified = 10)
}
