package moe.antimony.hoshi.features.statistics

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsCalculationsTest {
    @Test
    fun recentYearWindowEndsTodayAndStartsOneYearAgoPlusOneDay() {
        val window = recentYearStatisticsWindow(LocalDate.parse("2026-06-30"))

        assertEquals(LocalDate.parse("2025-07-01"), window.start)
        assertEquals(LocalDate.parse("2026-06-30"), window.end)
        assertEquals(365, window.dayCount)
    }

    @Test
    fun fixedYearWindowClipsCurrentYearToToday() {
        val window = fixedYearStatisticsWindow(2026, LocalDate.parse("2026-06-30"))

        assertEquals(LocalDate.parse("2026-01-01"), window.start)
        assertEquals(LocalDate.parse("2026-06-30"), window.end)
    }

    @Test
    fun weekRangeStartsMondayAndClipsToWindow() {
        val window = StatisticsDateRange(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"))
        val range = selectedStatisticsRange(
            mode = StatisticsRangeMode.Week,
            anchor = LocalDate.parse("2026-06-30"),
            window = window,
        )

        assertEquals(LocalDate.parse("2026-06-29"), range.start)
        assertEquals(LocalDate.parse("2026-06-30"), range.end)
    }

    @Test
    fun aggregateRangeRecomputesAverageSpeedFromCharactersAndSeconds() {
        val summary = aggregateRange(
            listOf(
                day("2026-06-29", characters = 1_000, seconds = 600.0),
                day("2026-06-30", characters = 2_000, seconds = 900.0),
            ),
            StatisticsTargetSettings(),
        )

        assertEquals(3_000, summary.totalCharacters)
        assertEquals(1_500.0, summary.readingSeconds, 0.0)
        assertEquals(7_200, summary.averageSpeedPerHour)
    }

    @Test
    fun targetRatiosCanExceedOneForCharactersAndDuration() {
        val aggregate = day("2026-06-30", characters = 3_000, seconds = 2_700.0)

        assertEquals(
            1.5,
            aggregate.targetRatio(
                StatisticsTargetSettings(
                    dailyTargetType = DailyTargetType.Characters,
                    dailyCharacterTarget = 2_000,
                ),
            ),
            0.0,
        )
        assertEquals(
            1.5,
            aggregate.targetRatio(StatisticsTargetSettings(dailyTargetType = DailyTargetType.Duration)),
            0.0,
        )
    }

    @Test
    fun dailyGoalStreakExcludesUnmetToday() {
        val today = LocalDate.parse("2026-06-30")
        val days = listOf(
            day("2026-06-27", characters = 2_000),
            day("2026-06-28", characters = 2_000),
            day("2026-06-29", characters = 2_000),
            day("2026-06-30", characters = 1_000),
        ).associateBy { it.date }

        assertEquals(
            3,
            dailyGoalStreak(days, today, StatisticsTargetSettings(dailyCharacterTarget = 2_000)),
        )
    }

    @Test
    fun currentWeekAverageUsesElapsedDaysIncludingToday() {
        val today = LocalDate.parse("2026-06-30")
        val week = currentWeekSummary(
            days = listOf(
                day("2026-06-29", characters = 2_000, seconds = 600.0),
                day("2026-06-30", characters = 4_000, seconds = 1_200.0),
            ),
            today = today,
            settings = StatisticsTargetSettings(),
        )

        assertEquals(2, week.elapsedDays)
        assertEquals(3_000, week.averageCharactersPerElapsedDay)
        assertEquals(900.0, week.averageReadingSecondsPerElapsedDay, 0.0)
    }

    @Test
    fun readingHeatLevelsSpreadActiveDaysAcrossTheVisibleWindow() {
        val levels = readingHeatLevels(
            listOf(
                day("2026-06-01", characters = 0),
                day("2026-06-02", characters = 6_000),
                day("2026-06-03", characters = 7_000),
                day("2026-06-04", characters = 8_000),
                day("2026-06-05", characters = 9_000),
                day("2026-06-06", characters = 10_000),
                day("2026-06-07", characters = 11_000),
                day("2026-06-08", characters = 12_000),
            ),
        )

        assertEquals(0, levels.getValue(LocalDate.parse("2026-06-01")))
        assertEquals(1, levels.getValue(LocalDate.parse("2026-06-02")))
        assertEquals(2, levels.getValue(LocalDate.parse("2026-06-03")))
        assertEquals(3, levels.getValue(LocalDate.parse("2026-06-04")))
        assertEquals(4, levels.getValue(LocalDate.parse("2026-06-05")))
        assertEquals(5, levels.getValue(LocalDate.parse("2026-06-06")))
        assertEquals(6, levels.getValue(LocalDate.parse("2026-06-07")))
        assertEquals(7, levels.getValue(LocalDate.parse("2026-06-08")))
    }

    @Test
    fun readingHeatLevelsKeepEmptyDaysAtZero() {
        val levels = readingHeatLevels(
            listOf(
                day("2026-06-01", characters = 0),
                day("2026-06-02", characters = 0),
            ),
        )

        assertEquals(0, levels.getValue(LocalDate.parse("2026-06-01")))
        assertEquals(0, levels.getValue(LocalDate.parse("2026-06-02")))
    }

    @Test
    fun readingHeatLevelsKeepRepeatedCharacterCountsTogether() {
        val levels = readingHeatLevels(
            listOf(
                day("2026-06-01", characters = 1_000),
                day("2026-06-02", characters = 1_000),
                day("2026-06-03", characters = 2_000),
            ),
        )

        assertEquals(levels.getValue(LocalDate.parse("2026-06-01")), levels.getValue(LocalDate.parse("2026-06-02")))
        assertEquals(1, levels.getValue(LocalDate.parse("2026-06-01")))
        assertEquals(7, levels.getValue(LocalDate.parse("2026-06-03")))
    }

    @Test
    fun readingHeatLevelsUseStrongestLevelForSingleActiveCharacterCount() {
        val levels = readingHeatLevels(
            listOf(
                day("2026-06-01", characters = 0),
                day("2026-06-02", characters = 4_000),
                day("2026-06-03", characters = 4_000),
            ),
        )

        assertEquals(0, levels.getValue(LocalDate.parse("2026-06-01")))
        assertEquals(7, levels.getValue(LocalDate.parse("2026-06-02")))
        assertEquals(7, levels.getValue(LocalDate.parse("2026-06-03")))
    }

    @Test
    fun readingHeatLevelsStayWithinRangeForSparseDistinctCounts() {
        val levels = readingHeatLevels(
            listOf(
                day("2026-06-01", characters = 100),
                day("2026-06-02", characters = 10_000),
                day("2026-06-03", characters = 50_000),
            ),
        )

        assertEquals(listOf(1, 4, 7), levels.values.toList())
    }

    @Test
    fun trendPointsUseMonthsForYearAndDaysForShorterRanges() {
        val days = listOf(
            day("2026-01-15", characters = 1_000, seconds = 600.0),
            day("2026-01-16", characters = 2_000, seconds = 900.0),
            day("2026-02-01", characters = 3_000, seconds = 1_200.0),
        )
        val range = StatisticsDateRange(
            start = LocalDate.parse("2026-01-15"),
            end = LocalDate.parse("2026-02-01"),
        )

        val year = trendPoints(StatisticsRangeMode.Year, range, days)
        val month = trendPoints(StatisticsRangeMode.Month, range, days.take(2))
        val day = trendPoints(StatisticsRangeMode.Day, range, days.take(1))

        assertEquals(listOf("2026-01", "2026-02"), year.map { it.key })
        assertEquals(3_000, year.first().characters)
        assertEquals(
            listOf(
                "2026-01-15",
                "2026-01-16",
                "2026-01-17",
                "2026-01-18",
                "2026-01-19",
                "2026-01-20",
                "2026-01-21",
                "2026-01-22",
                "2026-01-23",
                "2026-01-24",
                "2026-01-25",
                "2026-01-26",
                "2026-01-27",
                "2026-01-28",
                "2026-01-29",
                "2026-01-30",
                "2026-01-31",
                "2026-02-01",
            ),
            month.map { it.key },
        )
        assertEquals(0, month[2].characters)
        assertEquals(0.0, month[2].readingSeconds, 0.0)
        assertTrue(day.isEmpty())
    }

    @Test
    fun distributionRowsSortAndPercentByActiveTargetType() {
        val days = listOf(
            day(
                "2026-06-30",
                contributions = listOf(
                    contribution(bookId = "fast", title = "Fast", characters = 4_000, seconds = 600.0),
                    contribution(bookId = "slow", title = "Slow", characters = 1_000, seconds = 1_800.0),
                ),
            ),
        )

        val byCharacters = distributionRows(
            days,
            StatisticsTargetSettings(dailyTargetType = DailyTargetType.Characters),
        )
        val byDuration = distributionRows(
            days,
            StatisticsTargetSettings(dailyTargetType = DailyTargetType.Duration),
        )

        assertEquals(listOf("Fast", "Slow"), byCharacters.map { it.title })
        assertEquals(80, byCharacters.first().percent)
        assertEquals(listOf("Slow", "Fast"), byDuration.map { it.title })
        assertEquals(75, byDuration.first().percent)
    }

    private fun day(
        date: String,
        characters: Int = 0,
        seconds: Double = 0.0,
        contributions: List<StatisticsBookContribution> = listOf(
            contribution(
                bookId = "book-$date",
                title = "Book $date",
                characters = characters,
                seconds = seconds,
            ),
        ),
    ): StatisticsDayAggregate =
        StatisticsDayAggregate(
            date = LocalDate.parse(date),
            totalCharacters = characters.takeIf { contributions.size == 1 } ?: contributions.sumOf { it.characters },
            readingSeconds = seconds.takeIf { contributions.size == 1 } ?: contributions.sumOf { it.readingSeconds },
            activeBookCount = contributions.count { it.characters > 0 || it.readingSeconds > 0.0 },
            bookContributions = contributions,
        )

    private fun contribution(
        bookId: String,
        title: String,
        characters: Int,
        seconds: Double,
    ): StatisticsBookContribution =
        StatisticsBookContribution(
            bookId = bookId,
            title = title,
            coverPath = null,
            characters = characters,
            readingSeconds = seconds,
        )
}
