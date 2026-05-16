package moe.antimony.hoshi.features.reader

import kotlin.math.roundToInt

internal data class ReaderToolbarContentRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal object ReaderFloatingToolbarAnchor {
    fun rectForTouch(
        viewWidth: Int,
        viewHeight: Int,
        touchX: Float,
        touchY: Float,
        density: Float,
    ): ReaderToolbarContentRect {
        val width = viewWidth.coerceAtLeast(1)
        val height = viewHeight.coerceAtLeast(1)
        val size = (ToolbarAnchorSizeDp * density).roundToInt()
            .coerceAtLeast(1)
            .coerceAtMost(maxOf(width, height))
        val half = size / 2
        val x = touchX.roundToInt().coerceIn(0, width)
        val y = touchY.roundToInt().coerceIn(0, height)
        val left = (x - half).coerceIn(0, width - 1)
        val top = (y - half).coerceIn(0, height - 1)
        val right = (x + half).coerceIn(left + 1, width)
        val bottom = (y + half).coerceIn(top + 1, height)
        return ReaderToolbarContentRect(left, top, right, bottom)
    }

    private const val ToolbarAnchorSizeDp = 48f
}
