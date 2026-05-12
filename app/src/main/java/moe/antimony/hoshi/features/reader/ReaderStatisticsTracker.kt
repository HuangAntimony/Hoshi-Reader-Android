package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

data class ReaderStatisticsState(
    val isTracking: Boolean,
    val session: ReadingStatistics,
    val today: ReadingStatistics,
    val allTime: ReadingStatistics,
)

interface ReaderStatisticsClock {
    fun currentTimeMillis(): Long
    fun currentDate(): LocalDate
}

object SystemReaderStatisticsClock : ReaderStatisticsClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun currentDate(): LocalDate = LocalDate.now(ZoneId.systemDefault())
}

class ReaderStatisticsTracker(
    private val title: String,
    initialStatistics: List<ReadingStatistics>,
    private val enabled: Boolean,
    private val clock: ReaderStatisticsClock = SystemReaderStatisticsClock,
) {
    private var statistics = initialStatistics.deduplicateReadingStatistics()
    private var lastTimestampMillis: Long = clock.currentTimeMillis()
    private var lastCharacterCount: Int = 0
    private var hasUpdated = false

    var state: ReaderStatisticsState = ReaderStatisticsState(
        isTracking = false,
        session = defaultStatistic(clock.currentDate()),
        today = statisticForDate(clock.currentDate()),
        allTime = allTimeStatistic(statistics),
    )
        private set

    fun start(currentCharacter: Int) {
        if (!enabled) return
        state = state.copy(isTracking = true)
        resetBaseline(currentCharacter)
    }

    fun startForPageTurnIfNeeded(currentCharacter: Int) {
        if (!state.isTracking) {
            start(currentCharacter)
        }
    }

    fun stop(currentCharacter: Int) {
        if (!state.isTracking) return
        update(currentCharacter)
        state = state.copy(isTracking = false)
    }

    fun update(currentCharacter: Int) {
        if (!enabled || !state.isTracking) return
        rollTodayIfNeeded()
        val now = clock.currentTimeMillis()
        val timeDiff = (now - lastTimestampMillis).toDouble() / 1000.0
        if (timeDiff <= 0.0) return

        val charDiff = currentCharacter - lastCharacterCount
        val finalCharDiff = if (charDiff < 0 && abs(charDiff) > state.session.charactersRead) {
            -state.session.charactersRead
        } else {
            charDiff
        }
        val modified = clock.currentTimeMillis()
        state = state.copy(
            session = state.session.updated(timeDiff, finalCharDiff, modified),
            today = state.today.updated(timeDiff, finalCharDiff, modified),
            allTime = state.allTime.updated(timeDiff, finalCharDiff, modified),
        )
        hasUpdated = true
        lastTimestampMillis = now
        lastCharacterCount = currentCharacter
    }

    fun resetBaseline(currentCharacter: Int) {
        lastCharacterCount = currentCharacter
        lastTimestampMillis = clock.currentTimeMillis()
    }

    fun statisticsForPersistenceOrNull(): List<ReadingStatistics>? =
        if (enabled && (hasUpdated || statistics.isNotEmpty())) statisticsForPersistence() else null

    fun statisticsForPersistence(): List<ReadingStatistics> {
        val today = state.today
        val next = statistics.toMutableList()
        val index = next.indexOfFirst { it.dateKey == today.dateKey }
        if (index >= 0) {
            next[index] = today
        } else {
            next += today
        }
        statistics = next.deduplicateReadingStatistics()
        return statistics
    }

    private fun rollTodayIfNeeded() {
        val currentDate = clock.currentDate()
        val currentDateKey = currentDate.toString()
        if (state.today.dateKey == currentDateKey) return
        statisticsForPersistence()
        state = state.copy(today = statisticForDate(currentDate))
    }

    private fun statisticForDate(date: LocalDate): ReadingStatistics =
        statistics.firstOrNull { it.dateKey == date.toString() } ?: defaultStatistic(date)

    private fun defaultStatistic(date: LocalDate): ReadingStatistics =
        ReadingStatistics(title = title, dateKey = date.toString())

    private fun allTimeStatistic(statistics: List<ReadingStatistics>): ReadingStatistics {
        val base = defaultStatistic(clock.currentDate())
        return statistics.fold(base) { total, statistic ->
            val readingTime = total.readingTime + statistic.readingTime
            val charactersRead = total.charactersRead + statistic.charactersRead
            total.copy(
                readingTime = readingTime,
                charactersRead = charactersRead,
                lastReadingSpeed = if (readingTime > 0.0) {
                    (charactersRead.toDouble() / readingTime * 3600.0).toInt()
                } else {
                    0
                },
            )
        }
    }
}

private fun ReadingStatistics.updated(
    timeDiff: Double,
    characterDiff: Int,
    lastStatisticModified: Long,
): ReadingStatistics {
    val nextReadingTime = readingTime + timeDiff
    val nextCharactersRead = (charactersRead + characterDiff).coerceAtLeast(0)
    val nextReadingSpeed = if (nextReadingTime > 0.0) {
        (nextCharactersRead.toDouble() / nextReadingTime * 3600.0).toInt()
    } else {
        0
    }
    return copy(
        readingTime = nextReadingTime,
        charactersRead = nextCharactersRead,
        lastReadingSpeed = nextReadingSpeed,
        maxReadingSpeed = maxOf(maxReadingSpeed, nextReadingSpeed),
        minReadingSpeed = if (minReadingSpeed != 0) minOf(minReadingSpeed, nextReadingSpeed) else nextReadingSpeed,
        altMinReadingSpeed = if (characterDiff != 0) {
            if (altMinReadingSpeed != 0) minOf(altMinReadingSpeed, nextReadingSpeed) else nextReadingSpeed
        } else {
            altMinReadingSpeed
        },
        lastStatisticModified = lastStatisticModified,
    )
}
