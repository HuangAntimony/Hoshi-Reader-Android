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

        val snapshot = AndroidStatisticsRepository(bookRepository, Dispatchers.IO).loadSnapshot()
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

        val day = AndroidStatisticsRepository(bookRepository, Dispatchers.IO).loadSnapshot().days.single()

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

        val snapshot = AndroidStatisticsRepository(bookRepository, Dispatchers.IO).loadSnapshot()

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

        val snapshot = AndroidStatisticsRepository(bookRepository, Dispatchers.IO).loadSnapshot()

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

        val snapshot = AndroidStatisticsRepository(bookRepository, Dispatchers.IO).loadSnapshot()

        assertEquals(listOf("2026-06-30"), snapshot.days.map { it.date.toString() })
        assertTrue(snapshot.skippedCorruptBookIds.isEmpty())
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
