package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.time.LocalDate
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
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val averageStyle = labelStyle.copy(color = primary)
    val textMeasurer = rememberTextMeasurer()
    val maxHours = max(5.0, ceil(points.maxOf { it.readingSeconds } / 3600.0))
    val topLabel = stringResource(R.string.statistics_chart_hours_format, formatInteger(maxHours.toInt()))
    val zeroLabel = formatInteger(0)
    val averageLabel = stringResource(R.string.statistics_chart_average)
    val density = LocalDensity.current
    val rightMargin = with(density) {
        maxOf(textMeasurer.measure(topLabel, labelStyle).size.width, textMeasurer.measure(averageLabel, averageStyle).size.width).toFloat() + 8.dp.toPx()
    }
    val bottomMargin = with(density) { 28.dp.toPx() }
    val topMargin = with(density) { 8.dp.toPx() }
    val labelIndexes = remember(points, mode) { trendLabelIndexes(points.size, mode) }
    Canvas(
        modifier = modifier.fillMaxWidth().height(128.dp)
            .pointerInput(points, mode, today, onSelectBucket, rightMargin) {
                detectTapGestures { position ->
                    if (position.y < topMargin || position.y > size.height - bottomMargin) return@detectTapGestures
                    val index = trendBucketIndex(position.x, size.width - rightMargin, points.size) ?: return@detectTapGestures
                    val point = points[index]
                    val bucket = statisticsTrendBucket(mode, point.key) ?: return@detectTapGestures
                    if (!bucket.start.isAfter(today)) onSelectBucket(point.key)
                }
            },
    ) {
        val plotWidth = (size.width - rightMargin).coerceAtLeast(1f)
        val plotBottom = size.height - bottomMargin
        val plotHeight = (plotBottom - topMargin).coerceAtLeast(1f)
        val bucketWidth = plotWidth / points.size
        fun y(seconds: Double) = plotBottom - (seconds / (maxHours * 3600)).coerceIn(0.0, 1.0).toFloat() * plotHeight
        repeat(5) { index ->
            val y = topMargin + index * plotHeight / 4f
            drawLine(muted, Offset(0f, y), Offset(plotWidth, y), strokeWidth = 0.7.dp.toPx())
        }
        points.forEachIndexed { index, point ->
            val barHeight = plotBottom - y(point.readingSeconds)
            if (barHeight > 0f) {
                val selected = statisticsTrendBucket(mode, point.key) == selectedBucket
                val hollow = eInkMode && !selected
                val barWidth = bucketWidth * 0.6f
                val topLeft = Offset((index + 0.2f) * bucketWidth, plotBottom - barHeight)
                val radius = minOf(3.dp.toPx(), barWidth / 2f)
                drawRoundRect(
                    color = when {
                        hollow -> surface
                        selected || selectedBucket == null -> primary
                        else -> muted
                    },
                    topLeft = topLeft,
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(radius),
                )
                if (hollow) {
                    // Keep the outline inside the bar and leave its interior clear of grid lines.
                    val strokeWidth = minOf(1.dp.toPx(), barWidth / 3f, barHeight / 3f)
                    val inset = strokeWidth / 2f
                    drawRoundRect(
                        color = primary,
                        topLeft = topLeft + Offset(inset, inset),
                        size = Size(barWidth - strokeWidth, barHeight - strokeWidth),
                        cornerRadius = CornerRadius((radius - inset).coerceAtLeast(0f)),
                        style = Stroke(strokeWidth),
                    )
                }
            }
        }
        if (averageSeconds > 0.0) {
            val averageY = y(averageSeconds)
            drawLine(
                primary, Offset(0f, averageY), Offset(plotWidth, averageY),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )
            drawText(textMeasurer, averageLabel, Offset(plotWidth + 4.dp.toPx(), averageY - 8.dp.toPx()), averageStyle, softWrap = false)
        }
        drawText(textMeasurer, topLabel, Offset(plotWidth + 4.dp.toPx(), 0f), labelStyle, softWrap = false)
        drawText(textMeasurer, zeroLabel, Offset(plotWidth + 4.dp.toPx(), plotBottom - 8.dp.toPx()), labelStyle, softWrap = false)
        labelIndexes.forEach { index ->
            val label = points[index].label
            val measured = textMeasurer.measure(label, labelStyle, softWrap = false)
            val x = ((index + 0.5f) * bucketWidth - measured.size.width / 2f).coerceIn(0f, max(0f, plotWidth - measured.size.width))
            drawText(measured, topLeft = Offset(x, plotBottom + 6.dp.toPx()))
        }
    }
}

internal fun trendBucketIndex(offsetX: Float, plotWidth: Float, bucketCount: Int): Int? {
    if (bucketCount <= 0 || plotWidth <= 0 || offsetX < 0 || offsetX >= plotWidth) return null
    return (offsetX / plotWidth * bucketCount).toInt().coerceAtMost(bucketCount - 1)
}

internal fun trendLabelIndexes(count: Int, mode: StatisticsRangeMode): List<Int> {
    if (count <= 0) return emptyList()
    val maxLabels = when (mode) {
        StatisticsRangeMode.Week -> 7
        StatisticsRangeMode.All -> 3
        else -> 6
    }
    if (count <= maxLabels) return List(count) { it }
    val step = ceil((count - 1).toDouble() / (maxLabels - 1)).toInt()
    return ((0 until count step step) + (count - 1)).distinct()
}
