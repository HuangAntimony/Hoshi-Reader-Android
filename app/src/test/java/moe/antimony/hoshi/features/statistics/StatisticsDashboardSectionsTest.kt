package moe.antimony.hoshi.features.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsDashboardSectionsTest {
    @Test
    fun metricCardLongValuesUseCompactTextInDenseGrids() {
        val compact = metricCardTextSpec(
            metric = StatisticMetric(label = "Speed", value = "77,931/h"),
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
            metric = StatisticMetric(label = "Avg Characters", value = "1,137"),
            columns = 3,
        )

        assertEquals(2, spec.labelMaxLines)
        assertTrue(spec.labelLineHeightSp <= 14)
    }
}
