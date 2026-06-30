package moe.antimony.hoshi.features.statistics

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.deduplicateReadingStatistics

internal interface StatisticsRepository {
    suspend fun loadSnapshot(): StatisticsSnapshot
}

internal data class StatisticsSnapshot(
    val days: List<StatisticsDayAggregate>,
    val availableYears: List<Int>,
    val skippedCorruptBookIds: Set<String> = emptySet(),
)

@Singleton
internal class AndroidStatisticsRepository @Inject constructor(
    private val bookRepository: BookRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : StatisticsRepository {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    override suspend fun loadSnapshot(): StatisticsSnapshot = withContext(ioDispatcher) {
        val skippedCorruptBookIds = linkedSetOf<String>()
        val contributionsByDate = linkedMapOf<LocalDate, MutableList<StatisticsBookContribution>>()
        bookRepository.loadBookEntries(BookSortOption.Recent).forEach { entry ->
            val statisticsFile = entry.root.resolve(StatisticsFileName)
            if (!statisticsFile.isFile) {
                return@forEach
            }
            val statistics = runCatching {
                json.decodeFromString(
                    ListSerializer(ReadingStatistics.serializer()),
                    statisticsFile.readText(),
                ).deduplicateReadingStatistics()
            }.getOrElse {
                skippedCorruptBookIds += entry.metadata.id
                return@forEach
            }
            val coverPath = bookRepository.coverFile(entry)?.absolutePath
            statistics.forEach { statistic ->
                val date = statistic.dateKey.toLocalDateOrNull() ?: return@forEach
                if (statistic.charactersRead <= 0 && statistic.readingTime <= 0.0) {
                    return@forEach
                }
                contributionsByDate.getOrPut(date) { mutableListOf() } += StatisticsBookContribution(
                    bookId = entry.metadata.id,
                    title = entry.statisticsTitle(statistic),
                    coverPath = coverPath,
                    characters = statistic.charactersRead,
                    readingSeconds = statistic.readingTime,
                )
            }
        }
        val days = contributionsByDate
            .toSortedMap()
            .map { (date, contributions) ->
                StatisticsDayAggregate(
                    date = date,
                    totalCharacters = contributions.sumOf { it.characters },
                    readingSeconds = contributions.sumOf { it.readingSeconds },
                    activeBookCount = contributions.count { it.characters > 0 || it.readingSeconds > 0.0 },
                    bookContributions = contributions.sortedBy { it.title.lowercase() },
                )
            }
        StatisticsSnapshot(
            days = days,
            availableYears = days.map { it.date.year }.distinct().sortedDescending(),
            skippedCorruptBookIds = skippedCorruptBookIds,
        )
    }

    private fun BookEntry.statisticsTitle(statistic: ReadingStatistics): String =
        displayTitle.ifBlank {
            statistic.title.ifBlank { root.name }
        }
}

private fun String.toLocalDateOrNull(): LocalDate? =
    takeIf { it.isNotBlank() }?.let { raw ->
        runCatching { LocalDate.parse(raw) }.getOrNull()
    }

private const val StatisticsFileName = "statistics.json"
