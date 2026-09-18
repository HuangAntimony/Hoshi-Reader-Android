package moe.antimony.hoshi.features.statistics

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import moe.antimony.hoshi.ui.UiText

internal enum class StatisticsRangeMode {
    Week,
    Month,
    Year,
    All,
}

internal enum class DailyTargetType {
    Characters,
    Duration,
}

internal data class StatisticsDateRange(
    val start: LocalDate,
    val end: LocalDate,
) {
    init {
        require(!end.isBefore(start)) { "Statistics range end must be on or after start." }
    }

    val dayCount: Int
        get() = ChronoUnit.DAYS.between(start, end).toInt() + 1

    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    fun coerce(date: LocalDate): LocalDate = when {
        date.isBefore(start) -> start
        date.isAfter(end) -> end
        else -> date
    }
}

internal data class StatisticsTargetSettings(
    val dailyTargetType: DailyTargetType = DailyTargetType.Characters,
    val dailyCharacterTarget: Int = StatisticsTargetDefaults.DailyCharacterTarget,
    val dailyDurationTargetMinutes: Int = StatisticsTargetDefaults.DailyDurationTargetMinutes,
)

internal object StatisticsTargetDefaults {
    const val DailyCharacterTarget = 5_000
    const val MinDailyCharacterTarget = 500
    const val MaxDailyCharacterTarget = 200_000
    const val DailyCharacterTargetStep = 500
    const val DailyDurationTargetMinutes = 30
    const val MinDailyDurationTargetMinutes = 5
    const val MaxDailyDurationTargetMinutes = 720
    const val DailyDurationTargetStepMinutes = 5
}

internal data class StatisticsBookContribution(
    val bookId: String,
    val folder: String = bookId,
    val isArchived: Boolean = false,
    val title: String,
    val coverPath: String?,
    val characters: Int,
    val readingSeconds: Double,
)

internal data class StatisticsDayAggregate(
    val date: LocalDate,
    val totalCharacters: Int,
    val readingSeconds: Double,
    val activeBookCount: Int,
    val bookContributions: List<StatisticsBookContribution>,
)

internal data class StatisticsRangeSummary(
    val totalCharacters: Int,
    val readingSeconds: Double,
    val averageSpeedPerHour: Int,
    val targetDays: Int,
    val targetProgressPercent: Int,
    val averageReadingSecondsPerBucket: Double = 0.0,
    val averageReadingTimeChangePercent: Double? = null,
)

internal data class TodayStatisticsUi(
    val date: LocalDate,
    val targetPercent: Int,
    val totalCharacters: Int,
    val readingSeconds: Double,
    val averageSpeedPerHour: Int,
    val dailyStreakDays: Int,
)

internal data class StatisticsTrendPoint(
    val key: String,
    val characters: Int,
    val readingSeconds: Double,
)

internal data class BookDistributionRow(
    val bookId: String,
    val folder: String = bookId,
    val isArchived: Boolean = false,
    val title: String,
    val coverPath: String?,
    val characters: Int,
    val readingSeconds: Double,
    val timeFraction: Float,
)

internal data class StatisticsHeatmapDayUi(
    val date: LocalDate,
    val heatLevel: Int,
)

internal data class StatisticsHeatmapUi(
    val windowRange: StatisticsDateRange,
    val days: List<StatisticsHeatmapDayUi> = emptyList(),
)

internal data class StatisticsChartPage(
    val points: List<StatisticsTrendPoint>,
    val averageSeconds: Double,
)

internal data class CurrentRangeStatisticsUi(
    val mode: StatisticsRangeMode = StatisticsRangeMode.Week,
    val range: StatisticsDateRange,
    val pageCount: Int = 1,
    val selectedPage: Int = 0,
    val chartPages: Map<Int, StatisticsChartPage> = emptyMap(),
    val summary: StatisticsRangeSummary,
    val selectedBucket: StatisticsDateRange? = null,
    val trendAverageSeconds: Double = 0.0,
    val periodChangePercent: Double? = null,
    val trendPoints: List<StatisticsTrendPoint> = emptyList(),
    val distributionRows: List<BookDistributionRow> = emptyList(),
)

internal data class StatisticsTargetSettingsUi(
    val values: StatisticsTargetSettings = StatisticsTargetSettings(),
    val isEditorVisible: Boolean = false,
    val error: UiText? = null,
)

internal data class StatisticsEmptyState(
    val hasAnyStatistics: Boolean,
    val hasPartialReadError: Boolean,
)

internal data class StatisticsGoalStreak(
    val count: Int = 0,
    val range: StatisticsDateRange? = null,
)

internal data class StatisticsHistoryUi(
    val currentStreak: StatisticsGoalStreak = StatisticsGoalStreak(),
    val longestStreak: StatisticsGoalStreak = StatisticsGoalStreak(),
    val metDays: Int = 0,
    val readingDays: Int = 0,
    val bestDay: StatisticsDayAggregate? = null,
)

internal data class StatisticsUiState(
    val isLoading: Boolean = true,
    val today: TodayStatisticsUi,
    val settings: StatisticsTargetSettingsUi,
    val heatmap: StatisticsHeatmapUi,
    val currentRange: CurrentRangeStatisticsUi,
    val emptyState: StatisticsEmptyState? = null,
    val history: StatisticsHistoryUi = StatisticsHistoryUi(),
)

internal sealed interface StatisticsEvent {
    data object OpenTargetSettings : StatisticsEvent
    data object DismissTargetSettings : StatisticsEvent
    data class SelectDailyTargetType(val type: DailyTargetType) : StatisticsEvent
    data class UpdateDailyCharacterTarget(val characters: Int) : StatisticsEvent
    data class UpdateDailyDurationTargetMinutes(val minutes: Int) : StatisticsEvent
    data class SelectRangeMode(val mode: StatisticsRangeMode) : StatisticsEvent
    data class SelectPeriodPage(val index: Int) : StatisticsEvent
    data class SelectTrendBucket(val key: String?) : StatisticsEvent
}
