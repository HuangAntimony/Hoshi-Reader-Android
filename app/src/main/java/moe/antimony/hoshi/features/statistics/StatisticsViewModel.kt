package moe.antimony.hoshi.features.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.DefaultDispatcher

internal interface StatisticsClock {
    fun today(): LocalDate
}

internal class SystemStatisticsClock @Inject constructor() : StatisticsClock {
    override fun today(): LocalDate = LocalDate.now()
}

@HiltViewModel
internal class StatisticsViewModel internal constructor(
    private val repository: StatisticsRepository,
    private val settings: Flow<StatisticsTargetSettings>,
    private val updateSettings: suspend ((StatisticsTargetSettings) -> StatisticsTargetSettings) -> Unit,
    private val clock: StatisticsClock,
    private val calculationDispatcher: CoroutineDispatcher,
    private val coroutineScope: CoroutineScope?,
) : ViewModel() {
    @Inject
    constructor(
        repository: StatisticsRepository,
        settingsRepository: StatisticsSettingsRepository,
        clock: StatisticsClock,
        @DefaultDispatcher calculationDispatcher: CoroutineDispatcher,
    ) : this(
        repository = repository,
        settings = settingsRepository.settings,
        updateSettings = settingsRepository::update,
        clock = clock,
        calculationDispatcher = calculationDispatcher,
        coroutineScope = null,
    )

    private val scope: CoroutineScope
        get() = coroutineScope ?: viewModelScope

    private val snapshot = MutableStateFlow(StatisticsSnapshot(days = emptyList(), availableYears = emptyList()))
    private val selection = MutableStateFlow(StatisticsSelectionState())
    private val isLoading = MutableStateFlow(true)
    private val _uiState = MutableStateFlow(
        buildStatisticsUiState(
            snapshot = snapshot.value,
            settings = StatisticsTargetSettings(),
            selection = selection.value,
            today = clock.today(),
            isLoading = true,
        ),
    )
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()
    private var reloadJob: Job? = null
    private var reloadGeneration = 0

    init {
        scope.launch {
            combine(snapshot, settings, selection, isLoading) { snapshot, settings, selection, isLoading ->
                withContext(calculationDispatcher) {
                    buildStatisticsUiState(
                        snapshot = snapshot,
                        settings = settings,
                        selection = selection,
                        today = clock.today(),
                        isLoading = isLoading,
                    )
                }
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun reload() {
        val generation = ++reloadGeneration
        reloadJob?.cancel()
        reloadJob = scope.launch {
            isLoading.value = true
            try {
                val loaded = repository.loadSnapshot()
                if (generation == reloadGeneration) {
                    snapshot.value = loaded
                }
            } finally {
                if (generation == reloadGeneration) {
                    isLoading.value = false
                }
            }
        }
    }

    fun onEvent(event: StatisticsEvent) {
        when (event) {
            is StatisticsEvent.ToggleTargetSettings -> selection.update {
                it.copy(
                    expandedTargetEditor = if (it.expandedTargetEditor == event.focus) null else event.focus,
                )
            }
            is StatisticsEvent.SelectDailyTargetType -> updateTargets {
                it.copy(dailyTargetType = event.type)
            }
            is StatisticsEvent.UpdateDailyCharacterTarget -> updateTargets {
                it.copy(dailyCharacterTarget = event.characters).coerceStatisticsTargetSettings()
            }
            is StatisticsEvent.UpdateDailyDurationTargetMinutes -> updateTargets {
                it.copy(dailyDurationTargetMinutes = event.minutes).coerceStatisticsTargetSettings()
            }
            is StatisticsEvent.UpdateWeeklyTargetDays -> updateTargets {
                it.copy(weeklyTargetDays = event.days).coerceStatisticsTargetSettings()
            }
            is StatisticsEvent.SelectCalendarWindow -> selection.update { current ->
                current.copy(
                    windowSelection = event.window,
                    rangeMode = StatisticsRangeMode.Year,
                    anchorDate = anchorForWindow(snapshot.value, event.window, clock.today()),
                    currentRangeTab = current.currentRangeTab,
                )
            }
            is StatisticsEvent.SelectRangeMode -> selection.update { current ->
                current.copy(
                    rangeMode = event.mode,
                    currentRangeTab = current.currentRangeTab.availableFor(event.mode),
                )
            }
            is StatisticsEvent.SelectCalendarDate -> selection.update { current ->
                val nextMode = if (current.rangeMode == StatisticsRangeMode.Year) {
                    StatisticsRangeMode.Day
                } else {
                    current.rangeMode
                }
                current.copy(
                    rangeMode = nextMode,
                    anchorDate = event.date,
                    currentRangeTab = current.currentRangeTab.availableFor(nextMode),
                )
            }
            is StatisticsEvent.SelectCurrentRangeTab -> selection.update { current ->
                if (event.tab == CurrentRangeTab.Trend && current.rangeMode == StatisticsRangeMode.Day) {
                    current
                } else {
                    current.copy(currentRangeTab = event.tab)
                }
            }
        }
    }

    private fun updateTargets(transform: (StatisticsTargetSettings) -> StatisticsTargetSettings) {
        scope.launch {
            updateSettings { current -> transform(current).coerceStatisticsTargetSettings() }
        }
    }
}

private data class StatisticsSelectionState(
    val windowSelection: StatisticsCalendarWindowSelection = StatisticsCalendarWindowSelection(
        StatisticsCalendarWindowKind.RecentYear,
    ),
    val rangeMode: StatisticsRangeMode = StatisticsRangeMode.Year,
    val anchorDate: LocalDate? = null,
    val currentRangeTab: CurrentRangeTab = CurrentRangeTab.Overview,
    val expandedTargetEditor: StatisticsTargetSettingsFocus? = null,
)

private fun buildStatisticsUiState(
    snapshot: StatisticsSnapshot,
    settings: StatisticsTargetSettings,
    selection: StatisticsSelectionState,
    today: LocalDate,
    isLoading: Boolean,
): StatisticsUiState {
    val daysByDate = snapshot.days.associateBy { it.date }
    val windowSelection = selection.windowSelection
    val windowRange = windowRange(windowSelection, today)
    val anchor = windowRange.coerce(selection.anchorDate ?: anchorForWindow(snapshot, windowSelection, today))
    val rangeMode = selection.rangeMode
    val selectedRange = selectedStatisticsRange(rangeMode, anchor, windowRange)
    val currentTab = selection.currentRangeTab.availableFor(rangeMode)
    val rangeDays = datesInRange(selectedRange).map { date -> daysByDate[date] ?: emptyDayAggregate(date) }
    val windowDays = datesInRange(windowRange).map { date -> daysByDate[date] ?: emptyDayAggregate(date) }
    val heatLevelsByDate = readingHeatLevels(windowDays)
    val availableWindows = listOf(StatisticsCalendarWindowSelection(StatisticsCalendarWindowKind.RecentYear)) +
        snapshot.availableYears
            .distinct()
            .sortedDescending()
            .map { year ->
                StatisticsCalendarWindowSelection(StatisticsCalendarWindowKind.FixedYear, year)
            }
    val rangeSummary = aggregateRange(rangeDays, settings)
    val trendPoints = if (currentTab == CurrentRangeTab.Trend) {
        trendPoints(rangeMode, selectedRange, rangeDays)
    } else {
        emptyList()
    }
    val distributionRows = if (currentTab == CurrentRangeTab.Distribution) {
        distributionRows(rangeDays, settings)
    } else {
        emptyList()
    }
    return StatisticsUiState(
        isLoading = isLoading,
        today = todaySummary(daysByDate, today, settings),
        week = currentWeekSummary(snapshot.days, today, settings),
        settings = StatisticsTargetSettingsUi(
            values = settings.coerceStatisticsTargetSettings(),
            expandedEditor = selection.expandedTargetEditor,
        ),
        calendar = StatisticsCalendarUi(
            windowSelection = windowSelection,
            availableWindows = availableWindows,
            windowRange = windowRange,
            rangeMode = rangeMode,
            anchorDate = anchor,
            selectedRange = selectedRange,
            selectedRangeTitle = selectedRange.title(rangeMode, windowSelection),
            days = windowDays.map { aggregate ->
                val ratio = aggregate.targetRatio(settings)
                StatisticsCalendarDayUi(
                    date = aggregate.date,
                    heatLevel = heatLevelsByDate[aggregate.date] ?: 0,
                    characters = aggregate.totalCharacters,
                    readingSeconds = aggregate.readingSeconds,
                    targetPercent = (ratio * 100.0).toInt(),
                    targetMet = ratio >= 1.0,
                    inSelectedRange = rangeMode != StatisticsRangeMode.Year && selectedRange.contains(aggregate.date),
                    isAnchor = rangeMode != StatisticsRangeMode.Year && aggregate.date == anchor,
                )
            },
        ),
        currentRange = CurrentRangeStatisticsUi(
            mode = rangeMode,
            selectedTab = currentTab,
            title = selectedRange.title(rangeMode, windowSelection),
            summary = rangeSummary,
            trendPoints = trendPoints,
            distributionRows = distributionRows,
        ),
        emptyState = StatisticsEmptyState(
            hasAnyStatistics = snapshot.days.isNotEmpty(),
            hasPartialReadError = snapshot.skippedCorruptBookIds.isNotEmpty(),
        ),
    )
}

private fun anchorForWindow(
    snapshot: StatisticsSnapshot,
    windowSelection: StatisticsCalendarWindowSelection,
    today: LocalDate,
): LocalDate {
    val range = windowRange(windowSelection, today)
    return snapshot.days
        .asSequence()
        .map { it.date }
        .filter(range::contains)
        .maxOrNull()
        ?: range.end
}

private fun windowRange(
    selection: StatisticsCalendarWindowSelection,
    today: LocalDate,
): StatisticsDateRange =
    when (selection.kind) {
        StatisticsCalendarWindowKind.RecentYear -> recentYearStatisticsWindow(today)
        StatisticsCalendarWindowKind.FixedYear -> fixedYearStatisticsWindow(selection.year ?: today.year, today)
    }

private fun CurrentRangeTab.availableFor(mode: StatisticsRangeMode): CurrentRangeTab =
    if (mode == StatisticsRangeMode.Day && this == CurrentRangeTab.Trend) {
        CurrentRangeTab.Overview
    } else {
        this
    }

private fun StatisticsDateRange.title(
    mode: StatisticsRangeMode,
    windowSelection: StatisticsCalendarWindowSelection,
): String =
    when (mode) {
        StatisticsRangeMode.Year -> when (windowSelection.kind) {
            StatisticsCalendarWindowKind.RecentYear -> "Recent year"
            StatisticsCalendarWindowKind.FixedYear -> "${windowSelection.year ?: start.year}"
        }
        StatisticsRangeMode.Month -> "${start.year}-${start.monthValue}"
        StatisticsRangeMode.Week -> "${start.monthValue}/${start.dayOfMonth}-${end.monthValue}/${end.dayOfMonth}"
        StatisticsRangeMode.Day -> {
            val weekday = start.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            "${start.monthValue}/${start.dayOfMonth} $weekday"
        }
    }
