package moe.antimony.hoshi.features.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText

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
            StatisticsEvent.OpenTargetSettings -> selection.update { it.copy(isTargetEditorVisible = true, targetError = null) }
            StatisticsEvent.DismissTargetSettings -> selection.update { it.copy(isTargetEditorVisible = false, targetError = null) }
            is StatisticsEvent.SelectDailyTargetType -> updateTargets {
                it.copy(dailyTargetType = event.type)
            }
            is StatisticsEvent.UpdateDailyCharacterTarget -> updateTargets {
                it.copy(dailyCharacterTarget = event.characters).coerceStatisticsTargetSettings()
            }
            is StatisticsEvent.UpdateDailyDurationTargetMinutes -> updateTargets {
                it.copy(dailyDurationTargetMinutes = event.minutes).coerceStatisticsTargetSettings()
            }
            is StatisticsEvent.SelectRangeMode -> selection.update { current ->
                current.copy(rangeMode = event.mode, referenceDate = null, selectedBucketKey = null)
            }
            is StatisticsEvent.SelectPeriodPage -> selection.update { current ->
                val pageCount = uiState.value.currentRange.pageCount
                if (event.index !in 0 until pageCount) current else current.copy(
                    referenceDate = statisticsPeriodPageAnchor(current.rangeMode, event.index, pageCount, currentDate()),
                    selectedBucketKey = null,
                )
            }
            is StatisticsEvent.SelectTrendBucket -> selection.update { current ->
                val valid = event.key == null || uiState.value.currentRange.trendPoints.any {
                    it.key == event.key && statisticsTrendBucket(current.rangeMode, it.key)?.start?.isAfter(currentDate()) == false
                }
                if (valid) current.copy(
                    selectedBucketKey = event.key.takeUnless { it == current.selectedBucketKey },
                ) else current
            }
        }
    }

    private fun currentDate(): LocalDate = dateProvider.currentDate(currentResetMinutes)

    private fun updateTargets(transform: (StatisticsTargetSettings) -> StatisticsTargetSettings) {
        scope.launch {
            try {
                updateSettings { current -> transform(current).coerceStatisticsTargetSettings() }
                selection.update { it.copy(targetError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                selection.update { it.copy(targetError = UiText.Resource(R.string.statistics_goal_save_failed)) }
            }
        }
    }
}

private data class StatisticsSelectionState(
    val rangeMode: StatisticsRangeMode = StatisticsRangeMode.Week,
    val referenceDate: LocalDate? = null,
    val selectedBucketKey: String? = null,
    val isTargetEditorVisible: Boolean = false,
    val targetError: UiText? = null,
)

private fun buildStatisticsUiState(
    snapshot: StatisticsSnapshot,
    settings: StatisticsTargetSettings,
    selection: StatisticsSelectionState,
    today: LocalDate,
    isLoading: Boolean,
): StatisticsUiState {
    val daysByDate = snapshot.days.associateBy { it.date }
    val rangeMode = selection.rangeMode
    val activity = snapshot.days.filter { it.isActiveReadingDay() && !it.date.isAfter(today) }
    val firstDate = activity.minOfOrNull { it.date } ?: today
    val earliestPeriod = selectedStatisticsRange(rangeMode, firstDate, today, firstDate).start
    val anchor = minOf(selection.referenceDate ?: today, today).coerceAtLeast(earliestPeriod)
    val selectedRange = selectedStatisticsRange(rangeMode, anchor, today, firstDate)
    val pageCount = statisticsPeriodPageCount(rangeMode, firstDate, today)
    val selectedPage = (pageCount - statisticsPeriodPageCount(rangeMode, anchor, today)).coerceIn(0, pageCount - 1)
    val heatLevels = readingHeatLevels(activity)
    val rangeDays = activity.filter { selectedRange.contains(it.date) }
    val rangeSummary = overviewRangeSummary(snapshot.days, settings, rangeMode, anchor, today)
    val trendPoints = trendPoints(rangeMode, selectedRange, rangeDays)
    val selectedBucket = selection.selectedBucketKey?.let { statisticsTrendBucket(rangeMode, it) }
        ?.takeIf { !it.end.isBefore(selectedRange.start) && !it.start.isAfter(selectedRange.end) && !it.start.isAfter(today) }
    val detailDays = selectedBucket?.let { bucket -> rangeDays.filter { bucket.contains(it.date) } } ?: rangeDays
    val distributionRows = distributionRows(detailDays)
    return StatisticsUiState(
        isLoading = isLoading,
        today = todaySummary(daysByDate, today, settings),
        settings = StatisticsTargetSettingsUi(
            values = settings.coerceStatisticsTargetSettings(),
            isEditorVisible = selection.isTargetEditorVisible,
            error = selection.targetError,
        ),
        heatmap = StatisticsHeatmapUi(
            windowRange = StatisticsDateRange(statisticsStartOfWeek(firstDate), today),
            days = activity.map { StatisticsHeatmapDayUi(it.date, heatLevels[it.date] ?: 0) },
        ),
        currentRange = CurrentRangeStatisticsUi(
            mode = rangeMode,
            range = selectedRange,
            pageCount = pageCount,
            selectedPage = selectedPage,
            chartPages = (maxOf(0, selectedPage - 1)..minOf(pageCount - 1, selectedPage + 1)).associateWith { page ->
                val pageAnchor = statisticsPeriodPageAnchor(rangeMode, page, pageCount, today)
                val pageRange = selectedStatisticsRange(rangeMode, pageAnchor, today, firstDate)
                StatisticsChartPage(
                    points = trendPoints(rangeMode, pageRange, activity.filter { pageRange.contains(it.date) }),
                    averageSeconds = overviewRangeSummary(activity, settings, rangeMode, pageAnchor, today).averageReadingSecondsPerBucket,
                )
            },
            summary = if (selectedBucket != null) aggregateRange(detailDays, settings) else rangeSummary,
            selectedBucket = selectedBucket,
            trendAverageSeconds = rangeSummary.averageReadingSecondsPerBucket,
            periodChangePercent = rangeSummary.averageReadingTimeChangePercent,
            trendPoints = trendPoints,
            distributionRows = distributionRows,
        ),
        history = statisticsHistorySummary(snapshot.days, today, settings),
        emptyState = StatisticsEmptyState(
            hasAnyStatistics = snapshot.days.isNotEmpty(),
            hasPartialReadError = snapshot.skippedCorruptBookIds.isNotEmpty(),
        ),
    )
}
