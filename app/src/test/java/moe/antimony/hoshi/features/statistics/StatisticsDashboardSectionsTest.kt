package moe.antimony.hoshi.features.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsDashboardSectionsTest {
    @Test
    fun weekStatisticsMetricsPlaceAverageFirstOnSecondRow() {
        val metrics = weekStatisticsMetricsInDisplayOrder(
            durationMetric = StatisticMetric(label = "Duration", value = "1h"),
            charactersMetric = StatisticMetric(label = "Characters", value = "1000"),
            speedMetric = StatisticMetric(label = "Speed", value = "2000/h"),
            targetDaysMetric = StatisticMetric(label = "Days Met", value = "1 day"),
            streakMetric = StatisticMetric(label = "Streak", value = "1 week"),
            averageMetric = StatisticMetric(label = "Avg Characters", value = "500"),
        )

        assertEquals(
            listOf("Duration", "Characters", "Speed", "Avg Characters", "Days Met", "Streak"),
            metrics.map { it.label },
        )
    }

    @Test
    fun statisticsIntegerFormatterDoesNotUseGroupingSeparators() {
        assertEquals("1234567", formatInteger(1_234_567))
    }

    @Test
    fun metricCardLongValuesUseCompactTextInDenseGrids() {
        val compact = metricCardTextSpec(
            metric = StatisticMetric(label = "Speed", value = "77931/h"),
            columns = 3,
        )
        val regular = metricCardTextSpec(
            metric = StatisticMetric(label = "Speed", value = "2m"),
            columns = 3,
        )

        assertTrue(compact.valueFontSizeSp < regular.valueFontSizeSp)
        assertTrue(compact.valueLineHeightSp < regular.valueLineHeightSp)
    }

    @Test
    fun metricCardLongLabelsFitAsTwoCompactLines() {
        val spec = metricCardTextSpec(
            metric = StatisticMetric(label = "Avg Characters", value = "1137"),
            columns = 3,
        )

        assertEquals(2, spec.labelMaxLines)
        assertTrue(spec.labelLineHeightSp <= 14)
    }
}
