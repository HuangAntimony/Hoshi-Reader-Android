package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.bookshelf.MainShellLayoutSpec

@Composable
internal fun StatisticsView(
    layoutSpec: MainShellLayoutSpec,
    onOpenSettings: () -> Unit,
    onOpenBook: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatisticsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()
    val heatmapScrollState = rememberLazyListState()
    var heatmapInitiallyScrolled by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = StatisticsLifecycleReloader(viewModel::reload)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        topBar = { StatisticsHeader(onOpenSettings = onOpenSettings) },
    ) { innerPadding ->
        // These fixed dashboard sections stay composed when scrolled out of view.
        // Recreating whole cards also recreates the heatmap and chart during a fling.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(
                    start = layoutSpec.pageHorizontalPaddingDp.dp,
                    end = layoutSpec.pageHorizontalPaddingDp.dp,
                    top = 16.dp,
                    bottom = statisticsListBottomPaddingDp().dp,
                ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (uiState.emptyState?.hasPartialReadError == true) {
                CenteredStatisticsColumn(layoutSpec = layoutSpec) {
                    Text(
                        text = stringResource(R.string.statistics_partial_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            CenteredStatisticsColumn(layoutSpec = layoutSpec) {
                TodayStatisticsSection(
                    today = uiState.today,
                    settings = uiState.settings.values,
                    history = uiState.history,
                    heatmap = uiState.heatmap,
                    heatmapScrollState = heatmapScrollState,
                    heatmapInitiallyScrolled = heatmapInitiallyScrolled,
                    onHeatmapInitiallyScrolled = { heatmapInitiallyScrolled = true },
                    targetEditor = uiState.settings,
                    onEvent = viewModel::onEvent,
                )
            }
            CenteredStatisticsColumn(layoutSpec = layoutSpec) {
                StatisticsReadingTimeSection(
                    currentRange = uiState.currentRange,
                    today = uiState.today.date,
                    onEvent = viewModel::onEvent,
                )
            }
            if (uiState.currentRange.distributionRows.isNotEmpty()) {
                CenteredStatisticsColumn(layoutSpec = layoutSpec) {
                    StatisticsBooksSection(
                        rows = uiState.currentRange.distributionRows,
                        range = uiState.currentRange.range,
                        selectedBucket = uiState.currentRange.selectedBucket,
                        onOpenBook = onOpenBook,
                    )
                }
            }
        }
    }
}

internal fun statisticsListBottomPaddingDp(): Int = 24

internal class StatisticsLifecycleReloader(
    private val reload: () -> Unit,
) : LifecycleEventObserver {
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_RESUME) {
            reload()
        }
    }
}

@Composable
private fun CenteredStatisticsColumn(
    layoutSpec: MainShellLayoutSpec,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = layoutSpec.contentMaxWidthDp.dp),
            content = content,
        )
    }
}
