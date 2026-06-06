package moe.antimony.hoshi.features.dictionary

import moe.antimony.hoshi.features.reader.ReaderLookupPopupFramePayload

internal const val DictionaryPullResetTriggerDistanceDp = 160

internal enum class DictionaryPullResetAction {
    None,
    ResetAndFocus,
    FocusOnly,
}

internal fun dictionaryPullResetAction(
    distancePx: Float,
    thresholdPx: Float,
    hasQuery: Boolean,
): DictionaryPullResetAction =
    if (distancePx < thresholdPx) {
        DictionaryPullResetAction.None
    } else if (hasQuery) {
        DictionaryPullResetAction.ResetAndFocus
    } else {
        DictionaryPullResetAction.FocusOnly
    }

internal fun dictionarySearchPullGestureCanStart(
    popups: List<ReaderLookupPopupFramePayload>,
    x: Double,
    y: Double,
): Boolean = dictionarySearchRootGestureCanStart(popups, x, y)

internal fun dictionarySearchHistorySwipeGestureCanStart(
    popups: List<ReaderLookupPopupFramePayload>,
    x: Double,
    y: Double,
): Boolean = dictionarySearchRootGestureCanStart(popups, x, y)

private fun dictionarySearchRootGestureCanStart(
    popups: List<ReaderLookupPopupFramePayload>,
    x: Double,
    y: Double,
): Boolean {
    val rootIndex = popups.indexOfFirst { it.id == DictionarySearchRootPopupId }
    if (rootIndex < 0) return false
    val root = popups[rootIndex]
    if (!root.frame.contains(x, y)) return false
    return popups
        .drop(rootIndex + 1)
        .none { it.frame.contains(x, y) }
}

internal data class DictionarySearchIframeDismissal(
    val popups: List<LookupPopupItem>,
    val clearRootSelection: Boolean,
)

internal fun dictionarySearchIframePopupsAfterSwipeDismiss(
    popups: List<LookupPopupItem>,
    popupId: String,
): DictionarySearchIframeDismissal {
    val index = popups.indexOfFirst { it.id == popupId }
    if (index < 0) return DictionarySearchIframeDismissal(popups, clearRootSelection = false)
    return DictionarySearchIframeDismissal(
        popups = dismissPopupAt(popups, index),
        clearRootSelection = index == 0,
    )
}

private fun moe.antimony.hoshi.features.reader.ReaderLookupPopupFrameRect.contains(
    x: Double,
    y: Double,
): Boolean =
    x >= left &&
        x <= left + width &&
        y >= top &&
        y <= top + height
