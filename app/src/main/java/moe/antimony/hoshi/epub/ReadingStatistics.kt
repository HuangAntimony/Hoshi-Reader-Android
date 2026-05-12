package moe.antimony.hoshi.epub

import kotlinx.serialization.Serializable

@Serializable
data class ReadingStatistics(
    val title: String,
    val dateKey: String,
    val charactersRead: Int = 0,
    val readingTime: Double = 0.0,
    val minReadingSpeed: Int = 0,
    val altMinReadingSpeed: Int = 0,
    val lastReadingSpeed: Int = 0,
    val maxReadingSpeed: Int = 0,
    val lastStatisticModified: Long = 0,
)

fun List<ReadingStatistics>.deduplicateReadingStatistics(): List<ReadingStatistics> =
    fold(linkedMapOf<String, ReadingStatistics>()) { grouped, statistic ->
        val existing = grouped[statistic.dateKey]
        if (existing == null || statistic.lastStatisticModified > existing.lastStatisticModified) {
            grouped[statistic.dateKey] = statistic
        }
        grouped
    }.values.toList()
