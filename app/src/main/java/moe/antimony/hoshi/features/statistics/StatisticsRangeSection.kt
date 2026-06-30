package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R

@Composable
internal fun StatisticsRangeSection(
    currentRange: CurrentRangeStatisticsUi,
    calendar: StatisticsCalendarUi,
    onEvent: (StatisticsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    StatisticsSection(
        title = stringResource(R.string.statistics_current_range),
        trailing = rangeTitle(calendar),
        modifier = modifier,
    ) {
        RangeTabRow(
            selected = currentRange.selectedTab,
            mode = currentRange.mode,
            onSelect = { tab -> onEvent(StatisticsEvent.SelectCurrentRangeTab(tab)) },
        )
        Spacer(Modifier.height(16.dp))
        when (currentRange.selectedTab) {
            CurrentRangeTab.Overview -> CurrentRangeOverview(range = currentRange)
            CurrentRangeTab.Trend -> StatisticsTrendChart(
                mode = currentRange.mode,
                points = currentRange.trendPoints,
                modifier = Modifier.fillMaxWidth(),
            )
            CurrentRangeTab.Distribution -> StatisticsDistributionList(
                rows = currentRange.distributionRows,
            )
        }
    }
}

@Composable
private fun RangeTabRow(
    selected: CurrentRangeTab,
    mode: StatisticsRangeMode,
    onSelect: (CurrentRangeTab) -> Unit,
) {
    val tabs = CurrentRangeTab.entries
    StatisticsSegmentedControl(
        options = tabs.map { tab ->
            StatisticsSegmentedOption(
                value = tab,
                label = currentRangeTabLabel(tab),
                enabled = currentRangeTabEnabled(tab, mode),
            )
        },
        selected = selected,
        onSelect = onSelect,
    )
}

@Composable
private fun CurrentRangeOverview(
    range: CurrentRangeStatisticsUi,
) {
    val summary = range.summary
    val fourthMetric = if (range.mode == StatisticsRangeMode.Day) {
        StatisticMetric(
            label = stringResource(R.string.statistics_target_progress),
            value = stringResource(R.string.statistics_percent_format, summary.targetProgressPercent),
        )
    } else {
        StatisticMetric(
            label = stringResource(R.string.statistics_target_days),
            value = formatStatisticsDays(summary.targetDays),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricGrid(
            metrics = listOf(
                StatisticMetric(
                    label = stringResource(R.string.statistics_reading_duration),
                    value = formatStatisticsDuration(summary.readingSeconds),
                ),
                StatisticMetric(
                    label = stringResource(R.string.statistics_characters_read),
                    value = formatStatisticsCharacterCount(summary.totalCharacters),
                ),
                StatisticMetric(
                    label = stringResource(R.string.statistics_average_speed),
                    value = formatStatisticsSpeed(summary.averageSpeedPerHour),
                ),
                fourthMetric,
            ),
            columns = 2,
        )
    }
}

internal fun currentRangeTabEnabled(tab: CurrentRangeTab, mode: StatisticsRangeMode): Boolean =
    tab != CurrentRangeTab.Trend || mode != StatisticsRangeMode.Day

@Composable
private fun currentRangeTabLabel(tab: CurrentRangeTab): String =
    when (tab) {
        CurrentRangeTab.Overview -> stringResource(R.string.statistics_tab_overview)
        CurrentRangeTab.Trend -> stringResource(R.string.statistics_tab_trend)
        CurrentRangeTab.Distribution -> stringResource(R.string.statistics_tab_distribution)
    }
