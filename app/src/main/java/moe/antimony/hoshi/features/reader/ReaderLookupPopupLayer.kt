package moe.antimony.hoshi.features.reader

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import moe.antimony.hoshi.features.dictionary.LookupPopupItem

internal data class ReaderRootSelectionHighlight(
    val popupId: String?,
    val rects: List<ReaderSelectionRect>?,
)

internal data class ReaderPopupHistoryCounts(
    val backCount: Int = 0,
    val forwardCount: Int = 0,
)

internal fun readerLookupPopupFramePayloads(
    popups: List<LookupPopupItem>,
    histories: Map<String, ReaderPopupHistoryCounts>,
    viewport: ReaderLookupPopupViewport,
    sasayakiWasPaused: Boolean,
    sasayakiIsPlaying: Boolean,
    iframeUrl: String,
    rootSelectionHighlight: ReaderRootSelectionHighlight?,
): List<ReaderLookupPopupFramePayload> =
    popups.mapIndexed { index, popup ->
        val history = histories[popup.id] ?: ReaderPopupHistoryCounts()
        ReaderLookupPopupFramePayload.fromPopup(
            popup = popup,
            popupIndex = index,
            viewport = viewport,
            backCount = history.backCount,
            forwardCount = history.forwardCount,
            sasayakiWasPaused = sasayakiWasPaused,
            sasayakiIsPlaying = sasayakiIsPlaying,
            iframeUrl = iframeUrl,
            includeInitialEntryJson = shouldSendInitialEntryJson(
                popup = popup,
                popupIndex = index,
                rootSelectionHighlight = rootSelectionHighlight,
            ),
        )
    }

private fun shouldSendInitialEntryJson(
    popup: LookupPopupItem,
    popupIndex: Int,
    rootSelectionHighlight: ReaderRootSelectionHighlight?,
): Boolean =
    popupIndex != 0 ||
        rootSelectionHighlight?.popupId != popup.id ||
        rootSelectionHighlight.rects == null

@Composable
internal fun ReaderLookupPopupIframeSync(
    webView: WebView?,
    payloads: List<ReaderLookupPopupFramePayload>,
    rootHighlight: ReaderLookupPopupRootHighlightPayload?,
) {
    val payloadJson = remember(payloads, rootHighlight) {
        ReaderLookupPopupStackPayload(
            popups = payloads,
            rootHighlight = rootHighlight,
        ).toJson()
    }
    LaunchedEffect(webView, payloadJson) {
        webView?.evaluateJavascript(
            """
                (function() {
                  var payload = $payloadJson;
                  if (window.hoshiReaderPopupHost) {
                    window.hoshiReaderPopupHost.renderStack(payload);
                  } else {
                    window.__hoshiPendingReaderPopupStack = payload;
                  }
                })();
            """.trimIndent(),
            null,
        )
    }
}
