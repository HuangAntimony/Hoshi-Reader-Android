package moe.antimony.hoshi.features.reader

import android.view.Menu
import android.view.MenuItem
import moe.antimony.hoshi.epub.HighlightColor

internal object ReaderHighlightSelectionMenu {
    const val parentTitle = "Highlight"
    const val groupId = 0x4849
    const val parentItemId = 0x484900
    const val itemOrder = Menu.NONE

    val actionModeItems: List<ReaderHighlightActionModeItem> =
        listOf(
            ReaderHighlightActionModeItem(
                id = parentItemId,
                order = itemOrder,
                title = parentTitle,
                showAsAction = MenuItem.SHOW_AS_ACTION_ALWAYS,
            ),
        )

    val colorItems: List<ReaderHighlightSelectionMenuItem> =
        HighlightColor.entries.mapIndexed { index, color ->
            ReaderHighlightSelectionMenuItem(
                id = parentItemId + index + 1,
                order = itemOrder + index + 1,
                title = color.rawValue.replaceFirstChar { it.uppercase() },
                color = color,
            )
        }

    fun colorForItemId(itemId: Int): HighlightColor? =
        colorItems.firstOrNull { it.id == itemId }?.color

    fun colorPickerPopupPosition(
        viewLeft: Int,
        viewTop: Int,
        viewWidth: Int,
        screenWidth: Int,
        screenHeight: Int,
        popupWidth: Int,
        popupHeight: Int,
        margin: Int,
        anchor: ReaderHighlightSelectionAnchor?,
    ): ReaderHighlightPopupPosition {
        val fallbackX = viewLeft + viewWidth / 2 - popupWidth / 2
        val anchorCenterX = anchor?.let { viewLeft + (it.left + it.right) / 2 }
        val x = ((anchorCenterX ?: (viewLeft + viewWidth / 2)) - popupWidth / 2)
            .coerceIn(0, (screenWidth - popupWidth).coerceAtLeast(0))
        val minY = viewTop + margin
        val y = anchor?.let {
            val above = viewTop + it.top - popupHeight - margin
            if (above >= minY) above else viewTop + it.bottom + margin
        } ?: minY
        val maxY = (screenHeight - popupHeight - margin).coerceAtLeast(0)
        return ReaderHighlightPopupPosition(
            x = if (anchor == null) fallbackX.coerceIn(0, (screenWidth - popupWidth).coerceAtLeast(0)) else x,
            y = y.coerceIn(0, maxY),
        )
    }
}

internal data class ReaderHighlightSelectionMenuItem(
    val id: Int,
    val order: Int,
    val title: String,
    val color: HighlightColor,
)

internal data class ReaderHighlightActionModeItem(
    val id: Int,
    val order: Int,
    val title: String,
    val showAsAction: Int,
)

internal data class ReaderHighlightSelectionAnchor(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal data class ReaderHighlightPopupPosition(
    val x: Int,
    val y: Int,
)
