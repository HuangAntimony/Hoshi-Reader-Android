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
    fun initialStateUsesRecentYearYearRangeLatestAnchorAndOverview() = runBlocking {
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
            assertEquals(LocalDate.parse("2026-06-29"), state.calendar.anchorDate)
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
    fun switchingWindowResetsToYearAndAnchorsToLatestRecordInWindow() = runBlocking {
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
            assertEquals(StatisticsRangeMode.Year, state.calendar.rangeMode)
            assertEquals(LocalDate.parse("2025-02-01"), state.calendar.anchorDate)
            assertEquals(StatisticsDateRange(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31")), state.calendar.selectedRange)
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
    fun fixedWindowWithoutRecordsAnchorsToWindowEnd() = runBlocking {
        viewModel(snapshot = snapshot(day("2026-06-29", characters = 2_000))).use { viewModel ->
            viewModel.reload()
            viewModel.onEvent(
                StatisticsEvent.SelectCalendarWindow(
                    StatisticsCalendarWindowSelection(StatisticsCalendarWindowKind.FixedYear, 2025),
                ),
            )

            assertEquals(LocalDate.parse("2025-12-31"), viewModel.uiState.value.calendar.anchorDate)
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
    fun targetSettingsUpdatesRecomputeTodayWeekCurrentRangeAndDistribution() = runBlocking {
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
            assertEquals(1, state.week.metTargetDays)
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
                    "2025-07",
                    "2025-08",
                    "2025-09",
                    "2025-10",
                    "2025-11",
                    "2025-12",
                    "2026-01",
                    "2026-02",
                    "2026-03",
                    "2026-04",
                    "2026-05",
                    "2026-06",
                ),
                viewModel.uiState.value.currentRange.trendPoints.map { it.key },
            )

            viewModel.onEvent(StatisticsEvent.SelectCurrentRangeTab(CurrentRangeTab.Overview))

            assertEquals(emptyList<StatisticsTrendPoint>(), viewModel.uiState.value.currentRange.trendPoints)
        }
    }

    @Test
    fun weeklyTargetChangesRecomputeCurrentWeekGoal() = runBlocking {
        viewModel(
            snapshot = snapshot(
                day("2026-06-29", characters = 5_000),
                day("2026-06-30", characters = 5_000),
            ),
        ).use { viewModel ->
            viewModel.reload()
            assertEquals(4, viewModel.uiState.value.week.targetDays)
            assertEquals(0, viewModel.uiState.value.week.weeklyStreakWeeks)

            viewModel.onEvent(StatisticsEvent.UpdateWeeklyTargetDays(2))

            assertEquals(2, viewModel.uiState.value.week.targetDays)
            assertEquals(1, viewModel.uiState.value.week.weeklyStreakWeeks)
        }
    }

    @Test
    fun targetSettingsGoalButtonsToggleOneInlineEditorAtATime() = runBlocking {
        viewModel(snapshot = snapshot()).use { viewModel ->
            viewModel.reload()
            assertEquals(null, viewModel.uiState.value.settings.expandedEditor)

            viewModel.onEvent(StatisticsEvent.ToggleTargetSettings(StatisticsTargetSettingsFocus.Weekly))
            assertEquals(StatisticsTargetSettingsFocus.Weekly, viewModel.uiState.value.settings.expandedEditor)

            viewModel.onEvent(StatisticsEvent.ToggleTargetSettings(StatisticsTargetSettingsFocus.Daily))
            assertEquals(StatisticsTargetSettingsFocus.Daily, viewModel.uiState.value.settings.expandedEditor)

            viewModel.onEvent(StatisticsEvent.ToggleTargetSettings(StatisticsTargetSettingsFocus.Daily))
            assertEquals(null, viewModel.uiState.value.settings.expandedEditor)
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

            viewModel.onEvent(StatisticsEvent.UpdateWeeklyTargetDays(2))
            yield()
            assertEquals(true, viewModel.uiState.value.isLoading)

            pendingLoad.complete(snapshot(day("2026-06-30", characters = 2_000)))
            yield()

            assertEquals(false, viewModel.uiState.value.isLoading)
        }
    }

    private fun viewModel(
        snapshot: StatisticsSnapshot,
        settings: StatisticsTargetSettings = StatisticsTargetSettings(),
    ): ViewModelHandle =
        viewModel(
            repository = FakeStatisticsRepository(snapshot),
            settings = settings,
        )

    private fun viewModel(
        repository: StatisticsRepository,
        settings: StatisticsTargetSettings = StatisticsTargetSettings(),
    ): ViewModelHandle {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val settingsFlow = MutableStateFlow(settings)
        return ViewModelHandle(
            StatisticsViewModel(
                repository = repository,
                settings = settingsFlow,
                updateSettings = { transform -> settingsFlow.value = transform(settingsFlow.value) },
                clock = FakeStatisticsClock(LocalDate.parse("2026-06-30")),
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
    ) : StatisticsRepository {
        override suspend fun loadSnapshot(): StatisticsSnapshot = snapshot
    }

    private class DeferredStatisticsRepository(
        vararg loads: CompletableDeferred<StatisticsSnapshot>,
    ) : StatisticsRepository {
        private val pendingLoads = ArrayDeque(loads.toList())

        override suspend fun loadSnapshot(): StatisticsSnapshot =
            pendingLoads.removeFirst().await()
    }

    private class FakeStatisticsClock(
        private val today: LocalDate,
    ) : StatisticsClock {
        override fun today(): LocalDate = today
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
