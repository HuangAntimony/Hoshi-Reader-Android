package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
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
import moe.antimony.hoshi.R

@Composable
internal fun StatisticsCalendarSection(
    calendar: StatisticsCalendarUi,
    heatmapScrollState: ScrollState,
    heatmapAutoScrolledWindowKey: String?,
    onHeatmapAutoScrolled: (String) -> Unit,
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
            scrollState = heatmapScrollState,
            autoScrolledWindowKey = heatmapAutoScrolledWindowKey,
            onAutoScrolled = onHeatmapAutoScrolled,
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
                    text = formatStatisticsDays(calendar.selectedRange.dayCount),
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
    scrollState: ScrollState,
    autoScrolledWindowKey: String?,
    onAutoScrolled: (String) -> Unit,
    onDateClick: (LocalDate) -> Unit,
) {
    val dayByDate = remember(calendar.days) { calendar.days.associateBy { it.date } }
    val gridStart = mondayStartOfWeek(calendar.windowRange.start)
    val weekStarts = remember(calendar.windowRange) {
        generateSequence(gridStart) { it.plusWeeks(1) }
            .takeWhile { weekStart -> !weekStart.isAfter(calendar.windowRange.end) }
            .toList()
    }
    val canvasWidth = heatmapCanvasWidth(weekStarts.size)
    val autoScrollKey = heatmapAutoScrollKey(calendar.windowRange, weekStarts.size)
    val currentOnAutoScrolled by rememberUpdatedState(onAutoScrolled)
    LaunchedEffect(autoScrollKey, autoScrolledWindowKey) {
        if (!shouldAutoScrollHeatmap(autoScrolledWindowKey, autoScrollKey)) {
            return@LaunchedEffect
        }
        withFrameNanos { }
        val maxScroll = snapshotFlow { scrollState.maxValue }.first { maxValue -> maxValue > 0 }
        scrollState.scrollTo(maxScroll)
        currentOnAutoScrolled(autoScrollKey)
    }
    val density = LocalDensity.current
    val currentOnDateClick by rememberUpdatedState(onDateClick)
    val heatmapContentDescription = stringResource(R.string.statistics_calendar_heatmap_semantics)
    val heatColors = statisticsHeatColors()
    val rangeOutlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
    val anchorOuterStrokeColor = MaterialTheme.colorScheme.onSurface
    val selectionInset = CalendarHeatmapSelectionInset
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CalendarDayCellSpacing),
    ) {
        Column(
            modifier = Modifier.padding(top = CalendarMonthLabelHeight + selectionInset),
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
                    .width(canvasWidth)
                    .height(CalendarMonthLabelHeight),
            ) {
                Spacer(modifier = Modifier.width(selectionInset))
                weekStarts.forEachIndexed { index, weekStart ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.width(CalendarDayCellSpacing))
                    }
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
                Spacer(modifier = Modifier.width(selectionInset))
            }
            Canvas(
                modifier = Modifier
                    .width(canvasWidth)
                    .height(CalendarHeatmapCanvasHeight)
                    .pointerInput(calendar.windowRange, weekStarts, dayByDate) {
                        detectTapGestures { offset ->
                            val cellSizePx = with(density) { CalendarDayCellSize.toPx() }
                            val spacingPx = with(density) { CalendarDayCellSpacing.toPx() }
                            val selectionInsetPx = with(density) { CalendarHeatmapSelectionInset.toPx() }
                            val date = heatmapDateForCanvasPosition(
                                offsetX = offset.x,
                                offsetY = offset.y,
                                selectionInsetPx = selectionInsetPx,
                                weekStarts = weekStarts,
                                window = calendar.windowRange,
                                cellSizePx = cellSizePx,
                                spacingPx = spacingPx,
                            )
                            if (date != null) {
                                currentOnDateClick(date)
                            }
                        }
                    }
                    .semantics { contentDescription = heatmapContentDescription },
            ) {
                val cellSize = CalendarDayCellSize.toPx()
                val spacing = CalendarDayCellSpacing.toPx()
                val selectionInsetPx = CalendarHeatmapSelectionInset.toPx()
                val cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                val selectedCells = mutableListOf<HeatmapCell>()
                val anchorCells = mutableListOf<Pair<Offset, HeatmapSelectionDecoration>>()
                weekStarts.forEachIndexed { weekIndex, weekStart ->
                    repeat(7) { dayIndex ->
                        val date = weekStart.plusDays(dayIndex.toLong())
                        if (!calendar.windowRange.contains(date)) {
                            return@repeat
                        }
                        val day = dayByDate[date] ?: return@repeat
                        val topLeft = Offset(
                            x = selectionInsetPx + weekIndex * (cellSize + spacing),
                            y = selectionInsetPx + dayIndex * (cellSize + spacing),
                        )
                        val selection = heatmapSelectionDecoration(
                            isAnchor = day.isAnchor,
                            inSelectedRange = day.inSelectedRange,
                        )
                        if (day.inSelectedRange) {
                            selectedCells += HeatmapCell(weekIndex, dayIndex)
                        }
                        if (day.isAnchor) {
                            anchorCells += topLeft to selection
                        }
                        drawRoundRect(
                            color = heatColors[day.heatLevel.coerceIn(heatColors.indices)],
                            topLeft = topLeft,
                            size = Size(cellSize, cellSize),
                            cornerRadius = cornerRadius,
                        )
                    }
                }
                if (shouldDrawHeatmapRangeOutline(calendar.rangeMode, selectedCells.size)) {
                    val rangeSelection = heatmapSelectionDecoration(isAnchor = false, inSelectedRange = true)
                    val rangePath = heatmapRangeOutlinePath(
                        selectedCells = selectedCells,
                        selectionInsetPx = selectionInsetPx,
                        cellSizePx = cellSize,
                        spacingPx = spacing,
                        outlinePaddingPx = rangeSelection.rangeOutlinePaddingDp.dp.toPx(),
                    )
                    if (rangePath != null && rangeSelection.rangeOutlineStrokeWidthDp > 0f) {
                        drawPath(
                            path = rangePath,
                            color = rangeOutlineColor,
                            style = Stroke(
                                width = rangeSelection.rangeOutlineStrokeWidthDp.dp.toPx(),
                                join = StrokeJoin.Round,
                            ),
                        )
                    }
                }
                anchorCells.forEach { (topLeft, selection) ->
                    if (selection.anchorOuterStrokeWidthDp > 0f) {
                        val outerStrokeWidth = selection.anchorOuterStrokeWidthDp.dp.toPx()
                        val outerPadding = selection.anchorOuterStrokePaddingDp.dp.toPx()
                        drawRoundRect(
                            color = anchorOuterStrokeColor,
                            topLeft = topLeft - Offset(outerPadding, outerPadding),
                            size = Size(cellSize + outerPadding * 2f, cellSize + outerPadding * 2f),
                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                            style = Stroke(width = outerStrokeWidth),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun statisticsHeatColors(): List<Color> {
    val empty = MaterialTheme.colorScheme.surfaceContainerLow
    val primary = MaterialTheme.colorScheme.primary
    return listOf(
        empty,
        lerp(empty, primary, 0.18f),
        lerp(empty, primary, 0.30f),
        lerp(empty, primary, 0.42f),
        lerp(empty, primary, 0.55f),
        lerp(empty, primary, 0.68f),
        lerp(empty, primary, 0.84f),
        primary,
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

internal fun monthLabelForWeek(
    weekStart: LocalDate,
    window: StatisticsDateRange,
): String {
    val monthStart = (0L..6L)
        .map { offset -> weekStart.plusDays(offset) }
        .firstOrNull { date -> window.contains(date) && date.dayOfMonth == 1 }
    return monthStart?.monthValue?.toString().orEmpty()
}

internal fun heatmapDateForPosition(
    offsetX: Float,
    offsetY: Float,
    weekStarts: List<LocalDate>,
    window: StatisticsDateRange,
    cellSizePx: Float,
    spacingPx: Float,
): LocalDate? {
    if (offsetX < 0f || offsetY < 0f || cellSizePx <= 0f) {
        return null
    }
    val pitch = cellSizePx + spacingPx.coerceAtLeast(0f)
    val hitExpansion = spacingPx.coerceAtLeast(0f) / 2f
    val weekIndex = ((offsetX + hitExpansion) / pitch).toInt()
    val dayIndex = ((offsetY + hitExpansion) / pitch).toInt()
    if (dayIndex !in 0..6) {
        return null
    }
    val cellLeft = weekIndex * pitch
    val cellTop = dayIndex * pitch
    val hitLeft = cellLeft - hitExpansion
    val hitTop = cellTop - hitExpansion
    val hitRight = cellLeft + cellSizePx + hitExpansion
    val hitBottom = cellTop + cellSizePx + hitExpansion
    if (offsetX < hitLeft || offsetX > hitRight || offsetY < hitTop || offsetY > hitBottom) {
        return null
    }
    val date = weekStarts.getOrNull(weekIndex)?.plusDays(dayIndex.toLong()) ?: return null
    return date.takeIf(window::contains)
}

internal fun heatmapDateForCanvasPosition(
    offsetX: Float,
    offsetY: Float,
    selectionInsetPx: Float,
    weekStarts: List<LocalDate>,
    window: StatisticsDateRange,
    cellSizePx: Float,
    spacingPx: Float,
): LocalDate? =
    heatmapDateForPosition(
        offsetX = offsetX - selectionInsetPx,
        offsetY = offsetY - selectionInsetPx,
        weekStarts = weekStarts,
        window = window,
        cellSizePx = cellSizePx,
        spacingPx = spacingPx,
    )

internal fun heatmapAutoScrollKey(
    window: StatisticsDateRange,
    weekCount: Int,
): String = "${window.start}|${window.end}|$weekCount"

internal fun shouldAutoScrollHeatmap(
    autoScrolledWindowKey: String?,
    currentWindowKey: String,
): Boolean = autoScrolledWindowKey != currentWindowKey

internal data class HeatmapSelectionDecoration(
    val hasHalo: Boolean = false,
    val haloPaddingDp: Float = 0f,
    val rangeOutlineStrokeWidthDp: Float = 0f,
    val rangeOutlinePaddingDp: Float = 0f,
    val anchorOuterStrokeWidthDp: Float = 0f,
    val anchorOuterStrokePaddingDp: Float = 0f,
)

internal fun heatmapSelectionDecoration(
    isAnchor: Boolean,
    inSelectedRange: Boolean,
): HeatmapSelectionDecoration =
    when {
        isAnchor -> HeatmapSelectionDecoration(
            anchorOuterStrokeWidthDp = 2f,
            anchorOuterStrokePaddingDp = 2f,
        )
        inSelectedRange -> HeatmapSelectionDecoration(
            rangeOutlineStrokeWidthDp = 2f,
            rangeOutlinePaddingDp = CalendarDayCellSpacing.value / 2f,
        )
        else -> HeatmapSelectionDecoration()
    }

internal fun HeatmapSelectionDecoration.outwardPaddingDp(): Float =
    maxOf(
        haloPaddingDp,
        rangeOutlinePaddingDp + (rangeOutlineStrokeWidthDp / 2f),
        anchorOuterStrokePaddingDp + (anchorOuterStrokeWidthDp / 2f),
    )

internal fun heatmapSelectionInsetDp(): Float =
    maxOf(
        heatmapSelectionDecoration(isAnchor = false, inSelectedRange = true).outwardPaddingDp(),
        heatmapSelectionDecoration(isAnchor = true, inSelectedRange = true).outwardPaddingDp(),
    )

internal fun shouldDrawHeatmapRangeOutline(
    rangeMode: StatisticsRangeMode,
    selectedCellCount: Int,
): Boolean =
    selectedCellCount > 0 && rangeMode != StatisticsRangeMode.Year && rangeMode != StatisticsRangeMode.Day

internal data class HeatmapCell(
    val weekIndex: Int,
    val dayIndex: Int,
)

private data class HeatmapGridPoint(
    val x: Int,
    val y: Int,
)

private fun heatmapRangeOutlinePath(
    selectedCells: List<HeatmapCell>,
    selectionInsetPx: Float,
    cellSizePx: Float,
    spacingPx: Float,
    outlinePaddingPx: Float,
): Path? {
    if (selectedCells.isEmpty()) {
        return null
    }
    val selected = selectedCells.toSet()
    val edges = linkedMapOf<HeatmapGridPoint, HeatmapGridPoint>()
    fun hasCell(weekIndex: Int, dayIndex: Int): Boolean =
        HeatmapCell(weekIndex, dayIndex) in selected
    fun addEdge(startX: Int, startY: Int, endX: Int, endY: Int) {
        edges[HeatmapGridPoint(startX, startY)] = HeatmapGridPoint(endX, endY)
    }
    selected.forEach { cell ->
        val weekIndex = cell.weekIndex
        val dayIndex = cell.dayIndex
        if (!hasCell(weekIndex, dayIndex - 1)) {
            addEdge(weekIndex, dayIndex, weekIndex + 1, dayIndex)
        }
        if (!hasCell(weekIndex + 1, dayIndex)) {
            addEdge(weekIndex + 1, dayIndex, weekIndex + 1, dayIndex + 1)
        }
        if (!hasCell(weekIndex, dayIndex + 1)) {
            addEdge(weekIndex + 1, dayIndex + 1, weekIndex, dayIndex + 1)
        }
        if (!hasCell(weekIndex - 1, dayIndex)) {
            addEdge(weekIndex, dayIndex + 1, weekIndex, dayIndex)
        }
    }
    if (edges.isEmpty()) {
        return null
    }
    val pitch = cellSizePx + spacingPx
    fun pointOffset(point: HeatmapGridPoint): Offset =
        Offset(
            x = selectionInsetPx + point.x * pitch - outlinePaddingPx,
            y = selectionInsetPx + point.y * pitch - outlinePaddingPx,
        )
    val path = Path()
    var guard = edges.size + 1
    while (edges.isNotEmpty() && guard > 0) {
        val start = edges.keys.first()
        var current = start
        val firstOffset = pointOffset(start)
        path.moveTo(firstOffset.x, firstOffset.y)
        do {
            val next = edges.remove(current) ?: break
            val nextOffset = pointOffset(next)
            path.lineTo(nextOffset.x, nextOffset.y)
            current = next
            guard -= 1
        } while (current != start && guard > 0)
        path.close()
    }
    return path
}

private val CalendarDayCellSize = 18.dp
private val CalendarDayCellSpacing = 4.dp
private val CalendarMonthLabelHeight = 18.dp
private val CalendarHeatmapGridHeight = ((CalendarDayCellSize.value * 7) + (CalendarDayCellSpacing.value * 6)).dp
private val CalendarHeatmapSelectionInset = heatmapSelectionInsetDp().dp
private val CalendarHeatmapCanvasHeight = (
    CalendarHeatmapGridHeight.value + (CalendarHeatmapSelectionInset.value * 2f)
).dp

private fun heatmapGridWidth(weekCount: Int) =
    ((CalendarDayCellSize.value * weekCount) + (CalendarDayCellSpacing.value * (weekCount - 1).coerceAtLeast(0))).dp

private fun heatmapCanvasWidth(weekCount: Int) =
    (heatmapGridWidth(weekCount).value + (CalendarHeatmapSelectionInset.value * 2f)).dp
