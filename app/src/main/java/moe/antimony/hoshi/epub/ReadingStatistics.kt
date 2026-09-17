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
    }.values.sortedBy { it.dateKey }

internal val ReadingStatistics.hasActivity: Boolean
    get() = charactersRead > 0 || readingTime > 0.0

internal fun ReadingStatistics.updated(characters: Int, seconds: Double): ReadingStatistics {
    val clampedCharacters = characters.coerceAtLeast(0)
    val clampedSeconds = seconds.coerceAtLeast(0.0)
    val speed = if (clampedSeconds > 0.0) (clampedCharacters.toDouble() / clampedSeconds * 3600.0).toInt() else 0
    return copy(
        charactersRead = clampedCharacters,
        readingTime = clampedSeconds,
        lastReadingSpeed = speed,
        minReadingSpeed = if (minReadingSpeed == 0) speed else minOf(minReadingSpeed, speed),
        maxReadingSpeed = maxOf(maxReadingSpeed, speed),
        lastStatisticModified = System.currentTimeMillis(),
    )
}
