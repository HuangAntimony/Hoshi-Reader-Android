package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
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
    modifier: Modifier = Modifier,
    viewModel: StatisticsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val heatmapScrollState = rememberScrollState()
    var heatmapAutoScrolledWindowKey by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = StatisticsLifecycleReloader(viewModel::reload)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    StatisticsOverscrollDisabled {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            topBar = { StatisticsHeader() },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = layoutSpec.pageHorizontalPaddingDp.dp,
                    end = layoutSpec.pageHorizontalPaddingDp.dp,
                    top = 16.dp,
                    bottom = statisticsListBottomPaddingDp().dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (uiState.emptyState?.hasPartialReadError == true) {
                    item {
                        CenteredStatisticsColumn(layoutSpec = layoutSpec) {
                            Text(
                                text = stringResource(R.string.statistics_partial_unavailable),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                item {
                    CenteredStatisticsColumn(layoutSpec = layoutSpec) {
                        TodayStatisticsSection(
                            today = uiState.today,
                            settings = uiState.settings.values,
                            layoutSpec = layoutSpec,
                            targetEditorExpanded = uiState.settings.expandedEditor == StatisticsTargetSettingsFocus.Daily,
                            onToggleTargetSettings = {
                                viewModel.onEvent(
                                    StatisticsEvent.ToggleTargetSettings(StatisticsTargetSettingsFocus.Daily),
                                )
                            },
                            onEvent = viewModel::onEvent,
                        )
                    }
                }
                item {
                    CenteredStatisticsColumn(layoutSpec = layoutSpec) {
                        WeekStatisticsSection(
                            week = uiState.week,
                            settings = uiState.settings.values,
                            targetEditorExpanded = uiState.settings.expandedEditor == StatisticsTargetSettingsFocus.Weekly,
                            onToggleTargetSettings = {
                                viewModel.onEvent(
                                    StatisticsEvent.ToggleTargetSettings(StatisticsTargetSettingsFocus.Weekly),
                                )
                            },
                            onEvent = viewModel::onEvent,
                        )
                    }
                }
                item {
                    CenteredStatisticsColumn(layoutSpec = layoutSpec) {
                        StatisticsCalendarSection(
                            calendar = uiState.calendar,
                            heatmapScrollState = heatmapScrollState,
                            heatmapAutoScrolledWindowKey = heatmapAutoScrolledWindowKey,
                            onHeatmapAutoScrolled = { key -> heatmapAutoScrolledWindowKey = key },
                            onEvent = viewModel::onEvent,
                        )
                    }
                }
                item {
                    CenteredStatisticsColumn(layoutSpec = layoutSpec) {
                        StatisticsRangeSection(
                            currentRange = uiState.currentRange,
                            calendar = uiState.calendar,
                            onEvent = viewModel::onEvent,
                        )
                    }
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
