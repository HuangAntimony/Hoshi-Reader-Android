package moe.antimony.hoshi.features.statistics

import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsViewModelTest {
    @Test
    fun initialStateUsesRecentYearHeatmapAndNaturalYearAnchoredToToday() = runBlocking {
        viewModel(
            snapshot = snapshot(
                day("2026-06-28", characters = 1_000),
                day("2026-06-29", characters = 2_000),
            ),
        ).use { viewModel ->
            viewModel.reload()

            val state = viewModel.uiState.value
            assertEquals(StatisticsCalendarWindowKind.RecentYear, state.calendar.windowSelection.kind)
            assertEquals(StatisticsRangeMode.Year, state.calendar.rangeMode)
            assertEquals(LocalDate.parse("2026-06-30"), state.calendar.anchorDate)
            assertEquals(CurrentRangeTab.Overview, state.currentRange.selectedTab)
        }
    }

    @Test
    fun recentYearWithNoRecordsAnchorsToWindowEnd() = runBlocking {
        viewModel(snapshot = snapshot()).use { viewModel ->
            viewModel.reload()

            assertEquals(LocalDate.parse("2026-06-30"), viewModel.uiState.value.calendar.anchorDate)
        }
    }

    @Test
    fun dashboardUsesConfiguredResetTimeForCurrentStatisticsDay() = runBlocking {
        viewModel(
            snapshot = snapshot(),
            resetMinutes = 105,
            dateProvider = object : StatisticsDateProvider {
                override fun currentDate(resetMinutes: Int): LocalDate =
                    if (resetMinutes == 105) {
                        LocalDate.parse("2026-06-29")
                    } else {
                        LocalDate.parse("2026-06-30")
                    }
            },
        ).use { viewModel ->
            viewModel.reload()

            assertEquals(LocalDate.parse("2026-06-29"), viewModel.uiState.value.calendar.anchorDate)
        }
    }

    @Test
    fun clickingDateFromYearModeSwitchesToDay() = runBlocking {
        viewModel(snapshot = snapshot(day("2026-06-29", characters = 2_000))).use { viewModel ->
            viewModel.reload()
            viewModel.onEvent(StatisticsEvent.SelectCalendarDate(LocalDate.parse("2026-06-29")))

            val state = viewModel.uiState.value
            assertEquals(StatisticsRangeMode.Day, state.calendar.rangeMode)
            assertEquals(LocalDate.parse("2026-06-29"), state.calendar.anchorDate)
        }
    }

    @Test
    fun clickingDateFromWeekModeKeepsModeAndMovesAnchor() = runBlocking {
        viewModel(snapshot = snapshot(day("2026-06-29", characters = 2_000))).use { viewModel ->
            viewModel.reload()
            viewModel.onEvent(StatisticsEvent.SelectRangeMode(StatisticsRangeMode.Week))
            viewModel.onEvent(StatisticsEvent.SelectCalendarDate(LocalDate.parse("2026-06-29")))

            val state = viewModel.uiState.value
            assertEquals(StatisticsRangeMode.Week, state.calendar.rangeMode)
            assertEquals(LocalDate.parse("2026-06-29"), state.calendar.anchorDate)
        }
    }

    @Test
    fun switchingHeatmapWindowPreservesOverviewModeAndAnchor() = runBlocking {
        viewModel(
            snapshot = snapshot(
                day("2025-02-01", characters = 1_000),
                day("2026-06-29", characters = 2_000),
            ),
        ).use { viewModel ->
            viewModel.reload()
            viewModel.onEvent(StatisticsEvent.SelectRangeMode(StatisticsRangeMode.Month))
            viewModel.onEvent(
                StatisticsEvent.SelectCalendarWindow(
                    StatisticsCalendarWindowSelection(StatisticsCalendarWindowKind.FixedYear, 2025),
                ),
            )

            val state = viewModel.uiState.value
            assertEquals(StatisticsRangeMode.Month, state.calendar.rangeMode)
            assertEquals(LocalDate.parse("2026-06-30"), state.calendar.anchorDate)
            assertEquals(StatisticsDateRange(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30")), state.calendar.selectedRange)
        }
    }

    @Test
    fun availableWindowsKeepRecentYearFirstAndFixedYearsDescending() = runBlocking {
        viewModel(
            snapshot = StatisticsSnapshot(
                days = listOf(day("2024-01-01", characters = 1_000)),
                availableYears = listOf(2024, 2026, 2025),
            ),
        ).use { viewModel ->
            viewModel.reload()

            assertEquals(
                listOf(
                    StatisticsCalendarWindowSelection(StatisticsCalendarWindowKind.RecentYear),
                    StatisticsCalendarWindowSelection(StatisticsCalendarWindowKind.FixedYear, 2026),
                    StatisticsCalendarWindowSelection(StatisticsCalendarWindowKind.FixedYear, 2025),
                    StatisticsCalendarWindowSelection(StatisticsCalendarWindowKind.FixedYear, 2024),
                ),
                viewModel.uiState.value.calendar.availableWindows,
            )
        }
    }

    @Test
    fun fixedWindowWithoutRecordsDoesNotMoveOverviewAnchor() = runBlocking {
        viewModel(snapshot = snapshot(day("2026-06-29", characters = 2_000))).use { viewModel ->
            viewModel.reload()
            viewModel.onEvent(
                StatisticsEvent.SelectCalendarWindow(
                    StatisticsCalendarWindowSelection(StatisticsCalendarWindowKind.FixedYear, 2025),
                ),
            )

            assertEquals(LocalDate.parse("2026-06-30"), viewModel.uiState.value.calendar.anchorDate)
        }
    }

    @Test
    fun dayRangeForcesTrendTabBackToOverview() = runBlocking {
        viewModel(snapshot = snapshot(day("2026-06-29", characters = 2_000))).use { viewModel ->
            viewModel.reload()
            viewModel.onEvent(StatisticsEvent.SelectCurrentRangeTab(CurrentRangeTab.Trend))
            viewModel.onEvent(StatisticsEvent.SelectRangeMode(StatisticsRangeMode.Day))

            assertEquals(CurrentRangeTab.Overview, viewModel.uiState.value.currentRange.selectedTab)
        }
    }

    @Test
    fun calendarHeatLevelsUseWindowWhenShortRangeIsSelected() = runBlocking {
        viewModel(
            snapshot = snapshot(
                day("2026-06-01", characters = 6_000),
                day("2026-06-15", characters = 7_000),
                day("2026-06-29", characters = 8_000),
            ),
        ).use { viewModel ->
            viewModel.reload()
            viewModel.onEvent(StatisticsEvent.SelectRangeMode(StatisticsRangeMode.Week))

            val state = viewModel.uiState.value
            val daysByDate = state.calendar.days.associateBy { it.date }

            assertEquals(StatisticsRangeMode.Week, state.calendar.rangeMode)
            assertEquals(false, daysByDate.getValue(LocalDate.parse("2026-06-01")).inSelectedRange)
            assertEquals(1, daysByDate.getValue(LocalDate.parse("2026-06-01")).heatLevel)
            assertEquals(4, daysByDate.getValue(LocalDate.parse("2026-06-15")).heatLevel)
            assertEquals(7, daysByDate.getValue(LocalDate.parse("2026-06-29")).heatLevel)
        }
    }

    @Test
    fun clickingDateFromTrendYearModeSwitchesToDayAndReturnsToOverview() = runBlocking {
        viewModel(snapshot = snapshot(day("2026-06-29", characters = 2_000))).use { viewModel ->
            viewModel.reload()
            viewModel.onEvent(StatisticsEvent.SelectCurrentRangeTab(CurrentRangeTab.Trend))
            viewModel.onEvent(StatisticsEvent.SelectCalendarDate(LocalDate.parse("2026-06-29")))

            val state = viewModel.uiState.value
            assertEquals(StatisticsRangeMode.Day, state.calendar.rangeMode)
            assertEquals(CurrentRangeTab.Overview, state.currentRange.selectedTab)
        }
    }

    @Test
    fun dayRangeSummaryKeepsTargetProgressForOverviewMetric() = runBlocking {
        viewModel(snapshot = snapshot(day("2026-06-29", characters = 6_250))).use { viewModel ->
            viewModel.reload()
            viewModel.onEvent(StatisticsEvent.SelectCalendarDate(LocalDate.parse("2026-06-29")))

            val summary = viewModel.uiState.value.currentRange.summary
            assertEquals(1, summary.targetDays)
            assertEquals(125, summary.targetProgressPercent)
        }
    }

    @Test
    fun targetSettingsUpdatesRecomputeTodayHistoryCurrentRangeAndDistribution() = runBlocking {
        viewModel(
            snapshot = snapshot(
                day(
                    "2026-06-30",
                    contributions = listOf(
                        contribution("fast", "Fast", characters = 4_000, seconds = 600.0),
                        contribution("slow", "Slow", characters = 1_000, seconds = 1_800.0),
                    ),
                ),
            ),
        ).use { viewModel ->
            viewModel.reload()

            assertEquals(100, viewModel.uiState.value.today.targetPercent)
            viewModel.onEvent(StatisticsEvent.SelectCurrentRangeTab(CurrentRangeTab.Distribution))
            assertEquals(listOf("Fast", "Slow"), viewModel.uiState.value.currentRange.distributionRows.map { it.title })

            viewModel.onEvent(StatisticsEvent.SelectDailyTargetType(DailyTargetType.Duration))
            viewModel.onEvent(StatisticsEvent.UpdateDailyDurationTargetMinutes(30))

            val state = viewModel.uiState.value
            assertEquals(133, state.today.targetPercent)
            assertEquals(1, state.history.metDays)
            assertEquals(1, state.currentRange.summary.targetDays)
            assertEquals(listOf("Slow", "Fast"), state.currentRange.distributionRows.map { it.title })
        }
    }

    @Test
    fun distributionRowsAreOnlyBuiltForDistributionTab() = runBlocking {
        viewModel(
            snapshot = snapshot(
                day(
                    "2026-06-30",
                    contributions = listOf(
                        contribution("fast", "Fast", characters = 4_000, seconds = 600.0),
                        contribution("slow", "Slow", characters = 1_000, seconds = 1_800.0),
                    ),
                ),
            ),
        ).use { viewModel ->
            viewModel.reload()

            assertEquals(CurrentRangeTab.Overview, viewModel.uiState.value.currentRange.selectedTab)
            assertEquals(emptyList<BookDistributionRow>(), viewModel.uiState.value.currentRange.distributionRows)

            viewModel.onEvent(StatisticsEvent.SelectCurrentRangeTab(CurrentRangeTab.Distribution))

            assertEquals(
                listOf("Fast", "Slow"),
                viewModel.uiState.value.currentRange.distributionRows.map { it.title },
            )

            viewModel.onEvent(StatisticsEvent.SelectCurrentRangeTab(CurrentRangeTab.Overview))

            assertEquals(emptyList<BookDistributionRow>(), viewModel.uiState.value.currentRange.distributionRows)
        }
    }

    @Test
    fun trendPointsAreOnlyBuiltForTrendTab() = runBlocking {
        viewModel(
            snapshot = snapshot(
                day("2026-06-01", characters = 1_000, seconds = 600.0),
                day("2026-06-30", characters = 2_000, seconds = 900.0),
            ),
        ).use { viewModel ->
            viewModel.reload()

            assertEquals(CurrentRangeTab.Overview, viewModel.uiState.value.currentRange.selectedTab)
            assertEquals(emptyList<StatisticsTrendPoint>(), viewModel.uiState.value.currentRange.trendPoints)

            viewModel.onEvent(StatisticsEvent.SelectCurrentRangeTab(CurrentRangeTab.Trend))

            assertEquals(
                listOf(
                    "2026-01",
                    "2026-02",
                    "2026-03",
                    "2026-04",
                    "2026-05",
                    "2026-06",
                    "2026-07",
                    "2026-08",
                    "2026-09",
                    "2026-10",
                    "2026-11",
                    "2026-12",
                ),
                viewModel.uiState.value.currentRange.trendPoints.map { it.key },
            )

            viewModel.onEvent(StatisticsEvent.SelectCurrentRangeTab(CurrentRangeTab.Overview))

            assertEquals(emptyList<StatisticsTrendPoint>(), viewModel.uiState.value.currentRange.trendPoints)
        }
    }

    @Test
    fun weekOverviewUsesDailyGoalAndRetainsReadingTotalsAfterGoalChanges() = runBlocking {
        viewModel(
            snapshot = snapshot(
                day("2026-06-29", characters = 5_000),
                day("2026-06-30", characters = 5_000),
            ),
        ).use { viewModel ->
            viewModel.reload()
            viewModel.onEvent(StatisticsEvent.SelectRangeMode(StatisticsRangeMode.Week))
            assertEquals(10_000, viewModel.uiState.value.currentRange.summary.totalCharacters)
            assertEquals(2, viewModel.uiState.value.currentRange.summary.targetDays)
            assertEquals(2, viewModel.uiState.value.history.currentStreak.count)

            viewModel.onEvent(StatisticsEvent.UpdateDailyCharacterTarget(6_000))

            assertEquals(10_000, viewModel.uiState.value.currentRange.summary.totalCharacters)
            assertEquals(0, viewModel.uiState.value.currentRange.summary.targetDays)
            assertEquals(0, viewModel.uiState.value.history.currentStreak.count)
        }
    }

    @Test
    fun dailyTargetEditorTogglesInline() = runBlocking {
        viewModel(snapshot = snapshot()).use { viewModel ->
            viewModel.reload()
            assertEquals(false, viewModel.uiState.value.settings.isEditorExpanded)

            viewModel.onEvent(StatisticsEvent.ToggleTargetSettings)
            assertEquals(true, viewModel.uiState.value.settings.isEditorExpanded)

            viewModel.onEvent(StatisticsEvent.ToggleTargetSettings)
            assertEquals(false, viewModel.uiState.value.settings.isEditorExpanded)
        }
    }

    @Test
    fun reloadIgnoresOlderSnapshotWhenNewerReloadCompletesFirst() = runBlocking {
        val firstLoad = CompletableDeferred<StatisticsSnapshot>()
        val secondLoad = CompletableDeferred<StatisticsSnapshot>()
        viewModel(
            repository = DeferredStatisticsRepository(firstLoad, secondLoad),
        ).use { viewModel ->
            viewModel.reload()
            viewModel.reload()

            secondLoad.complete(snapshot(day("2026-06-30", characters = 2_000)))
            yield()
            firstLoad.complete(snapshot(day("2026-06-29", characters = 1_000)))
            yield()

            assertEquals(LocalDate.parse("2026-06-30"), viewModel.uiState.value.calendar.anchorDate)
        }
    }

    @Test
    fun loadingStaysTrueWhenSelectionAndSettingsChangeDuringReload() = runBlocking {
        val pendingLoad = CompletableDeferred<StatisticsSnapshot>()
        viewModel(
            repository = DeferredStatisticsRepository(pendingLoad),
        ).use { viewModel ->
            viewModel.reload()
            yield()
            assertEquals(true, viewModel.uiState.value.isLoading)

            viewModel.onEvent(StatisticsEvent.SelectRangeMode(StatisticsRangeMode.Month))
            yield()
            assertEquals(true, viewModel.uiState.value.isLoading)

            viewModel.onEvent(StatisticsEvent.UpdateDailyCharacterTarget(6_000))
            yield()
            assertEquals(true, viewModel.uiState.value.isLoading)

            pendingLoad.complete(snapshot(day("2026-06-30", characters = 2_000)))
            yield()

            assertEquals(false, viewModel.uiState.value.isLoading)
        }
    }

    @Test
    fun periodNavigationPreservesHeatmapAndStopsBeforeFuturePeriod() = runBlocking {
        viewModel(snapshot = snapshot(day("2024-02-29", 5_000))).use { viewModel ->
            viewModel.reload()
            val heatmap = viewModel.uiState.value.calendar.windowRange
            assertEquals(false, viewModel.uiState.value.currentRange.canNavigateNext)
            viewModel.onEvent(StatisticsEvent.NavigatePeriod(1))
            assertEquals(2026, viewModel.uiState.value.calendar.selectedRange.start.year)
            viewModel.onEvent(StatisticsEvent.NavigatePeriod(-1))
            assertEquals(2025, viewModel.uiState.value.calendar.selectedRange.start.year)
            assertEquals(true, viewModel.uiState.value.currentRange.canNavigateNext)
            assertEquals(heatmap, viewModel.uiState.value.calendar.windowRange)
            viewModel.onEvent(StatisticsEvent.NavigatePeriod(1))
            assertEquals(2026, viewModel.uiState.value.calendar.selectedRange.start.year)
            viewModel.onEvent(StatisticsEvent.SelectRangeMode(StatisticsRangeMode.All))
            assertEquals(false, viewModel.uiState.value.currentRange.canNavigatePrevious)
            assertEquals(false, viewModel.uiState.value.currentRange.canNavigateNext)
        }
    }

    @Test
    fun allUsesCompleteHistoryAndDateClickSwitchesToDay() = runBlocking {
        viewModel(snapshot = snapshot(day("2001-01-01", 6_000), day("2026-06-30", 5_000))).use { viewModel ->
            viewModel.reload()
            val history = viewModel.uiState.value.history
            assertEquals(2, history.metDays)
            assertEquals(LocalDate.parse("2001-01-01"), history.bestDay?.date)
            viewModel.onEvent(StatisticsEvent.SelectRangeMode(StatisticsRangeMode.All))
            assertEquals(11_000, viewModel.uiState.value.currentRange.summary.totalCharacters)
            assertEquals(LocalDate.parse("2001-01-01"), viewModel.uiState.value.calendar.selectedRange.start)
            assertEquals(365, viewModel.uiState.value.calendar.days.size)
            viewModel.onEvent(StatisticsEvent.SelectCalendarDate(LocalDate.parse("2026-06-29")))
            assertEquals(StatisticsRangeMode.Day, viewModel.uiState.value.currentRange.mode)
            assertEquals(0, viewModel.uiState.value.currentRange.summary.totalCharacters)
            assertEquals(history, viewModel.uiState.value.history)
        }
    }

    @Test
    fun monthDateSelectionChangesAnchorAndHeatmapWindowKeepsTrendTab() = runBlocking {
        viewModel(snapshot = snapshot(day("2024-02-29", 5_000))).use { viewModel ->
            viewModel.reload()
            viewModel.onEvent(StatisticsEvent.SelectRangeMode(StatisticsRangeMode.Month))
            viewModel.onEvent(StatisticsEvent.SelectCalendarDate(LocalDate.parse("2024-02-29")))
            viewModel.onEvent(StatisticsEvent.SelectCurrentRangeTab(CurrentRangeTab.Trend))
            viewModel.onEvent(StatisticsEvent.SelectCalendarWindow(
                StatisticsCalendarWindowSelection(StatisticsCalendarWindowKind.FixedYear, 2025),
            ))
            val state = viewModel.uiState.value
            assertEquals(StatisticsRangeMode.Month, state.currentRange.mode)
            assertEquals(CurrentRangeTab.Trend, state.currentRange.selectedTab)
            assertEquals(LocalDate.parse("2024-02-29"), state.calendar.anchorDate)
            assertEquals(29, state.currentRange.trendPoints.size)
            assertEquals(5_000, state.currentRange.summary.totalCharacters)
        }
    }

    private fun viewModel(
        snapshot: StatisticsSnapshot,
        settings: StatisticsTargetSettings = StatisticsTargetSettings(),
        resetMinutes: Int = 0,
        dateProvider: StatisticsDateProvider = FakeStatisticsDateProvider(LocalDate.parse("2026-06-30")),
    ): ViewModelHandle =
        viewModel(
            repository = FakeStatisticsRepository(snapshot),
            settings = settings,
            resetMinutes = resetMinutes,
            dateProvider = dateProvider,
        )

    private fun viewModel(
        repository: StatisticsRepository,
        settings: StatisticsTargetSettings = StatisticsTargetSettings(),
        resetMinutes: Int = 0,
        dateProvider: StatisticsDateProvider = FakeStatisticsDateProvider(LocalDate.parse("2026-06-30")),
    ): ViewModelHandle {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val settingsFlow = MutableStateFlow(settings)
        val resetMinutesFlow = MutableStateFlow(resetMinutes)
        return ViewModelHandle(
            StatisticsViewModel(
                repository = repository,
                settings = settingsFlow,
                updateSettings = { transform -> settingsFlow.value = transform(settingsFlow.value) },
                resetMinutes = resetMinutesFlow,
                dateProvider = dateProvider,
                calculationDispatcher = Dispatchers.Unconfined,
                coroutineScope = scope,
            ),
            scope,
        )
    }

    private class ViewModelHandle(
        private val viewModel: StatisticsViewModel,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        val uiState: StateFlow<StatisticsUiState> get() = viewModel.uiState
        fun reload() = viewModel.reload()
        fun onEvent(event: StatisticsEvent) = viewModel.onEvent(event)
        override fun close() {
            scope.cancel()
        }
    }

    private class FakeStatisticsRepository(
        private val snapshot: StatisticsSnapshot,
    ) : StatisticsRepositoryFake() {
        override suspend fun loadSnapshot(): StatisticsSnapshot = snapshot
    }

    private class DeferredStatisticsRepository(
        vararg loads: CompletableDeferred<StatisticsSnapshot>,
    ) : StatisticsRepositoryFake() {
        private val pendingLoads = ArrayDeque(loads.toList())

        override suspend fun loadSnapshot(): StatisticsSnapshot =
            pendingLoads.removeFirst().await()
    }

    private class FakeStatisticsDateProvider(
        private val date: LocalDate,
    ) : StatisticsDateProvider {
        override fun currentDate(resetMinutes: Int): LocalDate = date
    }

    private fun snapshot(vararg days: StatisticsDayAggregate): StatisticsSnapshot =
        StatisticsSnapshot(
            days = days.toList(),
            availableYears = days.map { it.date.year }.distinct().sortedDescending(),
        )

    private fun day(
        date: String,
        characters: Int = 0,
        seconds: Double = 0.0,
        contributions: List<StatisticsBookContribution> = listOf(
            contribution("book-$date", "Book $date", characters, seconds),
        ),
    ): StatisticsDayAggregate =
        StatisticsDayAggregate(
            date = LocalDate.parse(date),
            totalCharacters = if (contributions.size == 1) characters else contributions.sumOf { it.characters },
            readingSeconds = if (contributions.size == 1) seconds else contributions.sumOf { it.readingSeconds },
            activeBookCount = contributions.count { it.characters > 0 || it.readingSeconds > 0.0 },
            bookContributions = contributions,
        )

    private fun contribution(
        bookId: String,
        title: String,
        characters: Int,
        seconds: Double,
    ): StatisticsBookContribution =
        StatisticsBookContribution(
            bookId = bookId,
            title = title,
            coverPath = null,
            characters = characters,
            readingSeconds = seconds,
        )
}
