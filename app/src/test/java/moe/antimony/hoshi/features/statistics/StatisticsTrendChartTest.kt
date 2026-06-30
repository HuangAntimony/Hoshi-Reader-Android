package moe.antimony.hoshi.features.statistics

import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsTrendChartTest {
    @Test
    fun axisMarginsGrowToFitMeasuredAxisLabels() {
        val margins = trendChartAxisMargins(
            leftLabelWidthsPx = listOf(72f, 44f, 8f),
            rightLabelWidthsPx = listOf(96f, 48f, 8f),
            minLeftPx = 32f,
            minRightPx = 38f,
            labelPaddingPx = 6f,
        )

        assertEquals(78f, margins.leftPx, 0.0f)
        assertEquals(102f, margins.rightPx, 0.0f)
    }

    @Test
    fun axisMarginsKeepMinimumForShortAxisLabels() {
        val margins = trendChartAxisMargins(
            leftLabelWidthsPx = listOf(12f, 10f, 8f),
            rightLabelWidthsPx = listOf(14f, 12f, 8f),
            minLeftPx = 32f,
            minRightPx = 38f,
            labelPaddingPx = 6f,
        )

        assertEquals(32f, margins.leftPx, 0.0f)
        assertEquals(38f, margins.rightPx, 0.0f)
    }
}
