package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import moe.antimony.hoshi.R

@Composable
internal fun StatisticsCalendarSection(
    calendar: StatisticsCalendarUi,
    onEvent: (StatisticsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    StatisticsSection(
        title = stringResource(R.string.statistics_reading_calendar),
        trailing = null,
        modifier = modifier,
        trailingContent = {
            CalendarWindowDropdown(
                selected = calendar.windowSelection,
                windows = calendar.availableWindows,
                onSelect = { window -> onEvent(StatisticsEvent.SelectCalendarWindow(window)) },
            )
        },
    ) {
        RangeModeSegmentedButtons(
            selected = calendar.rangeMode,
            onSelect = { mode -> onEvent(StatisticsEvent.SelectRangeMode(mode)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        CalendarHeatmap(
            calendar = calendar,
            onDateClick = { date -> onEvent(StatisticsEvent.SelectCalendarDate(date)) },
        )
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.statistics_selected_range_format, rangeTitle(calendar)),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.statistics_days_count_format, calendar.selectedRange.dayCount),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun CalendarWindowDropdown(
    selected: StatisticsCalendarWindowSelection,
    windows: List<StatisticsCalendarWindowSelection>,
    onSelect: (StatisticsCalendarWindowSelection) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true },
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = calendarWindowTitle(selected),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            windows.forEach { window ->
                DropdownMenuItem(
                    text = { Text(calendarWindowTitle(window)) },
                    onClick = {
                        expanded = false
                        onSelect(window)
                    },
                )
            }
        }
    }
}

@Composable
private fun RangeModeSegmentedButtons(
    selected: StatisticsRangeMode,
    onSelect: (StatisticsRangeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = StatisticsRangeMode.entries
    StatisticsSegmentedControl(
        options = modes.map { mode ->
            StatisticsSegmentedOption(
                value = mode,
                label = rangeModeLabel(mode),
            )
        },
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
    )
}

@Composable
private fun CalendarHeatmap(
    calendar: StatisticsCalendarUi,
    onDateClick: (LocalDate) -> Unit,
) {
    val dayByDate = remember(calendar.days) { calendar.days.associateBy { it.date } }
    val gridStart = mondayStartOfWeek(calendar.windowRange.start)
    val weekStarts = remember(calendar.windowRange) {
        generateSequence(gridStart) { it.plusWeeks(1) }
            .takeWhile { weekStart -> !weekStart.isAfter(calendar.windowRange.end) }
            .toList()
    }
    val scrollState = rememberScrollState()
    val gridWidth = heatmapGridWidth(weekStarts.size)
    LaunchedEffect(calendar.windowRange, weekStarts.size) {
        val maxScroll = withTimeoutOrNull(1_000L) {
            snapshotFlow { scrollState.maxValue }.first { maxValue -> maxValue > 0 }
        } ?: scrollState.maxValue
        scrollState.scrollTo(maxScroll)
    }
    val density = LocalDensity.current
    val currentOnDateClick by rememberUpdatedState(onDateClick)
    val heatmapContentDescription = stringResource(R.string.statistics_calendar_heatmap_semantics)
    val heatColors = statisticsHeatColors()
    val primaryBorderColor = MaterialTheme.colorScheme.primary
    val selectedBorderColor = MaterialTheme.colorScheme.tertiary
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CalendarDayCellSpacing),
    ) {
        Column(
            modifier = Modifier.padding(top = CalendarMonthLabelHeight),
            verticalArrangement = Arrangement.spacedBy(CalendarDayCellSpacing),
        ) {
            statisticsWeekdayLabels().forEach { label ->
                Box(
                    modifier = Modifier.size(CalendarDayCellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState),
        ) {
            Row(
                modifier = Modifier
                    .width(gridWidth)
                    .height(CalendarMonthLabelHeight),
                horizontalArrangement = Arrangement.spacedBy(CalendarDayCellSpacing),
            ) {
                weekStarts.forEach { weekStart ->
                    Box(modifier = Modifier.width(CalendarDayCellSize)) {
                        val label = monthLabelForWeek(weekStart, calendar.windowRange)
                        if (label.isNotEmpty()) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Canvas(
                modifier = Modifier
                    .width(gridWidth)
                    .height(CalendarHeatmapGridHeight)
                    .pointerInput(calendar.windowRange, weekStarts, dayByDate) {
                        detectTapGestures { offset ->
                            val cellSizePx = with(density) { CalendarDayCellSize.toPx() }
                            val spacingPx = with(density) { CalendarDayCellSpacing.toPx() }
                            val pitch = cellSizePx + spacingPx
                            val weekIndex = (offset.x / pitch).toInt()
                            val dayIndex = (offset.y / pitch).toInt()
                            val inCellX = offset.x - (weekIndex * pitch) <= cellSizePx
                            val inCellY = offset.y - (dayIndex * pitch) <= cellSizePx
                            val date = weekStarts.getOrNull(weekIndex)?.plusDays(dayIndex.toLong())
                            if (
                                dayIndex in 0..6 &&
                                inCellX &&
                                inCellY &&
                                date != null &&
                                calendar.windowRange.contains(date)
                            ) {
                                currentOnDateClick(date)
                            }
                        }
                    }
                    .semantics { contentDescription = heatmapContentDescription },
            ) {
                val cellSize = CalendarDayCellSize.toPx()
                val spacing = CalendarDayCellSpacing.toPx()
                val cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                val borderWidth = 1.dp.toPx()
                weekStarts.forEachIndexed { weekIndex, weekStart ->
                    repeat(7) { dayIndex ->
                        val date = weekStart.plusDays(dayIndex.toLong())
                        if (!calendar.windowRange.contains(date)) {
                            return@repeat
                        }
                        val day = dayByDate[date] ?: return@repeat
                        val topLeft = Offset(
                            x = weekIndex * (cellSize + spacing),
                            y = dayIndex * (cellSize + spacing),
                        )
                        drawRoundRect(
                            color = heatColors[day.heatLevel.coerceIn(heatColors.indices)],
                            topLeft = topLeft,
                            size = Size(cellSize, cellSize),
                            cornerRadius = cornerRadius,
                        )
                        val borderColor = when {
                            day.isAnchor -> primaryBorderColor
                            day.inSelectedRange -> selectedBorderColor
                            else -> Color.Transparent
                        }
                        if (borderColor != Color.Transparent) {
                            drawRoundRect(
                                color = borderColor,
                                topLeft = topLeft,
                                size = Size(cellSize, cellSize),
                                cornerRadius = cornerRadius,
                                style = Stroke(width = borderWidth),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statisticsHeatColors(): List<Color> {
    val primary = MaterialTheme.colorScheme.primary
    return listOf(
        MaterialTheme.colorScheme.surfaceContainerLow,
        primary.copy(alpha = 0.18f),
        primary.copy(alpha = 0.30f),
        primary.copy(alpha = 0.46f),
        primary.copy(alpha = 0.64f),
        primary.copy(alpha = 0.82f),
    )
}

@Composable
internal fun rangeTitle(calendar: StatisticsCalendarUi): String =
    rangeTitle(
        mode = calendar.rangeMode,
        range = calendar.selectedRange,
        windowSelection = calendar.windowSelection,
    )

@Composable
internal fun rangeTitle(
    mode: StatisticsRangeMode,
    range: StatisticsDateRange,
    windowSelection: StatisticsCalendarWindowSelection,
): String =
    when (mode) {
        StatisticsRangeMode.Year -> calendarWindowTitle(windowSelection)
        StatisticsRangeMode.Month -> stringResource(
            R.string.statistics_range_month_title_format,
            range.start.year,
            range.start.monthValue,
        )
        StatisticsRangeMode.Week -> stringResource(
            R.string.statistics_range_week_title_format,
            range.start.monthValue,
            range.start.dayOfMonth,
            range.end.monthValue,
            range.end.dayOfMonth,
        )
        StatisticsRangeMode.Day -> stringResource(
            R.string.statistics_range_day_title_format,
            range.start.monthValue,
            range.start.dayOfMonth,
            statisticsWeekdayTitle(range.start.dayOfWeek.value),
        )
    }

@Composable
internal fun calendarWindowTitle(selection: StatisticsCalendarWindowSelection): String =
    when (selection.kind) {
        StatisticsCalendarWindowKind.RecentYear -> stringResource(R.string.statistics_recent_year)
        StatisticsCalendarWindowKind.FixedYear -> stringResource(
            R.string.statistics_range_fixed_year_format,
            selection.year ?: 0,
        )
    }

@Composable
internal fun rangeModeLabel(mode: StatisticsRangeMode): String =
    when (mode) {
        StatisticsRangeMode.Year -> stringResource(R.string.statistics_range_year)
        StatisticsRangeMode.Month -> stringResource(R.string.statistics_range_month)
        StatisticsRangeMode.Week -> stringResource(R.string.statistics_range_week)
        StatisticsRangeMode.Day -> stringResource(R.string.statistics_range_day)
    }

private fun monthLabelForWeek(
    weekStart: LocalDate,
    window: StatisticsDateRange,
): String {
    val weekEnd = weekStart.plusDays(6)
    val firstVisibleDay = maxOf(weekStart, window.start)
    return if (firstVisibleDay.dayOfMonth <= 7 || weekStart == mondayStartOfWeek(window.start) || weekEnd.month != weekStart.month) {
        firstVisibleDay.monthValue.toString()
    } else {
        ""
    }
}

private val CalendarDayCellSize = 18.dp
private val CalendarDayCellSpacing = 4.dp
private val CalendarMonthLabelHeight = 18.dp
private val CalendarHeatmapGridHeight = ((CalendarDayCellSize.value * 7) + (CalendarDayCellSpacing.value * 6)).dp

private fun heatmapGridWidth(weekCount: Int) =
    ((CalendarDayCellSize.value * weekCount) + (CalendarDayCellSpacing.value * (weekCount - 1).coerceAtLeast(0))).dp
