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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StatisticsViewModelTest {
    @Test
    fun initialStateShowsCurrentWeekWhileHeatmapRetainsAllHistory() = runBlocking {
        viewModel(snapshot = snapshot(day("2025-12-20", 1_000), day("2026-06-29", 2_000))).use { vm ->
            assertEquals(StatisticsRangeMode.Week, vm.uiState.value.currentRange.mode)
            vm.reload()
            val state = vm.uiState.value
            val weekStart = statisticsStartOfWeek(LocalDate.parse("2026-06-30"))
            assertEquals(StatisticsRangeMode.Week, state.currentRange.mode)
            assertEquals(StatisticsDateRange(weekStart, weekStart.plusDays(6)), state.currentRange.range)
            assertEquals(state.currentRange.pageCount - 1, state.currentRange.selectedPage)
            assertEquals(StatisticsDateRange(statisticsStartOfWeek(LocalDate.parse("2025-12-20")), LocalDate.parse("2026-06-30")), state.heatmap.windowRange)
            assertEquals(2, state.heatmap.days.size)
            assertEquals(LocalDate.parse("2026-06-30"), state.today.date)
            assertEquals(7, state.currentRange.trendPoints.size)
            assertEquals(2_000, state.currentRange.summary.totalCharacters)
            assertEquals(null, state.currentRange.selectedBucket)
        }
    }

    @Test
    fun emptyAllShowsTodayWithoutInventingActivity() = runBlocking {
        viewModel(snapshot = snapshot()).use { vm ->
            vm.reload()
            vm.onEvent(StatisticsEvent.SelectRangeMode(StatisticsRangeMode.All))
            val state = vm.uiState.value
            assertEquals(StatisticsDateRange(statisticsStartOfWeek(LocalDate.parse("2026-06-30")), LocalDate.parse("2026-06-30")), state.heatmap.windowRange)
            assertEquals(emptyList<StatisticsHeatmapDayUi>(), state.heatmap.days)
            assertEquals(1, state.currentRange.trendPoints.size)
            assertEquals(0, state.currentRange.summary.totalCharacters)
        }
    }

    @Test
    fun monthAndWeekChartSelectionFiltersSummaryAndBooksWithoutChangingOuterRange() = runBlocking {
        val data = snapshot(
            day("2026-06-29", contributions = listOf(contribution("a", "A", 1_000, 600.0))),
            day("2026-06-30", contributions = listOf(contribution("b", "B", 2_000, 900.0))),
        )
        viewModel(snapshot = data).use { vm ->
            vm.reload()
            listOf(StatisticsRangeMode.Month, StatisticsRangeMode.Week).forEach { mode ->
                vm.onEvent(StatisticsEvent.SelectRangeMode(mode))
                val outer = vm.uiState.value
                vm.onEvent(StatisticsEvent.SelectTrendBucket("2026-06-29"))
                val detail = vm.uiState.value
                assertEquals(outer.heatmap, detail.heatmap)
                assertEquals(outer.currentRange.trendPoints, detail.currentRange.trendPoints)
                assertEquals(outer.currentRange.trendAverageSeconds, detail.currentRange.trendAverageSeconds, 0.0)
                assertEquals(range("2026-06-29", "2026-06-29"), detail.currentRange.selectedBucket)
                assertEquals(1_000, detail.currentRange.summary.totalCharacters)
                assertEquals(600.0, detail.currentRange.summary.readingSeconds, 0.0)
                assertEquals(listOf("A"), detail.currentRange.distributionRows.map { it.title })
                assertEquals(null, detail.currentRange.summary.averageReadingTimeChangePercent)
                vm.onEvent(StatisticsEvent.SelectTrendBucket("2026-06-29"))
                assertEquals(null, vm.uiState.value.currentRange.selectedBucket)
                assertEquals(3_000, vm.uiState.value.currentRange.summary.totalCharacters)
                assertEquals(listOf("B", "A"), vm.uiState.value.currentRange.distributionRows.map { it.title })
            }
        }
    }

    @Test
    fun yearAndAllAllowMonthlyDrilldownIncludingPartialFirstMonth() = runBlocking {
        viewModel(snapshot = snapshot(day("2026-02-15", 1_000), day("2026-06-30", 2_000))).use { vm ->
            vm.reload()
            listOf(StatisticsRangeMode.All, StatisticsRangeMode.Year).forEach { mode ->
                vm.onEvent(StatisticsEvent.SelectRangeMode(mode))
                val outer = vm.uiState.value.heatmap
                vm.onEvent(StatisticsEvent.SelectTrendBucket("2026-02"))
                assertEquals(range("2026-02-01", "2026-02-28"), vm.uiState.value.currentRange.selectedBucket)
                assertEquals(1_000, vm.uiState.value.currentRange.summary.totalCharacters)
                assertEquals(outer, vm.uiState.value.heatmap)
                vm.onEvent(StatisticsEvent.SelectTrendBucket(null))
                assertEquals(3_000, vm.uiState.value.currentRange.summary.totalCharacters)
            }
        }
    }

    @Test
    fun chartSupportsEmptyBucketsAndIgnoresFutureOrInvalidBuckets() = runBlocking {
        viewModel(snapshot = snapshot(day("2026-02-15", 1_000))).use { vm ->
            vm.reload()
            vm.onEvent(StatisticsEvent.SelectRangeMode(StatisticsRangeMode.Year))
            vm.onEvent(StatisticsEvent.SelectTrendBucket("2026-03"))
            assertEquals(range("2026-03-01", "2026-03-31"), vm.uiState.value.currentRange.selectedBucket)
            assertEquals(0, vm.uiState.value.currentRange.summary.totalCharacters)
            val current = vm.uiState.value.currentRange
            listOf("2026-07", "2025-01", "invalid").forEach { key ->
                vm.onEvent(StatisticsEvent.SelectTrendBucket(key))
                assertEquals(current, vm.uiState.value.currentRange)
            }
        }
    }

    @Test
    fun goalChangesDoNotChangeTimeBasedBookOrder() = runBlocking {
        viewModel(snapshot = snapshot(day("2026-06-30", contributions = listOf(
            contribution("fast", "Fast", 4_000, 600.0), contribution("slow", "Slow", 1_000, 1_800.0),
        )))).use { vm ->
            vm.reload()
            assertEquals(100, vm.uiState.value.today.targetPercent)
            val rows = vm.uiState.value.currentRange.distributionRows
            assertEquals(listOf("Slow", "Fast"), rows.map { it.title })
            vm.onEvent(StatisticsEvent.SelectDailyTargetType(DailyTargetType.Duration))
            assertEquals(133, vm.uiState.value.today.targetPercent)
            assertEquals(rows, vm.uiState.value.currentRange.distributionRows)
        }
    }

    @Test
    fun longHistoryKeepsSparseHeatmapAndMonthlyChart() = runBlocking {
        viewModel(snapshot = snapshot(day("2001-01-01", 6_000), day("2026-06-30", 5_000))).use { vm ->
            vm.reload()
            vm.onEvent(StatisticsEvent.SelectRangeMode(StatisticsRangeMode.All))
            val state = vm.uiState.value
            assertEquals(2, state.heatmap.days.size)
            assertEquals(StatisticsDateRange(statisticsStartOfWeek(LocalDate.parse("2001-01-01")), LocalDate.parse("2026-06-30")), state.heatmap.windowRange)
            assertEquals(306, state.currentRange.trendPoints.size)
            assertEquals(11_000, state.currentRange.summary.totalCharacters)
        }
    }

    @Test
    fun chartPeriodPagingIsBoundedAndDoesNotChangeHeatmap() = runBlocking {
        viewModel(snapshot = snapshot(day("2025-12-20", 1_000), day("2026-06-29", 2_000))).use { vm ->
            vm.reload()
            val heatmap = vm.uiState.value.heatmap
            vm.onEvent(StatisticsEvent.SelectRangeMode(StatisticsRangeMode.Month))
            assertEquals(7, vm.uiState.value.currentRange.pageCount)
            assertEquals(6, vm.uiState.value.currentRange.selectedPage)
            assertEquals(2_000, vm.uiState.value.currentRange.summary.totalCharacters)
            vm.onEvent(StatisticsEvent.SelectPeriodPage(0))
            assertEquals(range("2025-12-01", "2025-12-31"), vm.uiState.value.currentRange.range)
            assertEquals(1_000, vm.uiState.value.currentRange.summary.totalCharacters)
            assertEquals(heatmap, vm.uiState.value.heatmap)
            listOf(-1, 7).forEach { index ->
                vm.onEvent(StatisticsEvent.SelectPeriodPage(index))
                assertEquals(0, vm.uiState.value.currentRange.selectedPage)
            }
        }
    }

    @Test
    fun periodChangesResetToCurrentPeriodAndClearBucketWhileReloadPreservesIt() = runBlocking {
        viewModel(snapshot = snapshot(day("2026-02-15", 1_000), day("2026-06-29", 2_000))).use { vm ->
            vm.reload()
            vm.onEvent(StatisticsEvent.SelectRangeMode(StatisticsRangeMode.Month))
            vm.onEvent(StatisticsEvent.SelectTrendBucket("2026-06-29"))
            vm.reload()
            assertEquals(range("2026-06-29", "2026-06-29"), vm.uiState.value.currentRange.selectedBucket)
            vm.onEvent(StatisticsEvent.SelectPeriodPage(0))
            assertEquals(null, vm.uiState.value.currentRange.selectedBucket)
            vm.onEvent(StatisticsEvent.SelectRangeMode(StatisticsRangeMode.Year))
            assertEquals(range("2026-01-01", "2026-12-31"), vm.uiState.value.currentRange.range)
            assertEquals(null, vm.uiState.value.currentRange.selectedBucket)
            vm.onEvent(StatisticsEvent.SelectRangeMode(StatisticsRangeMode.All))
            assertEquals(1, vm.uiState.value.currentRange.pageCount)
            assertEquals(0, vm.uiState.value.currentRange.selectedPage)
        }
    }

    @Test
    fun heatmapIntensityUsesAllHistoryIndependentOfPeriod() = runBlocking {
        viewModel(snapshot = snapshot(day("2025-12-20", 1_000), day("2026-06-29", 6_000))).use { vm ->
            vm.reload()
            val heatmap = vm.uiState.value.heatmap
            assertEquals(listOf(1, 7), heatmap.days.map { it.heatLevel })
            StatisticsRangeMode.entries.forEach { mode ->
                vm.onEvent(StatisticsEvent.SelectRangeMode(mode))
                assertEquals(heatmap, vm.uiState.value.heatmap)
            }
        }
    }

    @Test
    fun deletingEarliestHistoryClampsPagerAndSummaryToRemainingPeriod() = runBlocking {
        val first = CompletableDeferred(snapshot(day("2026-02-15", 1_000), day("2026-06-29", 2_000)))
        val second = CompletableDeferred(snapshot(day("2026-06-29", 2_000)))
        viewModel(repository = DeferredStatisticsRepository(first, second)).use { vm ->
            vm.reload()
            vm.onEvent(StatisticsEvent.SelectRangeMode(StatisticsRangeMode.Month))
            vm.onEvent(StatisticsEvent.SelectPeriodPage(0))
            assertEquals(2, vm.uiState.value.currentRange.range.start.monthValue)
            vm.reload()
            assertEquals(1, vm.uiState.value.currentRange.pageCount)
            assertEquals(0, vm.uiState.value.currentRange.selectedPage)
            assertEquals(range("2026-06-01", "2026-06-30"), vm.uiState.value.currentRange.range)
            assertEquals(2_000, vm.uiState.value.currentRange.summary.totalCharacters)
        }
    }

    private fun range(start: String, end: String) = StatisticsDateRange(LocalDate.parse(start), LocalDate.parse(end))

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

            assertEquals(LocalDate.parse("2026-06-29"), viewModel.uiState.value.today.date)
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
    fun goalPickerOpenAndDismissPreserveDashboardSelection() = runBlocking {
        viewModel(snapshot = snapshot()).use { viewModel ->
            viewModel.reload()
            assertEquals(false, viewModel.uiState.value.settings.isEditorVisible)

            viewModel.onEvent(StatisticsEvent.OpenTargetSettings)
            assertEquals(true, viewModel.uiState.value.settings.isEditorVisible)

            viewModel.onEvent(StatisticsEvent.DismissTargetSettings)
            assertEquals(false, viewModel.uiState.value.settings.isEditorVisible)
        }
    }

    @Test
    fun changingGoalMetricKeepsIndependentValuesAndHeatmap() = runBlocking {
        viewModel(snapshot = snapshot(day("2026-06-30", characters = 5_000))).use { vm ->
            vm.reload()
            val heatmap = vm.uiState.value.heatmap
            vm.onEvent(StatisticsEvent.OpenTargetSettings)
            vm.onEvent(StatisticsEvent.UpdateDailyCharacterTarget(12_500))
            vm.onEvent(StatisticsEvent.SelectDailyTargetType(DailyTargetType.Duration))
            vm.onEvent(StatisticsEvent.UpdateDailyDurationTargetMinutes(45))
            vm.onEvent(StatisticsEvent.SelectDailyTargetType(DailyTargetType.Characters))
            vm.onEvent(StatisticsEvent.DismissTargetSettings)
            assertEquals(12_500, vm.uiState.value.settings.values.dailyCharacterTarget)
            assertEquals(45, vm.uiState.value.settings.values.dailyDurationTargetMinutes)
            assertEquals(heatmap, vm.uiState.value.heatmap)
        }
    }

    @Test
    fun failedGoalUpdateKeepsPickerOpenAndCanRetry() = runBlocking {
        var fail = true
        viewModel(repository = FakeStatisticsRepository(snapshot()), beforeUpdate = {
            if (fail) throw java.io.IOException("write failed")
        }).use { vm ->
            vm.onEvent(StatisticsEvent.OpenTargetSettings)
            vm.onEvent(StatisticsEvent.UpdateDailyCharacterTarget(12_500))
            assertEquals(5_000, vm.uiState.value.settings.values.dailyCharacterTarget)
            assertEquals(true, vm.uiState.value.settings.isEditorVisible)
            assertNotNull(vm.uiState.value.settings.error)
            fail = false
            vm.onEvent(StatisticsEvent.UpdateDailyCharacterTarget(12_500))
            assertEquals(12_500, vm.uiState.value.settings.values.dailyCharacterTarget)
            assertNull(vm.uiState.value.settings.error)
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

            assertEquals(LocalDate.parse("2026-06-30"), viewModel.uiState.value.today.date)
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
        beforeUpdate: () -> Unit = {},
    ): ViewModelHandle {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val settingsFlow = MutableStateFlow(settings)
        val resetMinutesFlow = MutableStateFlow(resetMinutes)
        return ViewModelHandle(
            StatisticsViewModel(
                repository = repository,
                settings = settingsFlow,
                updateSettings = { transform -> beforeUpdate(); settingsFlow.value = transform(settingsFlow.value) },
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
            totalCharacters = contributions.sumOf { it.characters },
            readingSeconds = contributions.sumOf { it.readingSeconds },
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
