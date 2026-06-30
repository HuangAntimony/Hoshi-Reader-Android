package moe.antimony.hoshi.features.statistics

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt
import moe.antimony.hoshi.R

@Composable
internal fun StatisticsTrendChart(
    mode: StatisticsRangeMode,
    points: List<StatisticsTrendPoint>,
    modifier: Modifier = Modifier,
) {
    if (points.size < 2) {
        Text(
            text = stringResource(R.string.statistics_no_trend_data),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 24.dp),
        )
        return
    }
    val durationColor = MaterialTheme.colorScheme.primary
    val characterColor = MaterialTheme.colorScheme.tertiary
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val maxDuration = max(1.0, points.maxOf { it.readingSeconds })
    val maxCharacters = max(1, points.maxOf { it.characters })
    val xLabelIndexes = remember(points, mode) { trendLabelIndexes(points.size, mode) }
    val axisLabels = TrendAxisLabels(
        durationTop = formatStatisticsDuration(maxDuration),
        durationMiddle = formatStatisticsDuration(maxDuration / 2.0),
        durationBottom = formatStatisticsDuration(0.0),
        charactersTop = formatInteger(maxCharacters),
        charactersMiddle = formatInteger((maxCharacters.toDouble() / 2.0).roundToInt()),
        charactersBottom = formatInteger(0),
    )
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (mode == StatisticsRangeMode.Year) {
                    stringResource(R.string.statistics_monthly_trend)
                } else {
                    stringResource(R.string.statistics_daily_trend)
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            TrendLegend(
                durationColor = durationColor,
                characterColor = characterColor,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .padding(top = 12.dp),
        ) {
            drawTrendChart(
                points = points,
                mode = mode,
                xLabelIndexes = xLabelIndexes,
                maxDuration = maxDuration,
                maxCharacters = maxCharacters,
                durationColor = durationColor,
                characterColor = characterColor,
                axisColor = axisColor,
                gridColor = gridColor,
                labelColor = labelColor,
                axisLabels = axisLabels,
            )
        }
    }
}

@Composable
private fun TrendLegend(
    durationColor: Color,
    characterColor: Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrendLegendItem(
            label = stringResource(R.string.statistics_duration),
            color = durationColor,
        )
        TrendLegendItem(
            label = stringResource(R.string.statistics_characters),
            color = characterColor,
        )
    }
}

