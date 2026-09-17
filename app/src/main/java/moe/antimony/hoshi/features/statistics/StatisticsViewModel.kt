package moe.antimony.hoshi.features.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.DefaultDispatcher
import moe.antimony.hoshi.features.reader.ReaderSettingsRepository

@HiltViewModel
internal class StatisticsViewModel internal constructor(
    private val repository: StatisticsRepository,
    private val settings: Flow<StatisticsTargetSettings>,
    private val updateSettings: suspend ((StatisticsTargetSettings) -> StatisticsTargetSettings) -> Unit,
    private val resetMinutes: Flow<Int>,
    private val dateProvider: StatisticsDateProvider,
    private val calculationDispatcher: CoroutineDispatcher,
    private val coroutineScope: CoroutineScope?,
) : ViewModel() {
    @Inject
    constructor(
        repository: StatisticsRepository,
        settingsRepository: StatisticsSettingsRepository,
        readerSettingsRepository: ReaderSettingsRepository,
        dateProvider: StatisticsDateProvider,
        @DefaultDispatcher calculationDispatcher: CoroutineDispatcher,
    ) : this(
        repository = repository,
        settings = settingsRepository.settings,
        updateSettings = settingsRepository::update,
        resetMinutes = readerSettingsRepository.settings.map { it.statisticsResetMinutes },
        dateProvider = dateProvider,
        calculationDispatcher = calculationDispatcher,
        coroutineScope = null,
    )

    private val scope: CoroutineScope
        get() = coroutineScope ?: viewModelScope

    private val snapshot = MutableStateFlow(StatisticsSnapshot(days = emptyList(), availableYears = emptyList()))
    private val selection = MutableStateFlow(StatisticsSelectionState())
    private val isLoading = MutableStateFlow(true)
    private var currentResetMinutes = 0
    private val _uiState = MutableStateFlow(
        buildStatisticsUiState(
            snapshot = snapshot.value,
            settings = StatisticsTargetSettings(),
            selection = selection.value,
            today = currentDate(),
            isLoading = true,
        ),
    )
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()
    private var reloadJob: Job? = null
    private var reloadGeneration = 0

    init {
        scope.launch {
            combine(snapshot, settings, resetMinutes, selection, isLoading) { snapshot, settings, resetMinutes, selection, isLoading ->
                currentResetMinutes = resetMinutes
                withContext(calculationDispatcher) {
                    buildStatisticsUiState(
                        snapshot = snapshot,
                        settings = settings,
                        selection = selection,
                        today = currentDate(),
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
                )
            }
            is StatisticsEvent.SelectRangeMode -> selection.update { current ->
                current.copy(
                    rangeMode = event.mode,
                    currentRangeTab = current.currentRangeTab.availableFor(event.mode),
                )
            }
            is StatisticsEvent.SelectCalendarDate -> selection.update { current ->
                val nextMode = if (current.rangeMode == StatisticsRangeMode.Year || current.rangeMode == StatisticsRangeMode.All) {
                    StatisticsRangeMode.Day
                } else {
                    current.rangeMode
                }
                current.copy(
                    rangeMode = nextMode,
                    anchorDate = minOf(event.date, currentDate()),
                    currentRangeTab = current.currentRangeTab.availableFor(nextMode),
                )
            }
            is StatisticsEvent.NavigatePeriod -> selection.update { current ->
                val today = currentDate()
                val anchor = current.anchorDate ?: today
                val next = shiftedStatisticsAnchor(current.rangeMode, anchor, event.offset)
                if (next == null || next.isAfter(today)) current else current.copy(anchorDate = next)
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

    private fun currentDate(): LocalDate = dateProvider.currentDate(currentResetMinutes)

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
    val anchor = minOf(selection.anchorDate ?: today, today)
    val rangeMode = selection.rangeMode
    val selectedRange = selectedStatisticsRange(
        rangeMode, anchor, today,
        firstActivity = snapshot.days.filter { it.isActiveReadingDay() && !it.date.isAfter(today) }.minOfOrNull { it.date },
    )
    val currentTab = selection.currentRangeTab.availableFor(rangeMode)
    val rangeDays = snapshot.days.filter { selectedRange.contains(it.date) && !it.date.isAfter(today) }
    val windowDays = datesInRange(windowRange).map { date -> daysByDate[date] ?: emptyDayAggregate(date) }
    val heatLevelsByDate = readingHeatLevels(windowDays)
    val availableWindows = listOf(StatisticsCalendarWindowSelection(StatisticsCalendarWindowKind.RecentYear)) +
        snapshot.availableYears
            .distinct()
            .sortedDescending()
            .map { year ->
                StatisticsCalendarWindowSelection(StatisticsCalendarWindowKind.FixedYear, year)
            }
    val rangeSummary = overviewRangeSummary(snapshot.days, settings, rangeMode, anchor, today)
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
            selectedRangeTitle = selectedRange.dateTitle(rangeMode),
            days = windowDays.map { aggregate ->
                val ratio = aggregate.targetRatio(settings)
                StatisticsCalendarDayUi(
                    date = aggregate.date,
                    heatLevel = heatLevelsByDate[aggregate.date] ?: 0,
                    characters = aggregate.totalCharacters,
                    readingSeconds = aggregate.readingSeconds,
                    targetPercent = (ratio * 100.0).toInt(),
                    targetMet = ratio >= 1.0,
                    inSelectedRange = rangeMode != StatisticsRangeMode.Year && rangeMode != StatisticsRangeMode.All && selectedRange.contains(aggregate.date),
                    isAnchor = rangeMode != StatisticsRangeMode.Year && rangeMode != StatisticsRangeMode.All && aggregate.date == anchor,
                )
            },
        ),
        currentRange = CurrentRangeStatisticsUi(
            mode = rangeMode,
            selectedTab = currentTab,
            title = selectedRange.dateTitle(rangeMode),
            summary = rangeSummary,
            trendPoints = trendPoints,
            distributionRows = distributionRows,
            canNavigatePrevious = shiftedStatisticsAnchor(rangeMode, anchor, -1) != null,
            canNavigateNext = shiftedStatisticsAnchor(rangeMode, anchor, 1)?.let { !it.isAfter(today) } == true,
        ),
        history = statisticsHistorySummary(snapshot.days, today, settings),
        emptyState = StatisticsEmptyState(
            hasAnyStatistics = snapshot.days.isNotEmpty(),
            hasPartialReadError = snapshot.skippedCorruptBookIds.isNotEmpty(),
        ),
    )
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

private fun StatisticsDateRange.dateTitle(mode: StatisticsRangeMode): String = when (mode) {
    StatisticsRangeMode.Year -> start.year.toString()
    StatisticsRangeMode.Month -> java.time.YearMonth.from(start).toString()
    StatisticsRangeMode.Week, StatisticsRangeMode.All -> "$start – $end"
    StatisticsRangeMode.Day -> start.toString()
}
