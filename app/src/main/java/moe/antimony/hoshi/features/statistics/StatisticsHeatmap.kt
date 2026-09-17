package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode

/** Display-only reading history, independent of the reading-time chart's selection. */
@Composable
internal fun StatisticsHeatmap(
    heatmap: StatisticsHeatmapUi,
    scrollState: LazyListState,
    initiallyScrolled: Boolean,
    onInitiallyScrolled: () -> Unit,
) {
    val dayByDate = remember(heatmap.days) { heatmap.days.associateBy { it.date } }
    val gridStart = statisticsStartOfWeek(heatmap.windowRange.start)
    val weekCount = ChronoUnit.WEEKS.between(gridStart, heatmap.windowRange.end).toInt() + 1
    val currentOnInitiallyScrolled by rememberUpdatedState(onInitiallyScrolled)
    LaunchedEffect(initiallyScrolled, heatmap.windowRange) {
        if (!initiallyScrolled) {
            snapshotFlow { scrollState.layoutInfo.viewportSize.width }.first { it > 0 }
            scrollState.scrollToItem(weekCount - 1)
            currentOnInitiallyScrolled()
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.onSurface
    val empty = if (LocalHoshiEInkMode.current) outline.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerHighest
    val heatColors = listOf(empty) + listOf(0.18f, 0.30f, 0.42f, 0.55f, 0.68f, 0.84f, 1f).map { lerp(empty, primary, it) }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val pattern = stringResource(R.string.statistics_heatmap_month_year_pattern)
    val formatter = remember(pattern) { DateTimeFormatter.ofPattern(pattern) }
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(
                modifier = Modifier.padding(top = HeatmapMonthLabelHeight + HeatmapInset),
                verticalArrangement = Arrangement.spacedBy(HeatmapSpacing),
            ) {
                statisticsWeekdayLabels().forEachIndexed { index, label ->
                    Box(
                        Modifier.widthIn(min = HeatmapCellSize).height(HeatmapCellSize),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (index % 3 == 0) label else "",
                            // Keep the row aligned to its cell without clipping the taller text line.
                            modifier = Modifier.wrapContentHeight(unbounded = true),
                            style = labelStyle,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).height(HeatmapHeight).clipToBounds()) {
                LazyRow(
                    state = scrollState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = HeatmapInset),
                    horizontalArrangement = Arrangement.spacedBy(HeatmapSpacing),
                ) {
                    items(weekCount, key = { gridStart.plusWeeks(it.toLong()).toEpochDay() }) {
                        Spacer(Modifier.width(HeatmapCellSize).fillMaxHeight())
                    }
                }
                Canvas(Modifier.matchParentSize()) {
                    val cellSize = HeatmapCellSize.toPx()
                    val pitch = HeatmapCellSize.roundToPx() + HeatmapSpacing.roundToPx()
                    val topInset = (HeatmapMonthLabelHeight + HeatmapInset).toPx()
                    scrollState.layoutInfo.visibleItemsInfo.forEach { item ->
                        val weekStart = gridStart.plusWeeks(item.index.toLong())
                        val x = (item.offset + scrollState.layoutInfo.beforeContentPadding).toFloat()
                        val label = monthLabelForWeek(weekStart, heatmap.windowRange, formatter)
                        if (label.isNotEmpty()) drawText(textMeasurer, label, Offset(x, 0f), labelStyle, softWrap = false)
                        repeat(7) { index ->
                            val date = weekStart.plusDays(index.toLong())
                            if (date.isAfter(heatmap.windowRange.end)) return@repeat
                            val day = dayByDate[date]
                            val topLeft = Offset(x, topInset + index * pitch)
                            val radius = CornerRadius(2.5.dp.toPx())
                            drawRoundRect(heatColors[(day?.heatLevel ?: 0).coerceIn(0, 7)], topLeft, Size(cellSize, cellSize), radius)
                            if (date == heatmap.windowRange.end) {
                                val inset = 0.5.dp.toPx()
                                drawRoundRect(
                                    outline, topLeft - Offset(inset, inset), Size(cellSize + inset * 2, cellSize + inset * 2),
                                    CornerRadius(3.dp.toPx()), style = Stroke(inset),
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.statistics_heatmap_less), style = labelStyle)
            heatColors.forEach { color ->
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
            }
            Text(stringResource(R.string.statistics_heatmap_more), style = labelStyle)
        }
    }
}

internal fun monthLabelForWeek(
    weekStart: LocalDate,
    window: StatisticsDateRange,
    monthYearFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M"),
): String {
    val monthStart = (0L..6L).map { weekStart.plusDays(it) }.firstOrNull { window.contains(it) && it.dayOfMonth == 1 }
    val labelDate = monthStart ?: window.start.takeIf { !it.isBefore(weekStart) && it.isBefore(weekStart.plusWeeks(1)) }
    return labelDate?.let { if (window.start.year != window.end.year) it.format(monthYearFormatter) else it.monthValue.toString() }.orEmpty()
}

private val HeatmapCellSize = 12.dp
private val HeatmapSpacing = 3.dp
private val HeatmapMonthLabelHeight = 16.dp
private val HeatmapInset = 1.dp
private val HeatmapHeight = HeatmapMonthLabelHeight + HeatmapInset * 2 + HeatmapCellSize * 7 + HeatmapSpacing * 6
