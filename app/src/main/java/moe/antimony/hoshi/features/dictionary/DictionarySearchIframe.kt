package moe.antimony.hoshi.features.dictionary

import de.manhhao.hoshi.LookupResult
import moe.antimony.hoshi.features.reader.ReaderLookupPopupFramePayload
import moe.antimony.hoshi.features.reader.ReaderLookupPopupFrameRect
import moe.antimony.hoshi.features.reader.ReaderLookupPopupViewport
import moe.antimony.hoshi.features.reader.ReaderPopupHistoryCounts

internal const val DictionarySearchRootPopupId = "dictionary-search-root"

internal fun dictionarySearchRootFramePayload(
    results: List<LookupResult>,
    viewport: ReaderLookupPopupViewport,
    searchBarBottomDp: Double,
    darkMode: Boolean,
    eInkMode: Boolean,
    iframeUrl: String,
    clearSelectionSignal: Int = 0,
): ReaderLookupPopupFramePayload {
    val top = searchBarBottomDp.coerceIn(0.0, viewport.height)
    return ReaderLookupPopupFramePayload(
        id = DictionarySearchRootPopupId,
        frame = ReaderLookupPopupFrameRect(
            left = 0.0,
            top = top,
            width = viewport.width,
            height = (viewport.height - top).coerceAtLeast(0.0),
        ),
        entriesCount = results.size,
        initialEntryJson = results.firstOrNull()?.let(LookupPopupHtml::entryJsonString),
        popupActionBar = false,
        actionBarVisible = false,
        backCount = 0,
        forwardCount = 0,
        sasayakiVisible = false,
        sasayakiWasPaused = false,
        sasayakiIsPlaying = false,
        darkMode = darkMode,
        eInkMode = eInkMode,
        clearSelectionSignal = clearSelectionSignal,
        selectionOffsetY = top,
        iframeUrl = iframeUrl,
    )
}

internal fun dictionarySearchIframePayloads(
    rootResults: List<LookupResult>,
    childPopups: List<LookupPopupItem>,
    childHistories: Map<String, ReaderPopupHistoryCounts>,
    viewport: ReaderLookupPopupViewport,
    searchBarBottomDp: Double,
    darkMode: Boolean,
    eInkMode: Boolean,
    iframeUrl: String,
    rootClearSelectionSignal: Int = 0,
): List<ReaderLookupPopupFramePayload> {
    if (rootResults.isEmpty()) return emptyList()
    return listOf(
        dictionarySearchRootFramePayload(
            results = rootResults,
            viewport = viewport,
            searchBarBottomDp = searchBarBottomDp,
            darkMode = darkMode,
            eInkMode = eInkMode,
            iframeUrl = iframeUrl,
            clearSelectionSignal = rootClearSelectionSignal,
        ),
    ) + childPopups.mapIndexed { index, popup ->
        val history = childHistories[popup.id] ?: ReaderPopupHistoryCounts()
        ReaderLookupPopupFramePayload.fromPopup(
            popup = popup,
            popupIndex = index + 1,
            viewport = viewport,
            backCount = history.backCount,
            forwardCount = history.forwardCount,
            iframeUrl = iframeUrl,
        )
    }
}
