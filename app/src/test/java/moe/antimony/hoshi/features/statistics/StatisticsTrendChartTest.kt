package moe.antimony.hoshi.features.statistics

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsTrendChartTest {
    @Test
    fun tapsUseBucketWidthIncludingEmptyBucketsAndExcludeYAxis() {
        val buckets = (0L..29L).map { day ->
            LocalDate.of(2026, 4, 1).plusDays(day).let { StatisticsDateRange(it, it) }
        }
        assertEquals(0, trendBucketIndex(0f, 300f, buckets))
        assertEquals(1, trendBucketIndex(10f, 300f, buckets))
        assertEquals(29, trendBucketIndex(299f, 300f, buckets))
        assertEquals(null, trendBucketIndex(300f, 300f, buckets))
        assertEquals(null, trendBucketIndex(-1f, 300f, buckets))
        assertEquals(null, trendBucketIndex(10f, 0f, emptyList()))
    }

    @Test
    fun monthlyBarsAndTapsShareTheCalendarScaleIncludingLeapFebruary() {
        val buckets = (1..3).map { month ->
            statisticsTrendBucket(StatisticsRangeMode.Year, "2024-0$month")!!
        }
        val range = StatisticsDateRange(buckets.first().start, buckets.last().end)
        assertEquals(31f / 91f, trendDateFraction(LocalDate.of(2024, 2, 1), range), 0.00001f)
        assertEquals(0, trendBucketIndex(30.9f, 91f, buckets))
        assertEquals(1, trendBucketIndex(31f, 91f, buckets))
        assertEquals(1, trendBucketIndex(59.9f, 91f, buckets))
        assertEquals(2, trendBucketIndex(60f, 91f, buckets))
    }

    @Test
    fun chartBucketsResolveDailyAndMonthlyRanges() {
        val leapDay = LocalDate.parse("2024-02-29")
        assertEquals(StatisticsDateRange(leapDay, leapDay), statisticsTrendBucket(StatisticsRangeMode.Week, "2024-02-29"))
        assertEquals(StatisticsDateRange(LocalDate.parse("2024-02-01"), leapDay), statisticsTrendBucket(StatisticsRangeMode.All, "2024-02"))
        assertEquals(null, statisticsTrendBucket(StatisticsRangeMode.Year, "invalid"))
    }

    @Test
    fun monthTicksFollowLocaleWeeksRatherThanEvenlySpacedDayNumbers() {
        val april = StatisticsDateRange(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30))
        assertEquals(listOf(5, 12, 19, 26), trendAxisDates(StatisticsRangeMode.Month, april, Locale.US).map { it.dayOfMonth })
        assertEquals(listOf(6, 13, 20, 27), trendAxisDates(StatisticsRangeMode.Month, april, Locale.UK).map { it.dayOfMonth })
    }

    @Test
    fun weekTicksIncludeEveryDayAcrossTheYearBoundary() {
        val week = StatisticsDateRange(LocalDate.of(2025, 12, 28), LocalDate.of(2026, 1, 3))
        assertEquals(datesInRange(week), trendAxisDates(StatisticsRangeMode.Week, week, Locale.US))
    }

    @Test
    fun yearTicksUseTwoMonthsAndAllUsesThreeMonthsFromItsFirstBucket() {
        val year = StatisticsDateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
        assertEquals(listOf(1, 3, 5, 7, 9, 11), trendAxisDates(StatisticsRangeMode.Year, year).map { it.monthValue })
        val all = year.copy(start = LocalDate.of(2026, 2, 1), end = LocalDate.of(2026, 9, 30))
        assertEquals(listOf(2, 5, 8), trendAxisDates(StatisticsRangeMode.All, all).map { it.monthValue })
    }

    @Test
    fun longHistoryThinsWholeCalendarIntervalsToFitTheChart() {
        val range = StatisticsDateRange(LocalDate.of(2000, 2, 1), LocalDate.of(2026, 9, 30))
        val ticks = trendAxisDates(StatisticsRangeMode.All, range, maxLabels = 3)
        assertTrue(ticks.size <= 3)
        assertEquals(range.start, ticks.first())
        assertTrue(ticks.all { it.dayOfMonth == 1 && (it.monthValue - 2) % 3 == 0 })
    }
}
