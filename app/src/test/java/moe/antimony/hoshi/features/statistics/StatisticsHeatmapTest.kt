package moe.antimony.hoshi.features.statistics

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsHeatmapTest {
    @Test
    fun monthLabelsMarkMonthBoundariesAndCrossYears() {
        val window = StatisticsDateRange(LocalDate.parse("2025-12-20"), LocalDate.parse("2026-06-30"))
        assertEquals("2025/12", monthLabelForWeek(LocalDate.parse("2025-12-15"), window))
        assertEquals("2026/1", monthLabelForWeek(LocalDate.parse("2025-12-29"), window))
        assertEquals("", monthLabelForWeek(LocalDate.parse("2026-01-05"), window))
        val sameYear = StatisticsDateRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-30"))
        assertEquals("1", monthLabelForWeek(statisticsStartOfWeek(sameYear.start, Locale.UK), sameYear))
    }
}
