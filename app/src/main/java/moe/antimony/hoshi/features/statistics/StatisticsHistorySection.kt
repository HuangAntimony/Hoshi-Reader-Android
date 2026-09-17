package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import moe.antimony.hoshi.R

@Composable
internal fun StatisticsHistoryGrid(
    history: StatisticsHistoryUi,
    settings: StatisticsTargetSettings,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    val shortDatePattern = stringResource(R.string.statistics_history_short_date_pattern)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HistoryMetricRow(
            HistoryMetricValue(
                title = stringResource(R.string.statistics_current_streak),
                value = formatStatisticsDays(history.currentStreak.count),
                detail = history.currentStreak.range?.let {
                    stringResource(
                        R.string.statistics_history_since_format,
                        formatStatisticsHistoryDate(it.start, today, shortDatePattern),
                    )
                },
            ),
            HistoryMetricValue(
                title = stringResource(R.string.statistics_longest_streak),
                value = formatStatisticsDays(history.longestStreak.count),
                detail = history.longestStreak.range?.let { range ->
                    val start = formatStatisticsHistoryDate(range.start, today, shortDatePattern)
                    if (range.start == range.end) start else stringResource(
                        R.string.statistics_date_interval_format,
                        start,
                        formatStatisticsHistoryDate(
                            range.end,
                            if (range.start.year == range.end.year) range.end else today,
                            shortDatePattern,
                        ),
                    )
                },
            ),
        )
        HistoryMetricRow(
            HistoryMetricValue(
                title = stringResource(R.string.statistics_target_days),
                value = formatStatisticsDays(history.metDays),
                detail = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.statistics_history_reading_days, history.readingDays, history.readingDays,
                ),
            ),
            HistoryMetricValue(
                title = stringResource(R.string.statistics_best_day),
                value = when (settings.dailyTargetType) {
                    DailyTargetType.Characters -> formatStatisticsGroupedCount(history.bestDay?.totalCharacters ?: 0)
                    DailyTargetType.Duration -> formatStatisticsDuration(history.bestDay?.readingSeconds ?: 0.0)
                },
                detail = history.bestDay?.let { formatStatisticsHistoryDate(it.date, today, shortDatePattern) },
            ),
        )
    }
}

private data class HistoryMetricValue(val title: String, val value: String, val detail: String?)

@Composable
private fun HistoryMetricRow(first: HistoryMetricValue, second: HistoryMetricValue) {
    val metrics = listOf(first, second)
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            metrics.forEach { metric ->
                Text(
                    text = metric.title,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            metrics.forEach { metric ->
                Text(
                    text = metric.value,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (metrics.any { it.detail != null }) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                metrics.forEach { metric ->
                    Text(
                        text = metric.detail.orEmpty(),
                        modifier = Modifier.weight(1f).alignByBaseline(),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

internal fun formatStatisticsDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))

internal fun formatStatisticsHistoryDate(
    date: LocalDate,
    today: LocalDate,
    shortPattern: String,
    locale: Locale = Locale.getDefault(),
): String = date.format(
    if (date.year == today.year) DateTimeFormatter.ofPattern(shortPattern, locale)
    else DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale),
)
