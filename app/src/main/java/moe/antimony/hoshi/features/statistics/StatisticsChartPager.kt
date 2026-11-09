package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/** Both button navigation and swipes commit the same settled page to the dashboard. */
@Composable
internal fun rememberStatisticsChartPagerState(
    range: CurrentRangeStatisticsUi,
    onSelectPage: (Int) -> Unit,
): PagerState = key(range.mode, range.pageCount) {
    val pager = rememberPagerState(initialPage = range.selectedPage, pageCount = { range.pageCount })
    val currentOnSelectPage by rememberUpdatedState(onSelectPage)
    LaunchedEffect(pager, range.selectedPage) {
        // A reset-time change can select the latest page without changing the page count.
        if (pager.settledPage != range.selectedPage) pager.scrollToPage(range.selectedPage)
        snapshotFlow { if (pager.isScrollInProgress) null else pager.settledPage }
            .filterNotNull().distinctUntilChanged().collect { page ->
                if (page != range.selectedPage) currentOnSelectPage(page)
            }
    }
    pager
}

@Composable
internal fun StatisticsChartPager(
    range: CurrentRangeStatisticsUi,
    pager: PagerState,
    today: LocalDate,
    onSelectBucket: (String?) -> Unit,
) {
    HorizontalPager(
        state = pager,
        userScrollEnabled = range.mode != StatisticsRangeMode.All,
        modifier = Modifier.testTag("statistics_chart_pager"),
    ) { index ->
        val page = range.chartPages[index]
        if (page == null) {
            Spacer(Modifier.height(128.dp))
        } else {
            StatisticsTrendChart(
                mode = range.mode,
                points = page.points,
                averageSeconds = page.averageSeconds,
                selectedBucket = range.selectedBucket.takeIf { index == range.selectedPage },
                today = today,
                onSelectBucket = onSelectBucket,
            )
        }
    }
}
