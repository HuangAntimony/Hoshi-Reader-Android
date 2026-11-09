package moe.antimony.hoshi.features.statistics

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.StatisticsArchiveSummary
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Generated statistics only; never opens or changes the installed library or settings. */
@RunWith(AndroidJUnit4::class)
class StatisticsReadingTimeSectionTest {
    @get:Rule val compose = createComposeRule()
    private val job = SupervisorJob()
    private var today = LocalDate.parse("2026-06-30")
    private val resetMinutes = MutableStateFlow(0)
    private lateinit var model: StatisticsViewModel
    private val dark = mutableStateOf(false)
    private val eInk = mutableStateOf(false)
    private val fontScale = mutableStateOf(1f)

    @After fun close() = runBlocking { job.cancelAndJoin() }

    @Test fun buttonsAndSwipesKeepChartSummaryAndSelectionTogether() {
        show()
        next().assertIsNotEnabled()
        previous().assertIsEnabled()
        assertPeriod("2026-06-28", 500)
        previous().performClick()
        assertPeriod("2026-06-21", 100)
        previous().assertIsNotEnabled()
        next().performClick()
        assertPeriod("2026-06-28", 500)
        chart().performTouchInput { swipeRight() }
        assertPeriod("2026-06-21", 100)
        next().performClick()
        assertPeriod("2026-06-28", 500)

        tapFirstBucket()
        compose.runOnIdle { assertEquals(LocalDate.parse("2026-06-28"), model.uiState.value.currentRange.selectedBucket?.start) }
        tapFirstBucket()
        compose.runOnIdle { assertNull(model.uiState.value.currentRange.selectedBucket) }
        tapFirstBucket()
        previous().performClick()
        assertPeriod("2026-06-21", 100)
        compose.runOnIdle { assertNull(model.uiState.value.currentRange.selectedBucket) }

        selectMode(R.string.statistics_range_month)
        assertPeriod("2026-06-01", 600)
        previous().assertIsNotEnabled()
        next().assertIsNotEnabled()
        selectMode(R.string.statistics_range_year)
        assertPeriod("2026-01-01", 600)
        selectMode(R.string.statistics_range_all)
        previous().assertDoesNotExist()
        next().assertDoesNotExist()
        selectMode(R.string.statistics_range_week)
        assertPeriod("2026-06-28", 500)
    }

    @Test fun reselectingCurrentModeKeepsHistoricalPageNavigable() {
        show()
        previous().performClick()
        assertPeriod("2026-06-21", 100)
        selectMode(R.string.statistics_range_week)
        assertPeriod("2026-06-21", 100)
        next().assertIsEnabled().performClick()
        assertPeriod("2026-06-28", 500)
    }

    @Test fun resetTimeJumpsToCurrentPageWithoutChangingPageCount() {
        show()
        previous().performClick()
        assertPeriod("2026-06-21", 100)

        compose.runOnIdle { resetMinutes.value = 120 }

        assertPeriod("2026-06-28", 500)
        next().assertIsNotEnabled()
        previous().assertIsEnabled().performClick()
        assertPeriod("2026-06-21", 100)
    }

    @Test fun modeSwitchDuringNavigationCancelsOldAnimation() {
        show()
        compose.mainClock.autoAdvance = false
        previous().performClick()
        compose.mainClock.advanceTimeBy(32)
        previous().assertIsNotEnabled()
        next().assertIsNotEnabled()
        selectMode(R.string.statistics_range_month)
        compose.mainClock.autoAdvance = true
        assertPeriod("2026-06-01", 600)
        previous().assertIsNotEnabled()
        next().assertIsNotEnabled()
        selectMode(R.string.statistics_range_week)
        assertPeriod("2026-06-28", 500)
        previous().assertIsEnabled()
    }

