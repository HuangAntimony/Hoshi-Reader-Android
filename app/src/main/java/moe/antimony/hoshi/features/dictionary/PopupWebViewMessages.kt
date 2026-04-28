package moe.antimony.hoshi.features.dictionary

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import moe.antimony.hoshi.features.audio.AudioPlaybackMode
import moe.antimony.hoshi.features.audio.AudioRequestHandler
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import org.json.JSONObject

internal class PopupWebViewCallbacks(
    val onTapOutside: () -> Unit = {},
    val onSwipeDismiss: () -> Unit = {},
    val onOpenLink: (String) -> Unit = {},
    val onTextSelected: (ReaderSelectionData) -> Int? = { null },
    val onPlayWordAudio: (String, AudioPlaybackMode) -> Unit = { _, _ -> },
)

internal class PopupWebViewCallbackHolder(
    var callbacks: PopupWebViewCallbacks,
)

internal class PopupMessageWebViewClient(
    private val callbackHolder: PopupWebViewCallbackHolder,
    private val audioRequestHandler: AudioRequestHandler? = null,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        handlePopupUrl(request.url)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        handlePopupUrl(Uri.parse(url))

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        audioRequestHandler?.handleAudioRequest(request.url.toString())

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
        audioRequestHandler?.handleAudioRequest(url)

    private fun handlePopupUrl(uri: Uri): Boolean {
        if (uri.scheme != "hoshi-popup") return false
        when (uri.host) {
            "tapOutside" -> callbackHolder.callbacks.onTapOutside()
            "swipeDismiss" -> callbackHolder.callbacks.onSwipeDismiss()
        }
        return true
    }
}

internal class PopupWebViewBridge(
    private val webView: WebView,
    private val callbackHolder: PopupWebViewCallbackHolder,
    private val selectionOffsetX: Double = 0.0,
    private val selectionOffsetY: Double = 0.0,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun postMessage(message: String) {
        val payload = runCatching { JSONObject(message) }.getOrNull() ?: return
        val callbacks = callbackHolder.callbacks
        when (payload.optString("name")) {
            "openLink" -> payload.optString("body").takeIf { it.isNotBlank() }?.let(callbacks.onOpenLink)
            "tapOutside" -> mainHandler.post {
                callbacks.onTapOutside()
                webView.evaluateJavascript("window.hoshiSelection.clearSelection()", null)
            }
            "swipeDismiss" -> mainHandler.post(callbacks.onSwipeDismiss)
            "playWordAudio" -> payload.optJSONObject("body")?.let { body ->
                val url = body.optString("url").takeIf { it.isNotBlank() } ?: return
                val mode = AudioPlaybackMode.fromRawValue(body.optString("mode"))
                mainHandler.post { callbacks.onPlayWordAudio(url, mode) }
            }
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
