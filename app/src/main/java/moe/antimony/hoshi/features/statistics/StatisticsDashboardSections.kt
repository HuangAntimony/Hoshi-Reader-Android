package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.bookshelf.MainShellLayoutSpec
import moe.antimony.hoshi.features.bookshelf.MainShellNavigationLayout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatisticsHeader(
    modifier: Modifier = Modifier,
) {
    CenterAlignedTopAppBar(
        modifier = modifier.fillMaxWidth(),
        title = {
            Text(
                text = stringResource(R.string.statistics_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
internal fun TodayStatisticsSection(
    today: TodayStatisticsUi,
    settings: StatisticsTargetSettings,
    layoutSpec: MainShellLayoutSpec,
    targetEditorExpanded: Boolean,
    onToggleTargetSettings: () -> Unit,
    onEvent: (StatisticsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    StatisticsSection(
        title = stringResource(R.string.statistics_today),
        trailing = null,
        modifier = modifier,
        trailingContent = {
            TargetGoalText(
                text = stringResource(R.string.statistics_target_format, dailyTargetText(settings)),
                onClick = onToggleTargetSettings,
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GoalRing(
                percent = today.targetPercent,
                modifier = Modifier.size(if (layoutSpec.navigationLayout == MainShellNavigationLayout.BottomBar) 96.dp else 116.dp),
            )
            MetricGrid(
                metrics = listOf(
                    StatisticMetric(
                        label = stringResource(R.string.statistics_reading_duration),
                        value = formatStatisticsDuration(today.readingSeconds),
                    ),
                    StatisticMetric(
                        label = stringResource(R.string.statistics_characters_read),
                        value = formatStatisticsCharacterCount(today.totalCharacters),
                    ),
                    StatisticMetric(
                        label = stringResource(R.string.statistics_average_speed),
                        value = formatStatisticsSpeed(today.averageSpeedPerHour),
                    ),
                    StatisticMetric(
                        label = stringResource(R.string.statistics_streak),
                        value = formatStatisticsDays(today.dailyStreakDays),
                    ),
                ),
                columns = 2,
                modifier = Modifier.weight(1f),
            )
        }
        if (targetEditorExpanded) {
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(14.dp))
            DailyTargetSettingsSection(settings = settings, onEvent = onEvent)
        }
    }
}

@Composable
internal fun WeekStatisticsSection(
    week: WeekStatisticsUi,
    settings: StatisticsTargetSettings,
    targetEditorExpanded: Boolean,
    onToggleTargetSettings: () -> Unit,
    onEvent: (StatisticsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val averageMetric = if (settings.dailyTargetType == DailyTargetType.Duration) {
        StatisticMetric(
            label = stringResource(R.string.statistics_daily_average_duration),
            value = formatStatisticsDuration(week.averageReadingSecondsPerElapsedDay),
        )
    } else {
        StatisticMetric(
            label = stringResource(R.string.statistics_daily_average_characters),
            value = formatStatisticsCharacterCount(week.averageCharactersPerElapsedDay),
        )
    }
    StatisticsSection(
        title = stringResource(R.string.statistics_this_week),
        trailing = null,
        modifier = modifier,
        trailingContent = {
            TargetGoalText(
                text = stringResource(R.string.statistics_target_format, formatStatisticsDays(week.targetDays)),
                onClick = onToggleTargetSettings,
            )
        },
    ) {
        MetricGrid(
            metrics = weekStatisticsMetricsInDisplayOrder(
                durationMetric = StatisticMetric(
                    label = stringResource(R.string.statistics_reading_duration),
                    value = formatStatisticsDuration(week.readingSeconds),
                ),
                charactersMetric = StatisticMetric(
                    label = stringResource(R.string.statistics_characters_read),
                    value = formatStatisticsCharacterCount(week.totalCharacters),
                ),
                speedMetric = StatisticMetric(
                    label = stringResource(R.string.statistics_average_speed),
                    value = formatStatisticsSpeed(week.averageSpeedPerHour),
                ),
                targetDaysMetric = StatisticMetric(
                    label = stringResource(R.string.statistics_target_days),
                    value = formatStatisticsDays(week.metTargetDays),
                ),
                streakMetric = StatisticMetric(
                    label = stringResource(R.string.statistics_streak),
                    value = formatStatisticsWeeks(week.weeklyStreakWeeks),
                ),
                averageMetric = averageMetric,
            ),
            columns = 3,
        )
        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.statistics_week_goal_days),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.statistics_week_status_format, week.metTargetDays),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        WeekGoalRow(days = week.days)
        if (targetEditorExpanded) {
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(14.dp))
            WeeklyTargetSettingsSection(settings = settings, onEvent = onEvent)
        }
    }
}

@Composable
private fun DailyTargetSettingsSection(
    settings: StatisticsTargetSettings,
    onEvent: (StatisticsEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.statistics_daily_target),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        TargetTypeSegmentedButtons(
            selected = settings.dailyTargetType,
            onSelect = { type -> onEvent(StatisticsEvent.SelectDailyTargetType(type)) },
        )
        if (settings.dailyTargetType == DailyTargetType.Characters) {
            StepperRow(
                label = stringResource(R.string.statistics_current_target),
                value = stringResource(
                    R.string.statistics_character_target_format,
                    formatInteger(settings.dailyCharacterTarget),
                ),
                canDecrease = settings.dailyCharacterTarget > StatisticsTargetDefaults.MinDailyCharacterTarget,
                canIncrease = settings.dailyCharacterTarget < StatisticsTargetDefaults.MaxDailyCharacterTarget,
                onDecrease = {
                    onEvent(
                        StatisticsEvent.UpdateDailyCharacterTarget(
                            settings.dailyCharacterTarget - StatisticsTargetDefaults.DailyCharacterTargetStep,
                        ),
                    )
                },
                onIncrease = {
                    onEvent(
                        StatisticsEvent.UpdateDailyCharacterTarget(
                            settings.dailyCharacterTarget + StatisticsTargetDefaults.DailyCharacterTargetStep,
                        ),
                    )
                },
            )
        } else {
            StepperRow(
                label = stringResource(R.string.statistics_current_target),
                value = formatStatisticsDuration(settings.dailyDurationTargetMinutes * 60.0),
                canDecrease = settings.dailyDurationTargetMinutes > StatisticsTargetDefaults.MinDailyDurationTargetMinutes,
                canIncrease = settings.dailyDurationTargetMinutes < StatisticsTargetDefaults.MaxDailyDurationTargetMinutes,
                onDecrease = {
                    onEvent(
                        StatisticsEvent.UpdateDailyDurationTargetMinutes(
                            settings.dailyDurationTargetMinutes - StatisticsTargetDefaults.DailyDurationTargetStepMinutes,
                        ),
                    )
                },
                onIncrease = {
                    onEvent(
                        StatisticsEvent.UpdateDailyDurationTargetMinutes(
                            settings.dailyDurationTargetMinutes + StatisticsTargetDefaults.DailyDurationTargetStepMinutes,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun WeeklyTargetSettingsSection(
    settings: StatisticsTargetSettings,
    onEvent: (StatisticsEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.statistics_weekly_target),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        StepperRow(
            label = stringResource(R.string.statistics_week_status),
            value = formatStatisticsDays(settings.weeklyTargetDays),
            canDecrease = settings.weeklyTargetDays > StatisticsTargetDefaults.MinWeeklyTargetDays,
            canIncrease = settings.weeklyTargetDays < StatisticsTargetDefaults.MaxWeeklyTargetDays,
            onDecrease = { onEvent(StatisticsEvent.UpdateWeeklyTargetDays(settings.weeklyTargetDays - 1)) },
            onIncrease = { onEvent(StatisticsEvent.UpdateWeeklyTargetDays(settings.weeklyTargetDays + 1)) },
        )
    }
}

@Composable
private fun TargetGoalText(
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
internal fun StatisticsSection(
    title: String,
    trailing: String?,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (trailingContent != null) {
                    trailingContent()
                } else if (trailing != null) {
                    Text(
                        text = trailing,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

internal data class StatisticMetric(
    val label: String,
    val value: String,
)

internal fun weekStatisticsMetricsInDisplayOrder(
    durationMetric: StatisticMetric,
    charactersMetric: StatisticMetric,
    speedMetric: StatisticMetric,
    targetDaysMetric: StatisticMetric,
    streakMetric: StatisticMetric,
    averageMetric: StatisticMetric,
): List<StatisticMetric> =
    listOf(
        durationMetric,
        charactersMetric,
        speedMetric,
        averageMetric,
        targetDaysMetric,
        streakMetric,
    )

internal data class MetricCardTextSpec(
    val valueFontSizeSp: Int,
    val valueLineHeightSp: Int,
    val labelFontSizeSp: Int,
    val labelLineHeightSp: Int,
    val labelMaxLines: Int,
)

internal fun metricCardTextSpec(
    metric: StatisticMetric,
    columns: Int,
): MetricCardTextSpec {
    val longValue = metric.value.length >= 7
    val denseGrid = columns >= 3
    return MetricCardTextSpec(
        valueFontSizeSp = when {
            longValue -> 20
            denseGrid -> 21
            else -> 22
        },
        valueLineHeightSp = when {
            longValue -> 23
            denseGrid -> 24
            else -> 26
        },
        labelFontSizeSp = if (denseGrid || metric.label.length >= 12) 12 else 13,
        labelLineHeightSp = 14,
        labelMaxLines = 2,
    )
}

@Composable
internal fun MetricGrid(
    metrics: List<StatisticMetric>,
    columns: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        metrics.chunked(columns).forEach { rowMetrics ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowMetrics.forEach { metric ->
                    MetricCard(metric = metric, columns = columns, modifier = Modifier.weight(1f))
                }
                repeat(columns - rowMetrics.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    metric: StatisticMetric,
    columns: Int,
    modifier: Modifier = Modifier,
) {
    val textSpec = metricCardTextSpec(metric = metric, columns = columns)
    Surface(
        modifier = modifier
            .height(68.dp)
            .semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 7.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = metric.value,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = textSpec.valueFontSizeSp.sp,
                    lineHeight = textSpec.valueLineHeightSp.sp,
                    letterSpacing = 0.sp,
                ),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = metric.label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = textSpec.labelFontSizeSp.sp,
                    lineHeight = textSpec.labelLineHeightSp.sp,
                    letterSpacing = 0.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                maxLines = textSpec.labelMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GoalRing(
    percent: Int,
    modifier: Modifier = Modifier,
) {
    val progress = (percent / 100f).coerceIn(0f, 1f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = MaterialTheme.colorScheme.primary
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 12.dp.toPx()
            val size = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = size,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = topLeft,
                size = size,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        Text(
            text = stringResource(R.string.statistics_percent_format, percent),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun WeekGoalRow(days: List<WeekDayGoalUi>) {
    val weekdayLabels = statisticsWeekdayLabels()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        days.forEachIndexed { index, day ->
            Column(
                modifier = Modifier
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val containerColor = when {
                    day.isToday && day.metTarget -> MaterialTheme.colorScheme.primary
                    day.metTarget -> MaterialTheme.colorScheme.primaryContainer
                    day.isToday -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                }
                val contentColor = when {
                    day.isToday && day.metTarget -> MaterialTheme.colorScheme.onPrimary
                    day.metTarget -> MaterialTheme.colorScheme.onPrimaryContainer
                    day.isToday -> MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = CircleShape,
                    color = containerColor,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = weekdayLabels[index],
                            style = MaterialTheme.typography.labelLarge,
                            color = contentColor,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = day.percent?.let { stringResource(R.string.statistics_percent_format, it) }
                        ?: stringResource(R.string.statistics_empty_goal_value),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

internal data class StatisticsSegmentedOption<T>(
    val value: T,
    val label: String,
    val enabled: Boolean = true,
)

@Composable
internal fun <T> StatisticsSegmentedControl(
    options: List<StatisticsSegmentedOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .height(56.dp)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEach { option ->
                val isSelected = option.value == selected
                val itemShape = RoundedCornerShape(8.dp)
                val background = if (isSelected) {
                    MaterialTheme.colorScheme.surface
                } else {
                    Color.Transparent
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(itemShape)
                        .background(background)
                        .clickable(enabled = option.enabled) { onSelect(option.value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (option.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        },
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .widthIn(min = 0.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetTypeSegmentedButtons(
    selected: DailyTargetType,
    onSelect: (DailyTargetType) -> Unit,
) {
    val entries = DailyTargetType.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        entries.forEachIndexed { index, type ->
            SegmentedButton(
                selected = selected == type,
                onClick = { onSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(index, entries.size),
            ) {
                Text(
                    text = when (type) {
                        DailyTargetType.Characters -> stringResource(R.string.statistics_target_type_characters)
                        DailyTargetType.Duration -> stringResource(R.string.statistics_target_type_duration)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onDecrease, enabled = canDecrease) {
                    Icon(
                        imageVector = Icons.Rounded.Remove,
                        contentDescription = stringResource(R.string.action_decrease),
                    )
                }
                IconButton(onClick = onIncrease, enabled = canIncrease) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.action_increase),
                    )
                }
            }
        }
    }
}

internal fun formatInteger(value: Int): String =
    value.toString()

@Composable
internal fun formatStatisticsDuration(seconds: Double): String {
    val minutes = (seconds / 60.0).roundToInt().coerceAtLeast(0)
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        hours <= 0 -> stringResource(R.string.statistics_duration_minutes_format, minutes)
        remainingMinutes == 0 -> stringResource(R.string.statistics_duration_hours_format, hours)
        else -> stringResource(R.string.statistics_duration_hours_minutes_format, hours, remainingMinutes)
    }
}

@Composable
internal fun formatStatisticsCharacters(characters: Int): String =
    stringResource(R.string.statistics_characters_value_format, formatInteger(characters))

@Composable
internal fun formatStatisticsCharacterCount(characters: Int): String =
    formatInteger(characters)

@Composable
internal fun formatStatisticsDays(days: Int): String =
    pluralStringResource(R.plurals.statistics_days_value, days, days)

@Composable
internal fun formatStatisticsWeeks(weeks: Int): String =
    pluralStringResource(R.plurals.statistics_weeks_value, weeks, weeks)

@Composable
internal fun formatStatisticsSpeed(speedPerHour: Int): String =
    stringResource(R.string.statistics_speed_value_format, formatInteger(speedPerHour))

@Composable
internal fun dailyTargetText(settings: StatisticsTargetSettings): String =
    when (settings.dailyTargetType) {
        DailyTargetType.Characters -> stringResource(
            R.string.statistics_character_target_format,
            formatInteger(settings.dailyCharacterTarget),
        )
        DailyTargetType.Duration -> formatStatisticsDuration(settings.dailyDurationTargetMinutes * 60.0)
    }

@Composable
internal fun statisticsWeekdayLabels(): List<String> = listOf(
    stringResource(R.string.statistics_weekday_monday_short),
    stringResource(R.string.statistics_weekday_tuesday_short),
    stringResource(R.string.statistics_weekday_wednesday_short),
    stringResource(R.string.statistics_weekday_thursday_short),
    stringResource(R.string.statistics_weekday_friday_short),
    stringResource(R.string.statistics_weekday_saturday_short),
    stringResource(R.string.statistics_weekday_sunday_short),
)

@Composable
internal fun statisticsWeekdayTitle(dayOfWeekValue: Int): String =
    when (dayOfWeekValue) {
        1 -> stringResource(R.string.statistics_weekday_monday)
        2 -> stringResource(R.string.statistics_weekday_tuesday)
        3 -> stringResource(R.string.statistics_weekday_wednesday)
        4 -> stringResource(R.string.statistics_weekday_thursday)
        5 -> stringResource(R.string.statistics_weekday_friday)
        6 -> stringResource(R.string.statistics_weekday_saturday)
        else -> stringResource(R.string.statistics_weekday_sunday)
    }
