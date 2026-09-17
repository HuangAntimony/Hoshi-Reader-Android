package moe.antimony.hoshi.epub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookStatisticsStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun archiveAndRestoreUseOppositeFirstOnTieOrder() = runBlocking {
        val files = temporary.newFolder()
        val repository = BookRepository(files)
        val store = repository.statisticsStore
        val active = repository.createBookDirectory("book")
        repository.saveStatistics(active, listOf(day(20)))
        val archive = files.resolve("Books/statistics_archive/book").apply { mkdirs() }
        repository.saveMetadata(archive, BookMetadata("archived", "Book", null, "book", 0.0))
        repository.saveStatistics(archive, listOf(day(10)))
        repository.deleteBook(active)
        assertEquals(10, repository.loadStatistics(archive).single().charactersRead)
        val restored = repository.createBookDirectory("book")
        repository.saveStatistics(restored, listOf(day(30)))
        store.restore("book")
        assertEquals(30, repository.loadStatistics(restored).single().charactersRead)
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

    @Test fun editsLatestDiskSelectedDayAndMaintainsSpeedExtrema() = runBlocking {
        val repository = BookRepository(temporary.newFolder())
        val root = repository.createBookDirectory("book")
        repository.saveStatistics(root, listOf(day(10).copy(minReadingSpeed = 300, altMinReadingSpeed = 700, maxReadingSpeed = 500), day(70).copy(dateKey = "2026-09-02")))
        repository.statisticsStore.updateDay("book", "2026-09-01", 100, 1)
        val days = repository.loadStatistics(root)
        val updated = days.first()
        assertEquals(6000, updated.lastReadingSpeed)
        assertEquals(6000, updated.maxReadingSpeed)
        assertEquals(300, updated.minReadingSpeed)
        assertEquals(700, updated.altMinReadingSpeed)
        assertEquals(70, days.last().charactersRead)
        assertTrue(updated.lastStatisticModified > 10)
        repository.statisticsStore.deleteDay("book", "2026-09-01")
        assertTrue(runCatching { repository.statisticsStore.updateDay("book", "2026-09-01", 42, 1) }.isFailure)
        assertEquals(listOf("2026-09-02"), repository.loadStatistics(root).map { it.dateKey })
    }

    @Test fun emptyArchiveIsRemovedButEmptyActiveStatisticsFileIsRetained() = runBlocking {
        val files = temporary.newFolder()
        val repository = BookRepository(files)
        val root = repository.createBookDirectory("book")
        repository.saveStatistics(root, listOf(day(10)))
        repository.statisticsStore.updateDay("book", "2026-09-01", -1, -1)
        assertTrue(root.resolve("statistics.json").isFile)
        assertTrue(repository.loadStatistics(root).isEmpty())
        repository.saveStatistics(root, listOf(day(10)))
        repository.deleteBook(root)
        repository.statisticsStore.deleteDay("book", "2026-09-01")
        assertFalse(files.resolve("Books/statistics_archive/book").exists())
        assertTrue(runCatching { repository.statisticsStore.deleteAll("unknown") }.isFailure)
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
        assertEquals(42, stored.statistics.single().charactersRead)
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
        assertEquals(20, snapshot.books.single().statistics.single().charactersRead)
        repository.restoreArchivedStatistics(composed)
        assertFalse(archive.exists())
        assertEquals(20, repository.loadStatistics(active).single().charactersRead)
        assertEquals(composed.repeat(100).toImportedBookStorageName(), decomposed.repeat(100).toImportedBookStorageName())
    }

    @Test fun queuedReaderSavesCannotUndoLaterEditsOrDeletes() = runBlocking {
        val repository = BookRepository(temporary.newFolder())
        val root = repository.createBookDirectory("book")
        val queued = day(10)
        val unaffected = day(20).copy(dateKey = "2026-09-02")
        repository.saveStatistics(root, listOf(queued, unaffected))
        repository.statisticsStore.updateDay("book", queued.dateKey, 100, 10)
        repository.saveTrackedStatistics(root, listOf(queued.copy(charactersRead = 15)))
        assertEquals(100, repository.loadStatistics(root).first().charactersRead)
        repository.statisticsStore.deleteDay("book", queued.dateKey)
        repository.saveTrackedStatistics(root, listOf(queued))
        assertEquals(listOf(unaffected), repository.loadStatistics(root))
        repository.statisticsStore.deleteAll("book")
        repository.saveTrackedStatistics(root, listOf(queued.copy(dateKey = "2026-09-03")))
        assertTrue(repository.loadStatistics(root).isEmpty())
        repository.saveTrackedStatistics(root, listOf(queued.copy(lastStatisticModified = System.currentTimeMillis() + 1000)))
        assertEquals(10, repository.loadStatistics(root).single().charactersRead)
    }

    @Test fun deletingLastHistoricalDayDoesNotDiscardAQueuedDifferentReadingDay() = runBlocking {
        val repository = BookRepository(temporary.newFolder())
        val root = repository.createBookDirectory("book")
        repository.saveStatistics(root, listOf(day(10)))
        repository.statisticsStore.deleteDay("book", "2026-09-01")
        val queued = day(30).copy(dateKey = "2026-09-02")
        repository.saveTrackedStatistics(root, listOf(queued))
        assertEquals(listOf(queued), repository.loadStatistics(root))
    }

    @Test fun transactionalUpdateUsesLatestPersistedDay() = runBlocking {
        val repository = BookRepository(temporary.newFolder())
        val root = repository.createBookDirectory("book")
        repository.saveStatistics(root, listOf(day(10)))
        repository.statisticsStore.updateDay("book", "2026-09-01", 100, 10)
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
