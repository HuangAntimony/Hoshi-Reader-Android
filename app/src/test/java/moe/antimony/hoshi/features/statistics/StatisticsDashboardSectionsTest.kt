package moe.antimony.hoshi.features.statistics

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsDashboardSectionsTest {
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
    @Test
    fun historyDatesStayCompactInCurrentYearAndKeepOlderYears() {
        val today = LocalDate.parse("2026-09-18")
        assertEquals("6月30日", formatStatisticsHistoryDate(
            LocalDate.parse("2026-06-30"), today, "M月d日", Locale.SIMPLIFIED_CHINESE,
        ))
        assertEquals("2025年12月31日", formatStatisticsHistoryDate(
            LocalDate.parse("2025-12-31"), today, "M月d日", Locale.SIMPLIFIED_CHINESE,
        ))
        assertEquals("Jun 30", formatStatisticsHistoryDate(
            LocalDate.parse("2026-06-30"), today, "MMM d", Locale.US,
        ))
        assertEquals("Dec 31, 2025", formatStatisticsHistoryDate(
            LocalDate.parse("2025-12-31"), today, "MMM d", Locale.US,
        ))
    }

    @Test
    fun dailyGaugeKeepsSmallProgressAndDoesNotRoundUpToGoalMet() {
        val today = TodayStatisticsUi(
            date = LocalDate.parse("2026-09-18"), targetPercent = 0,
            totalCharacters = 5, readingSeconds = 1.8, averageSpeedPerHour = 10_000,
            dailyStreakDays = 0,
        )
        val characters = StatisticsTargetSettings(dailyCharacterTarget = 5_000)
        val duration = StatisticsTargetSettings(dailyTargetType = DailyTargetType.Duration, dailyDurationTargetMinutes = 30)
        assertEquals(0.001f, statisticsDailyGoalProgress(today, characters), 0.00001f)
        assertEquals(0.001f, statisticsDailyGoalProgress(today, duration), 0.00001f)
        val almost = today.copy(targetPercent = 100, totalCharacters = 4_980, readingSeconds = 1_792.8)
        assertEquals(0.996f, statisticsDailyGoalProgress(almost, characters), 0.00001f)
        assertEquals(0.996f, statisticsDailyGoalProgress(almost, duration), 0.00001f)
        val exceeded = today.copy(targetPercent = 150, totalCharacters = 7_500, readingSeconds = 2_700.0)
        assertEquals(1f, statisticsDailyGoalProgress(exceeded, characters), 0f)
        assertEquals(1f, statisticsDailyGoalProgress(exceeded, duration), 0f)
    }

}
