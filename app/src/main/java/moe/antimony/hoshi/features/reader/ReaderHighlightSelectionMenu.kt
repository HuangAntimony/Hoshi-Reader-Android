package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.HighlightColor

internal object ReaderHighlightSelectionMenu {
    const val parentTitle = "Highlight"
    const val groupId = 0x4849
    const val parentItemId = 0x484900
    const val itemOrder = 200

    val actionModeItems: List<ReaderHighlightActionModeItem> =
        listOf(
            ReaderHighlightActionModeItem(
                id = parentItemId,
                order = itemOrder,
                title = parentTitle,
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
)
