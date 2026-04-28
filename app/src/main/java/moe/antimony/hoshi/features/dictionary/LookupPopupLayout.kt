package moe.antimony.hoshi.features.dictionary

import moe.antimony.hoshi.features.reader.ReaderSelectionRect

data class LookupPopupFrame(
    val width: Double,
    val height: Double,
    val centerX: Double,
    val centerY: Double,
)

data class LookupPopupLayout(
    val selectionRect: ReaderSelectionRect,
    val screenWidth: Double,
    val screenHeight: Double,
    val maxWidth: Double,
    val maxHeight: Double,
    val isVertical: Boolean,
    val isFullWidth: Boolean = false,
    val topInset: Double = 0.0,
    val bottomInset: Double = 0.0,
) {
    fun calculate(): LookupPopupFrame {
        val width = width()
        val height = height()
        return LookupPopupFrame(
            width = width,
            height = height,
            centerX = centerX(width),
            centerY = centerY(height),
        )
    }

    private fun width(): Double {
        if (isFullWidth) return screenWidth - screenBorderPadding * 2
        if (isVertical) return minOf(maxOf(spaceLeft(), spaceRight()) - screenBorderPadding, maxWidth)
        return minOf(screenWidth - screenBorderPadding * 2, maxWidth)
    }

    private fun height(): Double {
        if (isVertical || isFullWidth) return maxHeight
        return minOf(maxOf(spaceAbove(), spaceBelow()) - screenBorderPadding, maxHeight)
    }

    private fun centerX(width: Double): Double {
        if (isFullWidth) return width / 2 + screenBorderPadding
        if (isVertical) {
            val raw = if (showOnRight()) {
                selectionRect.x + selectionRect.width + popupPadding + width / 2
            } else {
                selectionRect.x - popupPadding - width / 2
            }
            return raw.coerceIn(width / 2, screenWidth - width / 2)
        }
        val raw = selectionRect.x + width / 2
        return raw.coerceIn(width / 2 + screenBorderPadding, screenWidth - width / 2 - screenBorderPadding)
    }

    private fun centerY(height: Double): Double {
        if (isFullWidth) return screenHeight - height / 2 - screenBorderPadding
        if (isVertical) {
            val raw = selectionRect.y + height / 2
            return raw.coerceIn(
                height / 2 + screenBorderPadding + topInset,
                screenHeight - bottomInset - height / 2 - screenBorderPadding,
            )
        }
        val raw = if (showBelow(height)) {
            selectionRect.y + selectionRect.height + popupPadding + height / 2
        } else {
            selectionRect.y - popupPadding - height / 2
        }
        return raw.coerceIn(
            height / 2 + topInset + screenBorderPadding,
            screenHeight - bottomInset - height / 2 - screenBorderPadding,
        )
    }

    private fun spaceLeft(): Double = selectionRect.x - popupPadding
    private fun spaceRight(): Double = screenWidth - selectionRect.x - selectionRect.width - popupPadding
    private fun spaceAbove(): Double = selectionRect.y - topInset - popupPadding
    private fun spaceBelow(): Double = screenHeight - bottomInset - selectionRect.y - selectionRect.height - popupPadding
    private fun showOnRight(): Boolean = spaceRight() >= spaceLeft()
    private fun showBelow(height: Double): Boolean = spaceBelow() >= height

    private companion object {
        const val popupPadding = 4.0
        const val screenBorderPadding = 6.0
    }
}
