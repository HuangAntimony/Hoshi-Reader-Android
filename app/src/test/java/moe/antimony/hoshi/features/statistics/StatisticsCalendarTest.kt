package moe.antimony.hoshi.features.statistics

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsCalendarTest {
    @Test
    fun monthLabelsAppearOnlyOnWeeksContainingFirstDayOfMonth() {
        val window = StatisticsDateRange(
            start = LocalDate.parse("2026-01-01"),
            end = LocalDate.parse("2026-03-31"),
        )
        val weekStarts = generateSequence(mondayStartOfWeek(window.start)) { it.plusWeeks(1) }
            .takeWhile { weekStart -> !weekStart.isAfter(window.end) }
            .toList()

        assertEquals("1", monthLabelForWeek(LocalDate.parse("2025-12-29"), window))
        assertEquals("", monthLabelForWeek(LocalDate.parse("2026-01-05"), window))
        assertEquals(listOf("1", "2", "3"), weekStarts.map { monthLabelForWeek(it, window) }.filter { it.isNotEmpty() })
    }

    @Test
    fun heatmapHitTestingIncludesSpacingAroundVisibleCells() {
        val window = StatisticsDateRange(
            start = LocalDate.parse("2026-06-01"),
            end = LocalDate.parse("2026-06-14"),
        )
        val weekStarts = listOf(
            LocalDate.parse("2026-06-01"),
            LocalDate.parse("2026-06-08"),
        )

        assertEquals(
            LocalDate.parse("2026-06-01"),
            heatmapDateForPosition(
                offsetX = 19f,
                offsetY = 19f,
                weekStarts = weekStarts,
                window = window,
                cellSizePx = 18f,
                spacingPx = 4f,
            ),
        )
        assertEquals(
            LocalDate.parse("2026-06-09"),
            heatmapDateForPosition(
                offsetX = 21f,
                offsetY = 21f,
                weekStarts = weekStarts,
                window = window,
                cellSizePx = 18f,
                spacingPx = 4f,
            ),
        )
    }

    @Test
    fun heatmapSelectionDecorationUsesRangeOutlineWithoutPerCellHaloOrStroke() {
        val range = heatmapSelectionDecoration(isAnchor = false, inSelectedRange = true)
        val anchor = heatmapSelectionDecoration(isAnchor = true, inSelectedRange = true)

        assertEquals(false, range.hasHalo)
        assertTrue(range.rangeOutlineStrokeWidthDp > 0f)
        assertEquals(false, anchor.hasHalo)
        assertTrue(anchor.anchorOuterStrokeWidthDp <= range.rangeOutlineStrokeWidthDp)
    }

    @Test
    fun heatmapRangeOutlineDrawsForShortRangesEvenWithOneVisibleCell() {
        assertEquals(true, shouldDrawHeatmapRangeOutline(StatisticsRangeMode.Week, selectedCellCount = 1))
        assertEquals(true, shouldDrawHeatmapRangeOutline(StatisticsRangeMode.Month, selectedCellCount = 1))
        assertEquals(false, shouldDrawHeatmapRangeOutline(StatisticsRangeMode.Day, selectedCellCount = 1))
        assertEquals(false, shouldDrawHeatmapRangeOutline(StatisticsRangeMode.Year, selectedCellCount = 10))
        assertEquals(false, shouldDrawHeatmapRangeOutline(StatisticsRangeMode.Week, selectedCellCount = 0))
    }

    @Test
    fun heatmapSelectionInsetCoversSelectionDecorationOutwardEdges() {
        val inset = heatmapSelectionInsetDp()
        val range = heatmapSelectionDecoration(isAnchor = false, inSelectedRange = true)
        val anchor = heatmapSelectionDecoration(isAnchor = true, inSelectedRange = true)

        assertTrue(inset >= range.outwardPaddingDp())
        assertTrue(inset >= anchor.outwardPaddingDp())
    }

    @Test
    fun heatmapCanvasHitTestingAccountsForSelectionInset() {
        val window = StatisticsDateRange(
            start = LocalDate.parse("2026-06-01"),
            end = LocalDate.parse("2026-06-14"),
        )
        val weekStarts = listOf(
            LocalDate.parse("2026-06-01"),
            LocalDate.parse("2026-06-08"),
        )

        assertEquals(
            LocalDate.parse("2026-06-09"),
            heatmapDateForCanvasPosition(
                offsetX = 3.5f + 21f,
                offsetY = 3.5f + 21f,
                selectionInsetPx = 3.5f,
                weekStarts = weekStarts,
                window = window,
                cellSizePx = 18f,
                spacingPx = 4f,
            ),
        )
        assertEquals(
            null,
            heatmapDateForCanvasPosition(
                offsetX = 2f,
                offsetY = 2f,
                selectionInsetPx = 3.5f,
                weekStarts = weekStarts,
                window = window,
                cellSizePx = 18f,
                spacingPx = 4f,
            ),
        )
    }

    @Test
    fun heatmapAutoScrollRunsOncePerWindow() {
        val firstWindow = StatisticsDateRange(
            start = LocalDate.parse("2026-01-01"),
            end = LocalDate.parse("2026-12-31"),
        )
        val secondWindow = StatisticsDateRange(
            start = LocalDate.parse("2025-01-01"),
            end = LocalDate.parse("2025-12-31"),
        )
        val firstKey = heatmapAutoScrollKey(firstWindow, weekCount = 53)
        val secondKey = heatmapAutoScrollKey(secondWindow, weekCount = 53)

        assertEquals(true, shouldAutoScrollHeatmap(autoScrolledWindowKey = null, currentWindowKey = firstKey))
        assertEquals(false, shouldAutoScrollHeatmap(autoScrolledWindowKey = firstKey, currentWindowKey = firstKey))
        assertEquals(true, shouldAutoScrollHeatmap(autoScrolledWindowKey = firstKey, currentWindowKey = secondKey))
    }
}
