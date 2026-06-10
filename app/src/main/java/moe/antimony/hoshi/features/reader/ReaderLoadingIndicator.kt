package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun ReaderLoadingIndicator(
    settings: ReaderSettings,
    systemDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = Color(readerChromeColors(settings, systemDark).infoText)
    if (settings.eInkMode) {
        Canvas(modifier.size(ReaderLoadingIndicatorSizeDp.dp)) {
            val stroke = ReaderLoadingIndicatorStrokeDp.dp.toPx()
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    } else {
        CircularProgressIndicator(
            color = color,
            modifier = modifier.size(ReaderLoadingIndicatorSizeDp.dp),
        )
    }
}

private const val ReaderLoadingIndicatorSizeDp = 38
private const val ReaderLoadingIndicatorStrokeDp = 4
