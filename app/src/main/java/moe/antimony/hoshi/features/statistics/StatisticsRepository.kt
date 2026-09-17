package moe.antimony.hoshi.features.statistics

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.epub.BookStatisticsStore
import moe.antimony.hoshi.epub.ReadingStatistics

internal interface StatisticsRepository {
    suspend fun loadSnapshot(): StatisticsSnapshot
    suspend fun loadBookStatistics(folder: String): StatisticsBookRecords?
    suspend fun updateDay(folder: String, dateKey: String, characters: Int, totalMinutes: Int)
    suspend fun deleteDay(folder: String, dateKey: String)
    suspend fun deleteAll(folder: String)
    suspend fun loadArchiveSummary(): Int
    suspend fun clearArchive()
}

internal data class StatisticsBookRecords(
    val folder: String,
    val title: String,
    val isArchived: Boolean,
    val statistics: List<ReadingStatistics>,
)

internal data class StatisticsSnapshot(
    val days: List<StatisticsDayAggregate>,
    val availableYears: List<Int>,
    val skippedCorruptBookIds: Set<String> = emptySet(),
)

@Singleton
internal class AndroidStatisticsRepository @Inject constructor(
    private val statisticsStore: BookStatisticsStore,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StatisticsRepository {
    override suspend fun loadSnapshot(): StatisticsSnapshot = withContext(ioDispatcher) {
        val stored = statisticsStore.loadSnapshot()
        val contributionsByDate = linkedMapOf<LocalDate, MutableList<StatisticsBookContribution>>()
        stored.books.forEach { book ->
            book.statistics.forEach statisticLoop@ { statistic ->
                val date = runCatching { LocalDate.parse(statistic.dateKey) }.getOrNull() ?: return@statisticLoop
                contributionsByDate.getOrPut(date) { mutableListOf() } += StatisticsBookContribution(
                    bookId = book.metadata.id,
                    title = book.metadata.displayTitle.ifBlank { statistic.title.ifBlank { book.folder } },
                    coverPath = book.coverPath,
                    characters = statistic.charactersRead,
                    readingSeconds = statistic.readingTime,
                    folder = book.folder,
                    isArchived = book.isArchived,
                )
            }
        }
        val days = contributionsByDate.toSortedMap().map { (date, contributions) ->
            StatisticsDayAggregate(
                date = date,
                totalCharacters = contributions.sumOf { it.characters },
                readingSeconds = contributions.sumOf { it.readingSeconds },
                activeBookCount = contributions.size,
                bookContributions = contributions.sortedBy { it.title.lowercase() },
            )
        }
        StatisticsSnapshot(days, days.map { it.date.year }.distinct().sortedDescending(), stored.corruptBookIds)
    }

    override suspend fun loadBookStatistics(folder: String): StatisticsBookRecords? =
        statisticsStore.loadBook(folder)?.let { book ->
            StatisticsBookRecords(book.folder, book.metadata.displayTitle.ifBlank { book.folder }, book.isArchived, book.statistics)
        }

    override suspend fun updateDay(folder: String, dateKey: String, characters: Int, totalMinutes: Int) =
        statisticsStore.updateDay(folder, dateKey, characters, totalMinutes)

    override suspend fun deleteDay(folder: String, dateKey: String) = statisticsStore.deleteDay(folder, dateKey)
    override suspend fun deleteAll(folder: String) = statisticsStore.deleteAll(folder)
    override suspend fun loadArchiveSummary(): Int = statisticsStore.loadArchiveSummary()
    override suspend fun clearArchive() = statisticsStore.clearArchive()
}