@Composable
private fun TrendLegendItem(
    label: String,
    color: Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(width = 12.dp, height = 3.dp),
            shape = RoundedCornerShape(50),
            color = color,
            content = {},
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun DrawScope.drawTrendChart(
    points: List<StatisticsTrendPoint>,
    mode: StatisticsRangeMode,
    xLabelIndexes: List<Int>,
    maxDuration: Double,
    maxCharacters: Int,
    durationColor: Color,
    characterColor: Color,
    axisColor: Color,
    gridColor: Color,
    labelColor: Color,
    axisLabels: TrendAxisLabels,
) {
    if (points.size < 2) return
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor.toArgb()
        textSize = 10.dp.toPx()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val durationPaint = Paint(labelPaint).apply { color = durationColor.toArgb() }
    val characterPaint = Paint(labelPaint).apply {
        color = characterColor.toArgb()
        textAlign = Paint.Align.RIGHT
    }
    val xLabelPaint = Paint(labelPaint).apply {
        color = labelColor.toArgb()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    val axisMargins = trendChartAxisMargins(
        leftLabelWidthsPx = listOf(
            durationPaint.measureText(axisLabels.durationTop),
            labelPaint.measureText(axisLabels.durationMiddle),
            labelPaint.measureText(axisLabels.durationBottom),
        ),
        rightLabelWidthsPx = listOf(
            characterPaint.measureText(axisLabels.charactersTop),
            characterPaint.measureText(axisLabels.charactersMiddle),
            characterPaint.measureText(axisLabels.charactersBottom),
        ),
        minLeftPx = 32.dp.toPx(),
        minRightPx = 38.dp.toPx(),
        labelPaddingPx = 6.dp.toPx(),
    )
    val marginLeft = axisMargins.leftPx
    val marginRight = axisMargins.rightPx
    val marginTop = 16.dp.toPx()
    val marginBottom = 28.dp.toPx()
    val plotLeft = marginLeft
    val plotRight = size.width - marginRight
    val plotTop = marginTop
    val plotBottom = size.height - marginBottom
    val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
    val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
    val axisStroke = 1.dp.toPx()
    val lineStroke = 2.25.dp.toPx()
    val pointRadius = 2.6.dp.toPx()

    fun xFor(index: Int): Float = plotLeft + (index.toFloat() / points.lastIndex.toFloat()) * plotWidth
    fun yFor(value: Double, maxValue: Double): Float =
        plotBottom - (value / maxValue).coerceIn(0.0, 1.0).toFloat() * plotHeight

    listOf(0f, 0.5f, 1f).forEach { ratio ->
        val y = plotTop + ratio * plotHeight
        drawLine(
            color = gridColor,
            start = Offset(plotLeft, y),
            end = Offset(plotRight, y),
            strokeWidth = axisStroke,
        )
    }

    drawLine(axisColor, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), axisStroke)
    drawLine(axisColor, Offset(plotRight, plotTop), Offset(plotRight, plotBottom), axisStroke)
    drawLine(axisColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), axisStroke)

    val durationOffsets = points.mapIndexed { index, point ->
        Offset(xFor(index), yFor(point.readingSeconds, maxDuration))
    }
    val characterOffsets = points.mapIndexed { index, point ->
        Offset(xFor(index), yFor(point.characters.toDouble(), maxCharacters.toDouble()))
    }
    drawTrendPath(durationOffsets, durationColor, lineStroke)
    drawTrendPath(characterOffsets, characterColor, lineStroke)
    durationOffsets.forEach { offset -> drawCircle(durationColor, pointRadius, offset) }
    characterOffsets.forEach { offset -> drawCircle(characterColor, pointRadius, offset) }

    drawIntoCanvas { canvas ->
        val nativeCanvas = canvas.nativeCanvas
        val middleY = plotTop + plotHeight / 2f + 3.dp.toPx()
        val bottomY = plotBottom + 3.dp.toPx()
        nativeCanvas.drawText(axisLabels.durationTop, 0f, plotTop + 3.dp.toPx(), durationPaint)
        nativeCanvas.drawText(axisLabels.durationMiddle, 0f, middleY, labelPaint)
        nativeCanvas.drawText(axisLabels.durationBottom, 0f, bottomY, labelPaint)
        nativeCanvas.drawText(axisLabels.charactersTop, size.width, plotTop + 3.dp.toPx(), characterPaint)
        nativeCanvas.drawText(axisLabels.charactersMiddle, size.width, middleY, characterPaint)
        nativeCanvas.drawText(axisLabels.charactersBottom, size.width, bottomY, characterPaint)
        xLabelIndexes.forEach { index ->
            val label = points[index].label
            nativeCanvas.drawText(
                label,
                xFor(index),
                size.height - 5.dp.toPx(),
                xLabelPaint,
            )
        }
    }
}

internal data class TrendChartAxisMargins(
    val leftPx: Float,
    val rightPx: Float,
)

internal fun trendChartAxisMargins(
    leftLabelWidthsPx: List<Float>,
    rightLabelWidthsPx: List<Float>,
    minLeftPx: Float,
    minRightPx: Float,
    labelPaddingPx: Float,
): TrendChartAxisMargins =
    TrendChartAxisMargins(
        leftPx = max(minLeftPx, leftLabelWidthsPx.maxOrNull().orZero() + labelPaddingPx),
        rightPx = max(minRightPx, rightLabelWidthsPx.maxOrNull().orZero() + labelPaddingPx),
    )

private fun DrawScope.drawTrendPath(
    offsets: List<Offset>,
    color: Color,
    strokeWidth: Float,
) {
    if (offsets.size < 2) return
    val path = Path().apply {
        moveTo(offsets.first().x, offsets.first().y)
        offsets.drop(1).forEach { offset -> lineTo(offset.x, offset.y) }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

private fun trendLabelIndexes(count: Int, mode: StatisticsRangeMode): List<Int> {
    if (count <= 0) return emptyList()
    if (mode == StatisticsRangeMode.Week || count <= 8) {
        return List(count) { it }
    }
    if (mode == StatisticsRangeMode.Year) {
        return List(count) { it }
    }
    val indexes = linkedSetOf(0, count - 1)
    var index = 6
    while (index < count - 1) {
        indexes += index
        index += 7
    }
    return indexes.sorted()
}

private data class TrendAxisLabels(
    val durationTop: String,
    val durationMiddle: String,
    val durationBottom: String,
    val charactersTop: String,
    val charactersMiddle: String,
    val charactersBottom: String,
)

private fun Float?.orZero(): Float = this ?: 0f
