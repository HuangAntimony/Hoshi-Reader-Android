package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R

@Composable
internal fun StatisticsReadingTimeSection(
    currentRange: CurrentRangeStatisticsUi,
    today: java.time.LocalDate,
    onEvent: (StatisticsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onSelectBucket: (String?) -> Unit = { onEvent(StatisticsEvent.SelectTrendBucket(it)) }
    val summary = currentRange.summary
    val selectedBucket = currentRange.selectedBucket
    val showTotal = selectedBucket != null
    StatisticsSection(
        title = stringResource(R.string.statistics_reading_time),
        modifier = modifier,
    ) {
        StatisticsSegmentedControl(
            options = StatisticsRangeMode.entries.map { StatisticsSegmentedOption(it, rangeModeLabel(it)) },
            selected = currentRange.mode,
            onSelect = { onEvent(StatisticsEvent.SelectRangeMode(it)) },
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedBucket?.let { statisticsBucketTitle(currentRange.mode, it) } ?: stringResource(
                    R.string.statistics_period_average_format,
                    statisticsCompactRangeTitle(currentRange, today),
                    stringResource(
                        if (currentRange.mode == StatisticsRangeMode.Year || currentRange.mode == StatisticsRangeMode.All) {
                            R.string.statistics_monthly_average_duration
                        } else {
                            R.string.statistics_daily_average_duration
                        },
                    ),
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selectedBucket != null) {
                IconButton(onClick = { onSelectBucket(null) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatStatisticsDuration(if (showTotal) summary.readingSeconds else summary.averageReadingSecondsPerBucket),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineLarge,
                autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = 34.sp, stepSize = 1.sp),
                maxLines = 1,
            )
            currentRange.periodChangePercent?.takeIf { selectedBucket == null }?.let { change ->
                Text(
                    text = stringResource(
                        when (currentRange.mode) {
                            StatisticsRangeMode.Week -> R.string.statistics_average_change_week_format
                            StatisticsRangeMode.Month -> R.string.statistics_average_change_month_format
                            else -> R.string.statistics_average_change_year_format
                        },
                        java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
                            .format(change).let { if (change > 0) "+$it" else it },
                    ),
                    modifier = Modifier.widthIn(max = 100.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        StatisticsChartPager(
            range = currentRange,
            today = today,
            onSelectBucket = onSelectBucket,
            onSelectPage = { onEvent(StatisticsEvent.SelectPeriodPage(it)) },
        )
        Spacer(Modifier.height(12.dp))
        StatisticsSummaryRow(stringResource(R.string.statistics_characters_read), formatStatisticsGroupedCount(summary.totalCharacters))
        StatisticsSummaryRow(stringResource(R.string.statistics_average_speed), stringResource(R.string.statistics_speed_value_format, formatStatisticsGroupedCount(summary.averageSpeedPerHour)))
        if (!showTotal) {
            StatisticsSummaryRow(stringResource(R.string.statistics_total_time), formatStatisticsDuration(summary.readingSeconds))
        }
    }
}

@Composable
private fun StatisticsSummaryRow(label: String, value: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun statisticsBucketTitle(mode: StatisticsRangeMode, bucket: StatisticsDateRange): String =
    if (mode == StatisticsRangeMode.Year || mode == StatisticsRangeMode.All) {
        rangeTitle(StatisticsRangeMode.Month, bucket)
    } else {
        formatStatisticsDate(bucket.start)
    }

@Composable
private fun statisticsCompactRangeTitle(current: CurrentRangeStatisticsUi, today: java.time.LocalDate): String {
    if (current.mode != StatisticsRangeMode.Week) return rangeTitle(current.mode, current.range)
    val pattern = stringResource(R.string.statistics_history_short_date_pattern)
    return stringResource(
        R.string.statistics_date_interval_format,
        formatStatisticsHistoryDate(current.range.start, today, pattern),
        formatStatisticsHistoryDate(current.range.end, today, pattern),
    )
}

@Composable
internal fun rangeTitle(mode: StatisticsRangeMode, range: StatisticsDateRange): String = when (mode) {
    StatisticsRangeMode.Year -> stringResource(R.string.statistics_range_fixed_year_format, range.start.year)
    StatisticsRangeMode.All -> stringResource(R.string.statistics_range_all_time)
    StatisticsRangeMode.Month -> stringResource(R.string.statistics_range_month_title_format, range.start.year, range.start.monthValue)
    StatisticsRangeMode.Week -> stringResource(R.string.statistics_date_interval_format, formatStatisticsDate(range.start), formatStatisticsDate(range.end))
}

@Composable
internal fun rangeModeLabel(mode: StatisticsRangeMode): String = stringResource(when (mode) {
    StatisticsRangeMode.Week -> R.string.statistics_range_week
    StatisticsRangeMode.Month -> R.string.statistics_range_month
    StatisticsRangeMode.Year -> R.string.statistics_range_year
    StatisticsRangeMode.All -> R.string.statistics_range_all
})
