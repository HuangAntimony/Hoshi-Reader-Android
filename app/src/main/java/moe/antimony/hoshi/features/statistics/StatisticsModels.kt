package moe.antimony.hoshi.features.statistics

import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal enum class StatisticsRangeMode {
    Year,
    Month,
    Week,
    Day,
}

internal enum class StatisticsCalendarWindowKind {
    RecentYear,
    FixedYear,
}

internal data class StatisticsCalendarWindowSelection(
    val kind: StatisticsCalendarWindowKind,
    val year: Int? = null,
)

internal enum class DailyTargetType {
    Characters,
    Duration,
}

internal enum class StatisticsTargetSettingsFocus {
    Daily,
    Weekly,
}

internal enum class CurrentRangeTab {
    Overview,
    Trend,
    Distribution,
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
    val weeklyTargetDays: Int = StatisticsTargetDefaults.WeeklyTargetDays,
)

internal object StatisticsTargetDefaults {
    const val DailyCharacterTarget = 5_000
    const val MinDailyCharacterTarget = 500
    const val MaxDailyCharacterTarget = 20_000
    const val DailyCharacterTargetStep = 500
    const val DailyDurationTargetMinutes = 30
    const val MinDailyDurationTargetMinutes = 5
    const val MaxDailyDurationTargetMinutes = 240
    const val DailyDurationTargetStepMinutes = 5
    const val WeeklyTargetDays = 4
    const val MinWeeklyTargetDays = 1
    const val MaxWeeklyTargetDays = 7
}

internal data class StatisticsBookContribution(
    val bookId: String,
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
)

internal data class WeekDayGoalUi(
    val date: LocalDate,
    val isToday: Boolean,
    val isFuture: Boolean,
    val percent: Int?,
    val metTarget: Boolean,
)

internal data class WeekStatisticsUi(
    val range: StatisticsDateRange,
    val elapsedDays: Int,
    val totalCharacters: Int,
    val readingSeconds: Double,
    val averageSpeedPerHour: Int,
    val targetDays: Int,
    val metTargetDays: Int,
    val dailyStreakDays: Int,
    val weeklyStreakWeeks: Int,
    val averageCharactersPerElapsedDay: Int,
    val averageReadingSecondsPerElapsedDay: Double,
    val days: List<WeekDayGoalUi>,
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
    val label: String,
    val characters: Int,
    val readingSeconds: Double,
)

internal data class BookDistributionRow(
    val title: String,
    val coverPath: String?,
    val characters: Int,
    val readingSeconds: Double,
    val percent: Int,
)

internal data class StatisticsCalendarDayUi(
    val date: LocalDate,
    val heatLevel: Int,
    val characters: Int,
    val readingSeconds: Double,
    val targetPercent: Int,
    val targetMet: Boolean,
    val inSelectedRange: Boolean,
    val isAnchor: Boolean,
)

internal data class StatisticsCalendarUi(
    val windowSelection: StatisticsCalendarWindowSelection = StatisticsCalendarWindowSelection(
        StatisticsCalendarWindowKind.RecentYear,
    ),
    val availableWindows: List<StatisticsCalendarWindowSelection> = listOf(
        StatisticsCalendarWindowSelection(StatisticsCalendarWindowKind.RecentYear),
    ),
    val windowRange: StatisticsDateRange,
    val rangeMode: StatisticsRangeMode = StatisticsRangeMode.Year,
    val anchorDate: LocalDate,
    val selectedRange: StatisticsDateRange,
    val selectedRangeTitle: String,
    val days: List<StatisticsCalendarDayUi> = emptyList(),
)

internal data class CurrentRangeStatisticsUi(
    val mode: StatisticsRangeMode = StatisticsRangeMode.Year,
    val selectedTab: CurrentRangeTab = CurrentRangeTab.Overview,
    val title: String,
    val summary: StatisticsRangeSummary,
    val trendPoints: List<StatisticsTrendPoint> = emptyList(),
    val distributionRows: List<BookDistributionRow> = emptyList(),
)

internal data class StatisticsTargetSettingsUi(
    val values: StatisticsTargetSettings = StatisticsTargetSettings(),
    val expandedEditor: StatisticsTargetSettingsFocus? = null,
)

internal data class StatisticsEmptyState(
    val hasAnyStatistics: Boolean,
    val hasPartialReadError: Boolean,
)

internal data class StatisticsUiState(
    val isLoading: Boolean = true,
    val today: TodayStatisticsUi,
    val week: WeekStatisticsUi,
    val settings: StatisticsTargetSettingsUi,
    val calendar: StatisticsCalendarUi,
    val currentRange: CurrentRangeStatisticsUi,
    val emptyState: StatisticsEmptyState? = null,
)

internal sealed interface StatisticsEvent {
    data class ToggleTargetSettings(val focus: StatisticsTargetSettingsFocus) : StatisticsEvent
    data class SelectDailyTargetType(val type: DailyTargetType) : StatisticsEvent
    data class UpdateDailyCharacterTarget(val characters: Int) : StatisticsEvent
    data class UpdateDailyDurationTargetMinutes(val minutes: Int) : StatisticsEvent
    data class UpdateWeeklyTargetDays(val days: Int) : StatisticsEvent
    data class SelectCalendarWindow(val window: StatisticsCalendarWindowSelection) : StatisticsEvent
    data class SelectRangeMode(val mode: StatisticsRangeMode) : StatisticsEvent
    data class SelectCalendarDate(val date: LocalDate) : StatisticsEvent
    data class SelectCurrentRangeTab(val tab: CurrentRangeTab) : StatisticsEvent
}
