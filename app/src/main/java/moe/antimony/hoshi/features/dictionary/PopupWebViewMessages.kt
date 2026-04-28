package moe.antimony.hoshi.features.dictionary

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import org.json.JSONObject

internal class PopupWebViewCallbacks(
    val onTapOutside: () -> Unit = {},
    val onSwipeDismiss: () -> Unit = {},
    val onOpenLink: (String) -> Unit = {},
    val onTextSelected: (ReaderSelectionData) -> Int? = { null },
)

internal class PopupMessageWebViewClient(
    private val callbacks: PopupWebViewCallbacks,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        handlePopupUrl(request.url)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        handlePopupUrl(Uri.parse(url))

    private fun handlePopupUrl(uri: Uri): Boolean {
        if (uri.scheme != "hoshi-popup") return false
        when (uri.host) {
            "tapOutside" -> callbacks.onTapOutside()
            "swipeDismiss" -> callbacks.onSwipeDismiss()
        }
        return true
    }
}

internal class PopupWebViewBridge(
    private val webView: WebView,
    private val callbacks: PopupWebViewCallbacks,
    private val selectionOffsetX: Double = 0.0,
    private val selectionOffsetY: Double = 0.0,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun postMessage(message: String) {
        val payload = runCatching { JSONObject(message) }.getOrNull() ?: return
        when (payload.optString("name")) {
            "openLink" -> payload.optString("body").takeIf { it.isNotBlank() }?.let(callbacks.onOpenLink)
            "tapOutside" -> mainHandler.post {
                callbacks.onTapOutside()
                webView.evaluateJavascript("window.hoshiSelection.clearSelection()", null)
            }
            "swipeDismiss" -> mainHandler.post(callbacks.onSwipeDismiss)
            "textSelected" -> payload.optJSONObject("body")?.toSelectionData(selectionOffsetX, selectionOffsetY)?.let { selection ->
                mainHandler.post {
                    val highlightCount = callbacks.onTextSelected(selection) ?: return@post
                    webView.evaluateJavascript("window.hoshiSelection.highlightSelection($highlightCount)", null)
                }
            }
        }
    }
}

private fun JSONObject.toSelectionData(
    offsetX: Double,
    offsetY: Double,
): ReaderSelectionData? {
    val rect = optJSONObject("rect") ?: return null
    return ReaderSelectionData(
        text = optString("text"),
        sentence = optString("sentence"),
        rect = ReaderSelectionRect(
            x = offsetX + rect.optDouble("x"),
            y = offsetY + rect.optDouble("y"),
            width = rect.optDouble("width"),
            height = rect.optDouble("height"),
        ),
        normalizedOffset = opt("normalizedOffset")?.let { if (it == JSONObject.NULL) null else (it as? Number)?.toInt() },
    )
}
