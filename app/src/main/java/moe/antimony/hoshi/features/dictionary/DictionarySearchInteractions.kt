package moe.antimony.hoshi.features.dictionary

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
