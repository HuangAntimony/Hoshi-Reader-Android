package moe.antimony.hoshi.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class HoshiSurfaceRoles(
    val page: Color,
    val navigation: Color,
    val group: Color,
    val nested: Color,
    val overlay: Color,
    val selected: Color,
    val onSelected: Color,
    val content: Color,
    val muted: Color,
    val divider: Color,
    val outline: Color,
    val outlineContainers: Boolean,
)

internal fun hoshiSurfaceRoles(colors: ColorScheme, dark: Boolean, eInk: Boolean) = HoshiSurfaceRoles(
    page = if (dark) colors.surface else colors.surfaceContainerLow,
    navigation = colors.surfaceContainer,
    group = if (dark) colors.surfaceContainerLow else colors.surfaceContainerLowest,
    nested = colors.surfaceContainer,
    overlay = colors.surfaceContainerHigh,
    selected = if (eInk) colors.onSurface else if (dark) colors.surfaceContainerHighest else colors.surfaceContainerLowest,
    onSelected = if (eInk) colors.surface else colors.onSurface,
    content = colors.onSurface,
    muted = colors.onSurfaceVariant,
    divider = colors.outlineVariant,
    outline = colors.outline,
    outlineContainers = eInk,
)

val hoshiSurfaces: HoshiSurfaceRoles
    @Composable get() = hoshiSurfaceRoles(MaterialTheme.colorScheme, LocalHoshiDarkTheme.current, LocalHoshiEInkMode.current)

@Composable
fun hoshiContainerBorder(): BorderStroke? =
    if (LocalHoshiEInkMode.current) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null

@Composable
fun Modifier.hoshiContainerOutline(shape: Shape): Modifier =
    hoshiContainerBorder()?.let { border(it, shape) } ?: this

/** Only the outside edges are drawn, so adjacent lazy items form one closed group. */
fun Modifier.hoshiGroupOutline(first: Boolean, last: Boolean, color: Color, radius: Dp): Modifier = drawWithContent {
    drawContent()
    val stroke = 1.dp.toPx()
    val inset = stroke / 2
    val corner = radius.toPx()
    val top = if (first) inset else -corner
    val bottom = if (last) size.height - inset else size.height + corner
    clipRect {
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, top),
            size = Size(size.width - stroke, bottom - top),
            cornerRadius = CornerRadius((corner - inset).coerceAtLeast(0f)),
            style = Stroke(stroke),
        )
    }
}

@Composable
fun Modifier.hoshiLeadingBoundary(): Modifier {
    val outline = hoshiSurfaces.outline
    if (!LocalHoshiEInkMode.current) return this
    return drawWithContent {
        drawContent()
        val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) 0f else size.width
        drawLine(outline, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
    }
}
