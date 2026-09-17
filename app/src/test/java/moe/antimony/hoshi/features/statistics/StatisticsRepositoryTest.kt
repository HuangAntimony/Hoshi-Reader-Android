package moe.antimony.hoshi.features.statistics

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ReadingStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StatisticsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun aggregatesSameDateAcrossBooksWithBookContributions() = runBlocking {
        val bookRepository = BookRepository(tempFolder.newFolder("app"))
        val alpha = bookRepository.createStoredBook(
            id = AlphaId,
            folder = "alpha",
            title = "Alpha",
            statistics = listOf(ReadingStatistics("Alpha", "2026-06-30", charactersRead = 1_000, readingTime = 600.0)),
        )
        bookRepository.createStoredBook(
            id = BetaId,
            folder = "beta",
            title = "Beta",
            statistics = listOf(ReadingStatistics("Beta", "2026-06-30", charactersRead = 2_000, readingTime = 900.0)),
        )

        val snapshot = AndroidStatisticsRepository(bookRepository.statisticsStore).loadSnapshot()
        val day = snapshot.days.single()

        assertEquals(alpha.name, "alpha")
        assertEquals(LocalDateString, day.date.toString())
        assertEquals(3_000, day.totalCharacters)
        assertEquals(1_500.0, day.readingSeconds, 0.0)
        assertEquals(2, day.activeBookCount)
        assertEquals(listOf(AlphaId, BetaId).sorted(), day.bookContributions.map { it.bookId }.sorted())
        assertEquals(listOf("Alpha", "Beta"), day.bookContributions.map { it.title }.sorted())
    }

    @Test
    fun deduplicatesPerBookDateKeyUsingLatestModifiedStatistic() = runBlocking {
        val bookRepository = BookRepository(tempFolder.newFolder("app"))
        val root = bookRepository.createStoredBook(
            id = "book-id",
            folder = "book",
            title = "Book",
            statistics = emptyList(),
        )
        root.resolve("statistics.json").writeText(
            """
            [
              {"title":"Book","dateKey":"2026-06-30","charactersRead":100,"readingTime":10.0,"lastStatisticModified":100},
              {"title":"Book","dateKey":"2026-06-30","charactersRead":300,"readingTime":30.0,"lastStatisticModified":300}
            ]
            """.trimIndent(),
        )

        val day = AndroidStatisticsRepository(bookRepository.statisticsStore).loadSnapshot().days.single()

        assertEquals(300, day.totalCharacters)
        assertEquals(30.0, day.readingSeconds, 0.0)
    }

    @Test
    fun skipsInvalidAndMissingStatisticsWithoutThrowing() = runBlocking {
        val bookRepository = BookRepository(tempFolder.newFolder("app"))
        bookRepository.createStoredBook(
            id = "valid-id",
            folder = "valid",
            title = "Valid",
            statistics = listOf(ReadingStatistics("Valid", "2025-12-31", charactersRead = 1_000, readingTime = 600.0)),
        )
        val invalid = bookRepository.createStoredBook(
            id = InvalidId,
            folder = "invalid",
            title = "Invalid",
            statistics = emptyList(),
        )
        invalid.resolve("statistics.json").writeText("{ broken")
        bookRepository.createStoredBook(
            id = "missing-id",
            folder = "missing",
            title = "Missing",
            statistics = emptyList(),
        ).resolve("statistics.json").delete()

        val snapshot = AndroidStatisticsRepository(bookRepository.statisticsStore).loadSnapshot()

        assertEquals(listOf("2025-12-31"), snapshot.days.map { it.date.toString() })
        assertEquals(setOf(InvalidId), snapshot.skippedCorruptBookIds)
    }

    @Test
    fun availableYearsComeFromValidStatisticsRecordsDescending() = runBlocking {
        val bookRepository = BookRepository(tempFolder.newFolder("app"))
        bookRepository.createStoredBook(
            id = "book-id",
            folder = "book",
            title = "Book",
            statistics = listOf(
                ReadingStatistics("Book", "2024-05-01", charactersRead = 100),
                ReadingStatistics("Book", "2026-06-30", charactersRead = 100),
                ReadingStatistics("Book", "2025-01-01", charactersRead = 100),
            ),
        )

        val snapshot = AndroidStatisticsRepository(bookRepository.statisticsStore).loadSnapshot()

        assertEquals(listOf(2026, 2025, 2024), snapshot.availableYears)
    }

    @Test
    fun ignoresBlankUnparseableAndInactiveRecords() = runBlocking {
        val bookRepository = BookRepository(tempFolder.newFolder("app"))
        val root = bookRepository.createStoredBook(
            id = "book-id",
            folder = "book",
            title = "Book",
            statistics = emptyList(),
        )
        root.resolve("statistics.json").writeText(
            """
            [
              {"title":"Book","dateKey":"","charactersRead":100,"readingTime":10.0,"lastStatisticModified":100},
              {"title":"Book","dateKey":"not-a-date","charactersRead":100,"readingTime":10.0,"lastStatisticModified":100},
              {"title":"Book","dateKey":"2026-06-29","charactersRead":0,"readingTime":0.0,"lastStatisticModified":100},
              {"title":"Book","dateKey":"2026-06-30","charactersRead":100,"readingTime":10.0,"lastStatisticModified":100}
            ]
            """.trimIndent(),
        )

        val snapshot = AndroidStatisticsRepository(bookRepository.statisticsStore).loadSnapshot()

        assertEquals(listOf("2026-06-30"), snapshot.days.map { it.date.toString() })
        assertTrue(snapshot.skippedCorruptBookIds.isEmpty())
    }

    @Test
    fun archiveAndActiveDuplicateAreCoalescedUsingActiveIdentity() = runBlocking {
        val files = tempFolder.newFolder("app")
        val books = BookRepository(files)
        val active = books.createStoredBook("active-id", "book", "Current title", listOf(ReadingStatistics("Book", "2026-06-30", charactersRead = 20, lastStatisticModified = 10)))
        val archive = files.resolve("Books/statistics_archive/book").apply { mkdirs() }
        books.saveMetadata(archive, BookMetadata("archive-id", "Old title", null, "book", 0.0))
        books.saveStatistics(archive, listOf(
            ReadingStatistics("Book", "2026-06-30", charactersRead = 99, lastStatisticModified = 10),
            ReadingStatistics("Book", "2026-06-29", charactersRead = 5),
        ))
        val repository = AndroidStatisticsRepository(books.statisticsStore)
        val snapshot = repository.loadSnapshot()
        assertEquals(listOf(5, 20), snapshot.days.map { it.totalCharacters })
        assertTrue(snapshot.days.all { it.activeBookCount == 1 })
        assertTrue(snapshot.days.flatMap { it.bookContributions }.all { it.bookId == "active-id" && it.folder == "book" && !it.isArchived })
        repository.deleteDay("book", "2026-06-30")
        assertEquals(listOf("2026-06-29"), books.loadStatistics(active).map { it.dateKey })
        assertTrue(!archive.exists())
    }

    @Test
    fun archivedBooksAreEditableAndCorruptArchiveDoesNotHideOtherBooks() = runBlocking {
        val files = tempFolder.newFolder("app")
        val books = BookRepository(files)
        val root = books.createStoredBook("active-id", "book", "Archived title", listOf(ReadingStatistics("Book", "2026-06-30", charactersRead = 20)))
        books.deleteBook(root)
        val corrupt = files.resolve("Books/statistics_archive/corrupt").apply { mkdirs() }
        corrupt.resolve("statistics.json").writeText("broken")
        val repository = AndroidStatisticsRepository(books.statisticsStore)
        assertEquals(1, repository.loadArchiveSummary())
        assertEquals(setOf("corrupt"), repository.loadSnapshot().skippedCorruptBookIds)
        assertTrue(repository.loadBookStatistics("book")!!.isArchived)
        repository.updateDay("book", "2026-06-30", 60, 2)
        assertEquals(120.0, repository.loadBookStatistics("book")!!.statistics.single().readingTime, 0.0)
        repository.deleteAll("book")
        assertEquals(null, repository.loadBookStatistics("book"))
        repository.clearArchive()
        assertEquals(0, repository.loadArchiveSummary())
    }

    private suspend fun BookRepository.createStoredBook(
        id: String,
        folder: String,
        title: String,
        statistics: List<ReadingStatistics>,
    ): File {
        val root = createBookDirectory(folder)
        saveMetadata(
            root,
            BookMetadata(
                id = id,
                title = title,
                cover = null,
                folder = folder,
                lastAccess = 0.0,
            ),
        )
        if (statistics.isNotEmpty()) {
            saveStatistics(root, statistics)
        }
        return root
    }

    private companion object {
        const val AlphaId = "11111111-1111-4111-8111-111111111111"
        const val BetaId = "22222222-2222-4222-8222-222222222222"
        const val InvalidId = "33333333-3333-4333-8333-333333333333"
        const val LocalDateString = "2026-06-30"
    }
}