    @Test fun differentMonthTitleLengthsDoNotMoveChartOrNavigation() {
        today = LocalDate.parse("2026-10-25")
        show(width = 376.dp, history = listOf(
            day("2026-07-01", 50, 1860.0), day("2026-08-01", 100, 1860.0), day("2026-09-01", 200, 1800.0), day("2026-10-01", 300, 1500.0),
        ))
        selectMode(R.string.statistics_range_month)
        for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { fontScale.value = scale }
            val chartTop = chart().fetchSemanticsNode().boundsInRoot.top
            val arrowTop = previous().fetchSemanticsNode().boundsInRoot.top
            for ((date, characters) in listOf("2026-09-01" to 200, "2026-08-01" to 100)) {
                previous().performClick()
                assertPeriod(date, characters)
                assertEquals("Chart must not move when the month title changes", chartTop,
                    chart().fetchSemanticsNode().boundsInRoot.top, 0.5f)
                assertEquals("Arrow must stay under the finger", arrowTop,
                    previous().fetchSemanticsNode().boundsInRoot.top, 0.5f)
            }
            next().performClick()
            next().performClick()
            assertPeriod("2026-10-01", 300)
        }
    }

    @Test fun longerDurationKeepsComparisonOnSameRowWithoutMovingChart() {
        today = LocalDate.parse("2026-10-10")
        show(width = 376.dp, history = listOf(
            day("2026-09-20", 100, 12040.0), day("2026-09-27", 200, 36960.0), day("2026-10-04", 300, 3780.0),
        ))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val comparisonText = context.getString(R.string.statistics_average_change_week_format, formatStatisticsGroupedCount(207))
        for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { fontScale.value = scale }
            val chartTop = chart().fetchSemanticsNode().boundsInRoot.top
            previous().performClick()
            assertPeriod("2026-09-27", 200)
            val duration = compose.onNodeWithText(context.getString(R.string.statistics_duration_hours_minutes_format, 1, 28)).fetchSemanticsNode().boundsInRoot
            val comparison = compose.onNodeWithText(comparisonText).fetchSemanticsNode().boundsInRoot
            assertTrue("Comparison must remain beside the duration", comparison.top < duration.bottom && comparison.bottom > duration.top)
            assertTrue("Duration and comparison must not overlap", duration.right <= comparison.left)
            assertEquals("Longer duration must not push the chart down", chartTop,
                chart().fetchSemanticsNode().boundsInRoot.top, 0.5f)
            next().performClick()
            assertPeriod("2026-10-04", 300)
        }
    }

    @Test fun emptyHistoryHasNoNavigablePeriods() {
        show(empty = true)
        previous().assertIsNotEnabled()
        next().assertIsNotEnabled()
        selectMode(R.string.statistics_range_all)
        previous().assertDoesNotExist()
        next().assertDoesNotExist()
    }

    @Test fun narrowLayoutsKeepNavigationVisibleAcrossThemesAndLargeText() {
        show()
        for ((name, settings) in listOf(
            "light" to Triple(false, false, 1f),
            "dark" to Triple(true, false, 1f),
            "large" to Triple(false, false, 2f),
            "eink" to Triple(false, true, 1f),
            "eink-dark-large" to Triple(true, true, 2f),
        )) {
            compose.runOnIdle { dark.value = settings.first; eInk.value = settings.second; fontScale.value = settings.third }
            previous().assertIsDisplayed()
            next().assertIsDisplayed()
            val left = previous().fetchSemanticsNode().boundsInRoot
            val right = next().fetchSemanticsNode().boundsInRoot
            assertTrue(left.right <= right.left)
            assertTrue(right.bottom <= chart().fetchSemanticsNode().boundsInRoot.top)
            saveScreenshot(name)
            previous().performClick()
            assertPeriod("2026-06-21", 100)
            next().performClick()
            assertPeriod("2026-06-28", 500)
        }
    }

    private fun show(empty: Boolean = false, width: Dp = 320.dp, history: List<StatisticsDayAggregate>? = null) {
        val days = history ?: if (empty) emptyList() else listOf(
            day("2026-06-21", 100, 600.0), day("2026-06-29", 200, 1200.0), day("2026-06-30", 300, 1800.0),
        )
        val repository = object : StatisticsRepository {
            override suspend fun loadSnapshot() = StatisticsSnapshot(days, if (empty) emptyList() else listOf(2026))
            override suspend fun loadBookStatistics(folder: String): StatisticsBookRecords? = error("Unused")
            override suspend fun editSession(folder: String, id: String, characters: Int?, readingTime: Double?): Unit = error("Unused")
            override suspend fun deleteSessions(folder: String, ids: Collection<String>): Unit = error("Unused")
            override suspend fun loadArchiveSummary(): StatisticsArchiveSummary = error("Unused")
            override suspend fun clearArchive(): Unit = error("Unused")
        }
        compose.runOnUiThread {
            model = StatisticsViewModel(repository, MutableStateFlow(StatisticsTargetSettings()), {}, resetMinutes,
                object : StatisticsDateProvider { override fun currentDate(resetMinutes: Int) = today },
                Dispatchers.Main.immediate, CoroutineScope(job + Dispatchers.Main.immediate))
            model.reload()
        }
        compose.setContent {
            val state by model.uiState.collectAsState()
            HoshiReaderTheme(darkTheme = dark.value, eInkMode = eInk.value, dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
                    Box(Modifier.width(width).background(MaterialTheme.colorScheme.background).padding(12.dp)) {
                        StatisticsReadingTimeSection(state.currentRange, today, model::onEvent)
                    }
                }
            }
        }
    }

    private fun day(date: String, characters: Int, seconds: Double) = StatisticsDayAggregate(
        LocalDate.parse(date), characters, seconds, 1,
        listOf(StatisticsBookContribution("fixture", title = "Fixture", coverPath = null, characters = characters, readingSeconds = seconds)),
    )

    private fun previous() = compose.onNodeWithTag("statistics_previous_period")
    private fun next() = compose.onNodeWithTag("statistics_next_period")
    private fun chart() = compose.onNodeWithTag("statistics_chart_pager")
    private fun selectMode(resource: Int) {
        val text = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)
        compose.onNodeWithText(text).performClick()
    }
    private fun tapFirstBucket() = chart().performTouchInput { click(Offset(width * 0.05f, height * 0.5f)) }
    private fun assertPeriod(start: String, characters: Int) {
        compose.waitForIdle()
        compose.runOnIdle {
            val range = model.uiState.value.currentRange
            assertEquals(LocalDate.parse(start), range.range.start)
            assertEquals(characters, range.summary.totalCharacters)
        }
        compose.onNodeWithText(formatStatisticsGroupedCount(characters)).assertIsDisplayed()
    }
    private fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, "statistics-chart-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
