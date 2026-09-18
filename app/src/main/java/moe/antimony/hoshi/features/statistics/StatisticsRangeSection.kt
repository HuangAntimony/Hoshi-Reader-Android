package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.material.icons.rounded.ArrowCircleUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
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
                    when (currentRange.mode) {
                        StatisticsRangeMode.Week, StatisticsRangeMode.Month -> R.string.statistics_period_daily_average_format
                        StatisticsRangeMode.Year, StatisticsRangeMode.All -> R.string.statistics_period_monthly_average_format
                    },
                    statisticsCompactRangeTitle(currentRange, today),
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selectedBucket != null) {
                IconButton(onClick = { onSelectBucket(null) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        val change = currentRange.periodChangePercent?.takeIf { selectedBucket == null }
        StatisticsReadingTimeHeadline(
            duration = formatStatisticsDuration(if (showTotal) summary.readingSeconds else summary.averageReadingSecondsPerBucket),
            change = change,
            comparison = change?.let {
                stringResource(
                    when (currentRange.mode) {
                        StatisticsRangeMode.Week -> R.string.statistics_average_change_week_format
                        StatisticsRangeMode.Month -> R.string.statistics_average_change_month_format
                        else -> R.string.statistics_average_change_year_format
                    },
                    formatStatisticsGroupedCount(abs(it).roundToInt()),
                )
            },
        )
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
private fun StatisticsReadingTimeHeadline(duration: String, change: Double?, comparison: String?) {
    val durationStyle = MaterialTheme.typography.headlineLarge.copy(fontSize = 38.sp, fontWeight = FontWeight.Normal)
    val comparisonStyle = MaterialTheme.typography.bodyMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val comparisonIconSize = with(density) { 18.sp.toDp() }
    val comparisonSpacing = 4.dp
    val headlineSpacing = 10.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val durationWidth = measurer.measure(duration, durationStyle, softWrap = false).size.width
        val comparisonWidth = comparison?.let { measurer.measure(it, comparisonStyle, softWrap = false).size.width } ?: 0
        val fitsOneRow = comparison == null || durationWidth + comparisonWidth +
            with(density) { (comparisonIconSize + comparisonSpacing + headlineSpacing).toPx() } <= constraints.maxWidth
        val durationContent: @Composable (Modifier) -> Unit = { modifier ->
            Text(duration, modifier, style = durationStyle, maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = 38.sp, stepSize = 1.sp))
        }
        val comparisonContent: @Composable (Modifier) -> Unit = { modifier ->
            if (comparison != null && change != null) {
                Row(modifier, horizontalArrangement = Arrangement.spacedBy(comparisonSpacing), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (change >= 0) Icons.Rounded.ArrowCircleUp else Icons.Rounded.ArrowCircleDown,
                        contentDescription = null, tint = color, modifier = Modifier.size(comparisonIconSize))
                    Text(comparison, style = comparisonStyle, color = color, modifier = Modifier.alignByBaseline())
                }
            }
        }
        if (fitsOneRow) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(headlineSpacing)) {
                durationContent(Modifier.weight(1f).alignByBaseline())
                comparisonContent(Modifier.alignByBaseline())
            }
        } else {
            // Preserve the complete comparison on narrow screens and with larger system text.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                durationContent(Modifier.fillMaxWidth())
                comparisonContent(Modifier.align(Alignment.End))
            }
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
    StatisticsRangeMode.Month -> {
        val pattern = stringResource(R.string.statistics_range_month_title_pattern)
        val locale = LocalConfiguration.current.locales[0]
        val formatter = remember(pattern, locale) { DateTimeFormatter.ofPattern(pattern, locale) }
        formatter.format(range.start)
    }
    StatisticsRangeMode.Week -> stringResource(R.string.statistics_date_interval_format, formatStatisticsDate(range.start), formatStatisticsDate(range.end))
}

@Composable
internal fun rangeModeLabel(mode: StatisticsRangeMode): String = stringResource(when (mode) {
    StatisticsRangeMode.Week -> R.string.statistics_range_week
    StatisticsRangeMode.Month -> R.string.statistics_range_month
    StatisticsRangeMode.Year -> R.string.statistics_range_year
    StatisticsRangeMode.All -> R.string.statistics_range_all
})
