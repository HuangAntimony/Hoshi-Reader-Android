package moe.antimony.hoshi.features.statistics

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsTrendChartTest {
    @Test
    fun tapsUseBucketWidthIncludingEmptyBucketsAndExcludeYAxis() {
        assertEquals(0, trendBucketIndex(0f, 300f, 12))
        assertEquals(1, trendBucketIndex(25f, 300f, 12))
        assertEquals(11, trendBucketIndex(299f, 300f, 12))
        assertEquals(null, trendBucketIndex(300f, 300f, 12))
        assertEquals(null, trendBucketIndex(-1f, 300f, 12))
        assertEquals(null, trendBucketIndex(10f, 0f, 0))
    }

    @Test
    fun chartBucketsResolveDailyAndMonthlyRanges() {
        val leapDay = LocalDate.parse("2024-02-29")
        assertEquals(StatisticsDateRange(leapDay, leapDay), statisticsTrendBucket(StatisticsRangeMode.Week, "2024-02-29"))
        assertEquals(StatisticsDateRange(LocalDate.parse("2024-02-01"), leapDay), statisticsTrendBucket(StatisticsRangeMode.All, "2024-02"))
        assertEquals(null, statisticsTrendBucket(StatisticsRangeMode.Year, "invalid"))
    }

    @Test
    fun longHistoryHasBoundedAxisLabelsAndWeekShowsEveryDay() {
        assertEquals((0..6).toList(), trendLabelIndexes(7, StatisticsRangeMode.Week))
        assertTrue(trendLabelIndexes(306, StatisticsRangeMode.All).size <= 3)
        assertTrue(trendLabelIndexes(31, StatisticsRangeMode.Month).size <= 6)
        assertEquals(emptyList<Int>(), trendLabelIndexes(0, StatisticsRangeMode.All))
    }
}
