package moe.antimony.hoshi.features.statistics

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsCalculationsTest {
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
    fun weekRangeUsesLocaleAndKeepsFullNaturalPeriod() {
        val range = selectedStatisticsRange(
            mode = StatisticsRangeMode.Week,
            anchor = LocalDate.parse("2026-06-30"),
            today = LocalDate.parse("2026-06-30"),
            locale = Locale.UK,
        )

        assertEquals(LocalDate.parse("2026-06-29"), range.start)
        assertEquals(LocalDate.parse("2026-07-05"), range.end)
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
    fun currentWeekOverviewAverageUsesElapsedDaysIncludingToday() {
        val today = LocalDate.parse("2026-06-30")
        val week = overviewRangeSummary(
            days = listOf(
                day("2026-06-29", characters = 2_000, seconds = 600.0),
                day("2026-06-30", characters = 4_000, seconds = 1_200.0),
            ),
            settings = StatisticsTargetSettings(),
            mode = StatisticsRangeMode.Week,
            anchor = today,
            today = today,
            locale = Locale.UK,
        )

        assertEquals(6_000, week.totalCharacters)
        assertEquals(900.0, week.averageReadingSecondsPerBucket, 0.0)
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
    }

    @Test
    fun distributionRowsSortByTimeAndScaleToLongestBook() {
        val days = listOf(
            day(
                "2026-06-30",
                contributions = listOf(
                    contribution(bookId = "fast", title = "Fast", characters = 4_000, seconds = 600.0),
                    contribution(bookId = "slow", title = "Slow", characters = 1_000, seconds = 1_800.0),
                ),
            ),
        )

        val rows = distributionRows(days)
        assertEquals(listOf("Slow", "Fast"), rows.map { it.title })
        assertEquals(1f, rows.first().timeFraction, 0.0f)
        assertEquals(1f / 3f, rows.last().timeFraction, 0.0001f)
    }

    @Test
    fun distributionRowsCarryBookIdsForStableUiKeys() {
        val rows = distributionRows(
            listOf(
                day(
                    "2026-06-30",
                    contributions = listOf(
                        contribution(bookId = "alpha-id", title = "Same Title", characters = 2_000, seconds = 600.0),
                        contribution(bookId = "beta-id", title = "Same Title", characters = 1_000, seconds = 300.0),
                    ),
                ),
            ),
        )

        assertEquals(listOf("alpha-id", "beta-id"), rows.map { it.bookId })
    }

    @Test
    fun distributionGroupsByFolderAndPreservesArchivedRows() {
        val rows = distributionRows(
            listOf(day("2026-06-30", contributions = listOf(
                contribution("same-id", "First", 100, 60.0).copy(folder = "first", isArchived = true),
                contribution("same-id", "Second", 200, 120.0).copy(folder = "second"),
            ))),
        )
        assertEquals(listOf("second", "first"), rows.map { it.folder })
        assertEquals(listOf(false, true), rows.map { it.isArchived })
    }

    @Test
    fun naturalYearAndLeapMonthIgnoreHeatmapWindow() {
        val today = LocalDate.parse("2026-06-30")
        assertEquals(StatisticsDateRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")),
            selectedStatisticsRange(StatisticsRangeMode.Year, today, today))
        val leap = selectedStatisticsRange(StatisticsRangeMode.Month, LocalDate.parse("2024-02-20"), today)
        assertEquals(29, leap.dayCount)
        assertEquals(LocalDate.parse("2024-02-29"), leap.end)
    }

    @Test
    fun sundayFirstWeekOverviewCrossesYearAndCountsDailyGoals() {
        val today = LocalDate.parse("2025-01-01")
        val range = selectedStatisticsRange(StatisticsRangeMode.Week, today, today, locale = Locale.US)
        val week = overviewRangeSummary(
            listOf(day("2024-12-29", characters = 5_000, seconds = 600.0)),
            StatisticsTargetSettings(), StatisticsRangeMode.Week, today, today, locale = Locale.US,
        )
        assertEquals(LocalDate.parse("2024-12-29"), range.start)
        assertEquals(LocalDate.parse("2025-01-04"), range.end)
        assertEquals(150.0, week.averageReadingSecondsPerBucket, 0.0)
        assertEquals(1, week.targetDays)
    }

    @Test
    fun weeklyAndMonthlyAveragesIncludeElapsedEmptyDaysAndPreviousFullPeriod() {
        val today = LocalDate.parse("2026-06-03")
        val days = listOf(day("2026-06-01", seconds = 600.0), day("2026-05-25", seconds = 700.0))
        val week = overviewRangeSummary(days, StatisticsTargetSettings(), StatisticsRangeMode.Week,
            today, today, locale = Locale.UK)
        assertEquals(200.0, week.averageReadingSecondsPerBucket, 0.0)
        assertEquals(100.0, week.averageReadingTimeChangePercent!!, 0.0)
        val month = overviewRangeSummary(days, StatisticsTargetSettings(), StatisticsRangeMode.Month,
            today, today)
        assertEquals(200.0, month.averageReadingSecondsPerBucket, 0.0)
        val historical = overviewRangeSummary(days, StatisticsTargetSettings(), StatisticsRangeMode.Month,
            LocalDate.parse("2026-05-25"), today)
        assertEquals(700.0 / 31.0, historical.averageReadingSecondsPerBucket, 0.0)
    }

    @Test
    fun yearlyAverageIncludesEmptyElapsedMonthsAndAllStartsWithEarliestActivity() {
        val today = LocalDate.parse("2026-03-20")
        val days = listOf(day("2024-12-31", seconds = 120.0), day("2026-01-01", seconds = 180.0),
            day("2000-01-01"), day("2027-01-01", seconds = 900.0))
        val year = overviewRangeSummary(days, StatisticsTargetSettings(), StatisticsRangeMode.Year, today, today)
        assertEquals(60.0, year.averageReadingSecondsPerBucket, 0.0)
        assertEquals(null, year.averageReadingTimeChangePercent)
        val allRange = selectedStatisticsRange(StatisticsRangeMode.All, today, today,
            firstActivity = LocalDate.parse("2024-12-31"))
        val all = overviewRangeSummary(days, StatisticsTargetSettings(), StatisticsRangeMode.All, today, today)
        assertEquals(300.0 / 16.0, all.averageReadingSecondsPerBucket, 0.0)
        assertEquals(null, all.averageReadingTimeChangePercent)
        val points = trendPoints(StatisticsRangeMode.All, allRange, days)
        assertEquals(16, points.size)
        assertEquals("2024-12", points.first().key)
        assertEquals("2026-03", points.last().key)
        assertEquals(0, points[1].characters)
        assertEquals(0.0, points[1].readingSeconds, 0.0)
    }

    @Test
    fun emptyYearKeepsTwelveZeroBuckets() {
        val today = LocalDate.parse("2026-03-20")
        val year = selectedStatisticsRange(StatisticsRangeMode.Year, today, today)
        val points = trendPoints(StatisticsRangeMode.Year, year, emptyList())
        assertEquals(12, points.size)
        assertTrue(points.all { it.characters == 0 && it.readingSeconds == 0.0 })
    }

    @Test
    fun historyUsesSparseAllTimeActivityAndEarliestTiesForBothMetrics() {
        val today = LocalDate.parse("2026-06-30")
        val days = listOf(day("2026-06-29", 5_000, 1_800.0), day("2020-01-02", 6_000, 2_400.0),
            day("2020-01-01", 6_000, 2_400.0), day("2026-06-28", 5_000, 1_800.0),
            day("2026-06-30", 10, 0.0), day("2010-01-01"), day("2027-01-01", 10_000, 9_000.0))
        for (metric in DailyTargetType.entries) {
            val history = statisticsHistorySummary(days, today, StatisticsTargetSettings(dailyTargetType = metric))
            assertEquals(2, history.currentStreak.count)
            assertEquals(LocalDate.parse("2026-06-28"), history.currentStreak.range?.start)
            assertEquals(2, history.longestStreak.count)
            assertEquals(LocalDate.parse("2020-01-01"), history.longestStreak.range?.start)
            assertEquals(4, history.metDays)
            assertEquals(5, history.readingDays)
            assertEquals(LocalDate.parse("2020-01-01"), history.bestDay?.date)
        }
    }

    @Test
    fun historyChangesBestDayWithTargetMetricAndExpiresStreakAfterGap() {
        val days = listOf(day("2026-06-27", 9_000, 60.0), day("2026-06-28", 500, 3_600.0))
        val today = LocalDate.parse("2026-06-30")
        val characters = statisticsHistorySummary(days, today, StatisticsTargetSettings())
        val duration = statisticsHistorySummary(days, today, StatisticsTargetSettings(dailyTargetType = DailyTargetType.Duration))
        assertEquals(LocalDate.parse("2026-06-27"), characters.bestDay?.date)
        assertEquals(LocalDate.parse("2026-06-28"), duration.bestDay?.date)
        assertEquals(0, duration.currentStreak.count)
        assertEquals(1, duration.metDays)
        assertEquals(null, statisticsHistorySummary(emptyList(), today, StatisticsTargetSettings()).bestDay)
    }

    @Test
    fun yearlyComparisonUsesPreviousTwelveMonthAverage() {
        val today = LocalDate.parse("2026-03-20")
        val summary = overviewRangeSummary(
            listOf(day("2025-04-01", seconds = 1_200.0), day("2026-02-01", seconds = 150.0)),
            StatisticsTargetSettings(), StatisticsRangeMode.Year, today, today,
        )
        assertEquals(50.0, summary.averageReadingSecondsPerBucket, 0.0)
        assertEquals(-50.0, summary.averageReadingTimeChangePercent!!, 0.0)
    }

    @Test
    fun navigatingWeekUsesStartSoCurrentPartialWeekRemainsReachable() {
        assertEquals(LocalDate.parse("2026-06-28"), shiftedStatisticsAnchor(
            StatisticsRangeMode.Week, LocalDate.parse("2026-06-27"), 1, Locale.US,
        ))
        assertEquals(LocalDate.parse("2026-06-29"), shiftedStatisticsAnchor(
            StatisticsRangeMode.Week, LocalDate.parse("2026-06-28"), 1, Locale.UK,
        ))
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
