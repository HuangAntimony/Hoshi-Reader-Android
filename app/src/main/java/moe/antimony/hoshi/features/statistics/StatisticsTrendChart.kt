package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.max
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode

@Composable
internal fun StatisticsTrendChart(
    mode: StatisticsRangeMode,
    points: List<StatisticsTrendPoint>,
    averageSeconds: Double,
    selectedBucket: StatisticsDateRange?,
    today: LocalDate,
    onSelectBucket: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface
    val eInkMode = LocalHoshiEInkMode.current
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (eInkMode) 1f else 0.65f)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor, fontWeight = FontWeight.Normal)
    val averageStyle = labelStyle.copy(color = primary)
    val textMeasurer = rememberTextMeasurer()
    val buckets = remember(points, mode) { points.map { requireNotNull(statisticsTrendBucket(mode, it.key)) } }
    val range = remember(buckets) { StatisticsDateRange(buckets.first().start, buckets.last().end) }
    val maxHours = max(5.0, ceil(points.maxOf { it.readingSeconds } / 3600.0))
    val topLabel = stringResource(R.string.statistics_chart_hours_format, formatInteger(maxHours.toInt()))
    val zeroLabel = formatInteger(0)
    val averageLabel = stringResource(R.string.statistics_chart_average)
    val locale = LocalConfiguration.current.locales[0]
    val datePattern = stringResource(when (mode) {
        StatisticsRangeMode.Week -> R.string.statistics_chart_weekday_pattern
        StatisticsRangeMode.Month -> R.string.statistics_chart_day_pattern
        StatisticsRangeMode.Year -> R.string.statistics_chart_month_pattern
        StatisticsRangeMode.All -> R.string.statistics_chart_month_year_pattern
    })
    val formatter = remember(datePattern, locale) { DateTimeFormatter.ofPattern(datePattern, locale) }
    val density = LocalDensity.current
    val rightMargin = with(density) {
        // Keep the same trailing breathing room as the iOS plot, including its axis labels.
        maxOf(40.dp.toPx(), textMeasurer.measure(topLabel, labelStyle).size.width + 8.dp.toPx(),
            textMeasurer.measure(averageLabel, averageStyle).size.width + 8.dp.toPx())
    }
    val labelHeight = textMeasurer.measure(averageLabel, averageStyle).size.height.toFloat()
    val topMargin = with(density) { maxOf(8.dp.toPx(), labelHeight / 2f) }
    val plotHeight = with(density) { maxOf(96.dp.toPx(), labelHeight * 3 + 8.dp.toPx()) }
    val chartHeight = with(density) { (topMargin + plotHeight + maxOf(24.dp.toPx(), labelHeight + 4.dp.toPx())).toDp() }
    val currentOnSelectBucket = rememberUpdatedState(onSelectBucket)
    Spacer(
        modifier = modifier.fillMaxWidth().height(chartHeight)
            .pointerInput(buckets, today, rightMargin, topMargin, plotHeight) {
                detectTapGestures { position ->
                    if (position.y < topMargin || position.y > topMargin + plotHeight) return@detectTapGestures
                    val index = trendBucketIndex(position.x, size.width - rightMargin, buckets) ?: return@detectTapGestures
                    if (!buckets[index].start.isAfter(today)) currentOnSelectBucket.value(points[index].key)
                }
            }
            .drawWithCache {
                val plotWidth = (size.width - rightMargin).coerceAtLeast(1f)
                val plotBottom = topMargin + plotHeight
                fun x(date: LocalDate) = trendDateFraction(date, range) * plotWidth
                fun y(seconds: Double) = plotBottom - (seconds / (maxHours * 3600)).coerceIn(0.0, 1.0).toFloat() * plotHeight
                val topText = textMeasurer.measure(topLabel, labelStyle, softWrap = false)
                val zeroText = textMeasurer.measure(zeroLabel, labelStyle, softWrap = false)
                val averageText = textMeasurer.measure(averageLabel, averageStyle, softWrap = false)
                val allDates = trendAxisDates(mode, range, locale)
                val widestLabel = allDates.maxOf { textMeasurer.measure(formatter.format(it), labelStyle, softWrap = false).size.width }
                val maxLabels = (plotWidth / (widestLabel + 12.dp.toPx())).toInt().coerceAtLeast(1)
                val ticks = trendAxisDates(mode, range, locale, maxLabels).map { date ->
                    x(date) to textMeasurer.measure(formatter.format(date), labelStyle, softWrap = false)
                }
                val tickBottom = plotBottom + 4.dp.toPx() + ticks.maxOf { it.second.size.height }
                val verticalDash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
                val averageDash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                // Cache bar geometry and text; scrolling only replays the draw operations.
                val bars = points.mapIndexedNotNull { index, point ->
                    val height = plotBottom - y(point.readingSeconds)
                    if (height <= 0f) return@mapIndexedNotNull null
                    val bucket = buckets[index]
                    val start = x(bucket.start)
                    val width = x(bucket.end.plusDays(1)) - start
                    val left = start + width * 0.2f
                    val right = start + width * 0.8f
                    val top = plotBottom - height
                    val radius = minOf(3.dp.toPx(), (right - left) / 2f, height)
                    val hollow = eInkMode && bucket != selectedBucket
                    val strokeWidth = minOf(1.dp.toPx(), (right - left) / 3f, height / 3f)
                    fun path(inset: Float) = Path().apply {
                        val corner = CornerRadius((radius - inset).coerceAtLeast(0f))
                        addRoundRect(RoundRect(left + inset, top + inset, right - inset, plotBottom - inset,
                            topLeftCornerRadius = corner, topRightCornerRadius = corner))
                    }
                    TrendBarDrawing(
                        fill = path(0f),
                        outline = if (hollow) path(strokeWidth / 2f) else null,
                        stroke = Stroke(strokeWidth),
                        color = when {
                            hollow -> surface
                            selectedBucket == null || bucket == selectedBucket -> primary
                            else -> muted
                        },
                    )
                }
                onDrawBehind {
                    repeat(5) { index ->
                        val gridY = topMargin + index * plotHeight / 4f
                        drawLine(muted, Offset(0f, gridY), Offset(plotWidth, gridY), strokeWidth = 0.7.dp.toPx())
                    }
                    ticks.forEach { (tickX, label) ->
                        drawLine(muted, Offset(tickX, topMargin), Offset(tickX, tickBottom), 1.dp.toPx(), pathEffect = verticalDash)
                        drawText(label, topLeft = Offset((tickX + 3.dp.toPx()).coerceAtMost(size.width - label.size.width), plotBottom + 4.dp.toPx()))
                    }
                    bars.forEach { bar ->
                        drawPath(bar.fill, bar.color)
                        bar.outline?.let { drawPath(it, primary, style = bar.stroke) }
                    }
                    if (averageSeconds > 0.0) {
                        val averageY = y(averageSeconds)
                        drawLine(primary, Offset(0f, averageY), Offset(plotWidth, averageY),
                            strokeWidth = 1.5.dp.toPx(), pathEffect = averageDash)
                        // Short sessions can put the mean almost on zero; keep both labels legible.
                        val labelTop = (averageY - averageText.size.height / 2f).coerceIn(
                            topMargin + topText.size.height / 2f + 2.dp.toPx(),
                            plotBottom - zeroText.size.height / 2f - 2.dp.toPx() - averageText.size.height,
                        )
                        drawText(averageText, topLeft = Offset(plotWidth + 4.dp.toPx(), labelTop))
                    }
                    drawText(topText, topLeft = Offset(plotWidth + 4.dp.toPx(), topMargin - topText.size.height / 2f))
                    drawText(zeroText, topLeft = Offset(plotWidth + 4.dp.toPx(), plotBottom - zeroText.size.height / 2f))
                }
            },
    )
}

private data class TrendBarDrawing(
    val fill: Path,
    val outline: Path?,
    val stroke: Stroke,
    val color: androidx.compose.ui.graphics.Color,
)
