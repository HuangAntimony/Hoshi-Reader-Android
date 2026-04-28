package moe.antimony.hoshi.features.dictionary

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import moe.antimony.hoshi.dictionary.LookupEngine
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import java.util.UUID

internal data class LookupPopupOptions(
    val isVertical: Boolean,
    val isFullWidth: Boolean = false,
    val topInset: Double = 0.0,
    val bottomInset: Double = 0.0,
)

internal data class LookupPopupItem(
    val id: String = UUID.randomUUID().toString(),
    val state: LookupPopupState,
)

internal fun createLookupPopupItem(
    selection: ReaderSelectionData,
    options: LookupPopupOptions,
    dictionaryStyles: Map<String, String> = currentDictionaryStyles(),
): Pair<LookupPopupItem, Int>? {
    val results = runCatching { LookupEngine.lookup(selection.text) }.getOrDefault(emptyList())
    val first = results.firstOrNull() ?: return null
    return LookupPopupItem(
        state = LookupPopupState(
            selection = selection,
            results = results,
            dictionaryStyles = dictionaryStyles,
            isVertical = options.isVertical,
            isFullWidth = options.isFullWidth,
            topInset = options.topInset,
            bottomInset = options.bottomInset,
        ),
    ) to first.matched.codePointCount(0, first.matched.length)
}

internal fun currentDictionaryStyles(): Map<String, String> =
    runCatching {
        LookupEngine.getStyles().associate { it.dictName to it.styles }
    }.getOrDefault(emptyMap())

internal fun closeChildPopups(
    popups: List<LookupPopupItem>,
    parentIndex: Int,
): List<LookupPopupItem> = popups.take(parentIndex + 1)

internal fun dismissPopupAt(
    popups: List<LookupPopupItem>,
    index: Int,
): List<LookupPopupItem> =
    if (index == 0) emptyList() else closeChildPopups(popups, index - 1)

@Composable
internal fun LookupPopupStackView(
    popups: List<LookupPopupItem>,
    onPopupsChange: (List<LookupPopupItem>) -> Unit,
    lookupChildPopup: (ReaderSelectionData) -> Pair<LookupPopupItem, Int>?,
    modifier: Modifier = Modifier,
) {
    popups.forEachIndexed { index, popup ->
        key(popup.id) {
            LookupPopupView(
                state = popup.state,
                onTapOutside = {
                    onPopupsChange(closeChildPopups(popups, index))
                },
                onSwipeDismiss = {
                    onPopupsChange(dismissPopupAt(popups, index))
                },
                onTextSelected = { selection ->
                    val nextPopups = closeChildPopups(popups, index)
                    lookupChildPopup(selection)?.let { (childPopup, highlightCount) ->
                        onPopupsChange(nextPopups + childPopup)
                        highlightCount
                    }
                },
                modifier = modifier,
            )
        }
    }
}
