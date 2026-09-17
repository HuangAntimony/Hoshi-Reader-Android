package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

@Composable
internal fun StatisticsChartPager(
    range: CurrentRangeStatisticsUi,
    today: LocalDate,
    onSelectBucket: (String?) -> Unit,
    onSelectPage: (Int) -> Unit,
) {
    key(range.mode, range.pageCount) {
        val pager = rememberPagerState(initialPage = range.selectedPage, pageCount = { range.pageCount })
        val currentRange by rememberUpdatedState(range)
        val currentOnSelectPage by rememberUpdatedState(onSelectPage)
        LaunchedEffect(pager) {
            snapshotFlow { if (pager.isScrollInProgress) null else pager.settledPage }
                .filterNotNull().distinctUntilChanged().collect { page ->
                    if (page != currentRange.selectedPage) currentOnSelectPage(page)
                }
        }
        HorizontalPager(state = pager, userScrollEnabled = range.mode != StatisticsRangeMode.All) { index ->
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
}
