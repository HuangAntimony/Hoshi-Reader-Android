package moe.antimony.hoshi.features.dictionary

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject.quote
import java.io.ByteArrayInputStream
import de.manhhao.hoshi.HoshiDicts
import de.manhhao.hoshi.LookupResult
import moe.antimony.hoshi.dictionary.LookupEngine
import moe.antimony.hoshi.features.audio.AudioPlaybackMode
import moe.antimony.hoshi.features.audio.AudioRequestHandler
import moe.antimony.hoshi.features.reader.ReaderFontManager
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import moe.antimony.hoshi.features.reader.ReaderSelectionBridgePayload
import moe.antimony.hoshi.features.reader.mediaType
import org.json.JSONObject

internal class PopupWebViewCallbacks(
    val onTapOutside: () -> Unit = {},
    val onSwipeDismiss: () -> Unit = {},
    val onOpenLink: (String) -> Unit = {},
    val onTextSelected: (ReaderSelectionData) -> Int? = { null },
    val onSelectionRectsLoaded: ((List<ReaderSelectionRect>) -> Unit)? = null,
    val onLookupRedirect: (String) -> List<LookupResult> = { query -> LookupEngine.lookup(query) },
    val onLookupRedirected: (Int) -> Unit = {},
    val onPlayWordAudio: (String, AudioPlaybackMode) -> Unit = { _, _ -> },
    val onContentReady: () -> Unit = {},
    val onScroll: () -> Unit = {},
    val onMineEntry: (String) -> Boolean = { false },
    val onDuplicateCheck: (String, (Boolean) -> Unit) -> Unit = { _, reply -> reply(false) },
)

internal class PopupWebViewCallbackHolder(
    var callbacks: PopupWebViewCallbacks,
)

internal class PopupLookupResultsHolder(
    var results: List<LookupResult>,
)

internal class PopupSelectionOffsetHolder(
    var offsetX: Double = 0.0,
    var offsetY: Double = 0.0,
)

internal class PopupContentReadyGate {
    private var generation = 0L
    private var requestId = 0L

    fun reset() {
        generation += 1
        requestId += 1
    }

    fun awaitReadyToDraw(webView: WebView, onReady: () -> Unit) {
        val currentGeneration = generation
        val currentRequestId = requestId + 1
        requestId = currentRequestId
        webView.postVisualStateCallback(
            currentRequestId,
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (generation != currentGeneration || this@PopupContentReadyGate.requestId != currentRequestId) {
                        return
                    }
                    onReady()
                }
            },
        )
    }
}

internal class PopupMessageWebViewClient(
    private val callbackHolder: PopupWebViewCallbackHolder,
    private val audioRequestHandler: AudioRequestHandler? = null,
    private val assets: LookupPopupAssets? = null,
    private val fontManager: ReaderFontManager? = null,
    private val imageRequestHandler: DictionaryImageRequestHandler = DictionaryImageRequestHandler(),
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        handlePopupUrl(request.url)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        handlePopupUrl(Uri.parse(url))

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        handleAssetRequest(request.url)
            ?: handleFontRequest(request.url)
            ?: imageRequestHandler.handleImageRequest(request.url)
            ?: audioRequestHandler?.handleAudioRequest(request.url.toString())

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
        Uri.parse(url).let { uri ->
            handleAssetRequest(uri)
                ?: handleFontRequest(uri)
                ?: imageRequestHandler.handleImageRequest(uri)
                ?: audioRequestHandler?.handleAudioRequest(url)
        }

    private fun handleAssetRequest(uri: Uri): WebResourceResponse? {
        val assets = assets ?: return null
        if (uri.host != "hoshi.local" || !uri.path.orEmpty().startsWith("/popup/")) return null
        val content = when (uri.lastPathSegment) {
            "popup.css" -> assets.popupCss
            "selection.js" -> assets.selectionJs
            "popup.js" -> assets.popupJs
            else -> return null
        }
        val mimeType = when (uri.lastPathSegment) {
            "popup.css" -> "text/css"
            else -> "application/javascript"
        }
        return WebResourceResponse(
            mimeType,
            "UTF-8",
            ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun handleFontRequest(uri: Uri): WebResourceResponse? {
        val fontManager = fontManager ?: return null
        if (uri.scheme != "https" || uri.host != "hoshi.local") return null
        val fileName = uri.lastPathSegment?.takeIf { uri.path.orEmpty().startsWith("/fonts/") } ?: return null
        val fontFile = fontManager.fontFileForRequest(fileName) ?: return null
        return WebResourceResponse(
            fontFile.mediaType(),
            null,
            fontFile.inputStream(),
        )
    }

    private fun handlePopupUrl(uri: Uri): Boolean {
        if (uri.scheme != "hoshi-popup") return false
        when (uri.host) {
            "tapOutside" -> callbackHolder.callbacks.onTapOutside()
            "swipeDismiss" -> callbackHolder.callbacks.onSwipeDismiss()
        }
        return true
    }
}

internal class DictionaryImageRequestHandler(
    private val loadMedia: (dictionary: String, path: String) -> ByteArray? = { dictionary, path ->
        HoshiDicts.getMediaFile(HoshiDicts.lookupObject, dictionary, path)
    },
) {
    fun handleImageRequest(uri: Uri): WebResourceResponse? {
        val isIosImageScheme = uri.scheme == "image"
        val isAndroidImageEndpoint = uri.scheme == "https" &&
            uri.host == "hoshi.local" &&
            uri.path == "/image"
        if (!isIosImageScheme && !isAndroidImageEndpoint) return null
        val dictionary = uri.getQueryParameter("dictionary").orEmpty()
        val mediaPath = uri.getQueryParameter("path").orEmpty()
        if (dictionary.isBlank() || mediaPath.isBlank()) return null
        val data = loadMedia(dictionary, mediaPath)?.takeIf { it.isNotEmpty() } ?: return null

        return WebResourceResponse(
            dictionaryImageMimeType(mediaPath),
            null,
            ByteArrayInputStream(data),
        ).apply {
            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
        }
    }
}

internal fun dictionaryImageMimeType(path: String): String =
    when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "heic" -> "image/heic"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

internal class PopupWebViewBridge(
    private val webView: WebView,
    private val callbackHolder: PopupWebViewCallbackHolder,
    private val lookupResultsHolder: PopupLookupResultsHolder = PopupLookupResultsHolder(emptyList()),
    private val selectionOffsetHolder: PopupSelectionOffsetHolder = PopupSelectionOffsetHolder(),
    private val contentReadyGate: PopupContentReadyGate = PopupContentReadyGate(),
    private val onShellReady: () -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun getEntry(index: Int): String? =
        lookupResultsHolder.results.getOrNull(index)?.let { LookupPopupHtml.entryJsonString(it) }

    @JavascriptInterface
    fun lookupRedirect(query: String): Int {
        val results = callbackHolder.callbacks.onLookupRedirect(query)
        lookupResultsHolder.results = results
        if (results.isNotEmpty()) {
            mainHandler.post { callbackHolder.callbacks.onLookupRedirected(results.size) }
        }
        return results.size
    }

    @JavascriptInterface
    fun mineEntry(payloadJson: String): Boolean =
        runCatching { callbackHolder.callbacks.onMineEntry(payloadJson) }.getOrDefault(false)

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
            "shellReady" -> mainHandler.post(onShellReady)
            "contentReady" -> mainHandler.post {
                contentReadyGate.awaitReadyToDraw(webView) {
                    callbackHolder.callbacks.onContentReady()
                }
            }
            "popupScrolled" -> mainHandler.post(callbacks.onScroll)
            "playWordAudio" -> payload.optJSONObject("body")?.let { body ->
                val url = body.optString("url").takeIf { it.isNotBlank() } ?: return
                val mode = AudioPlaybackMode.fromRawValue(body.optString("mode"))
                mainHandler.post { callbacks.onPlayWordAudio(url, mode) }
            }
            "textSelected" -> payload.optJSONObject("body")?.toSelectionData(selectionOffsetHolder.offsetX, selectionOffsetHolder.offsetY)?.let { selection ->
                mainHandler.post {
                    val highlightCount = callbacks.onTextSelected(selection) ?: return@post
                    val onSelectionRectsLoaded = callbacks.onSelectionRectsLoaded
                    if (onSelectionRectsLoaded == null) {
                        webView.evaluateJavascript("window.hoshiSelection.highlightSelection($highlightCount)", null)
                        return@post
                    }
                    webView.evaluateJavascript("JSON.stringify(window.hoshiSelection.selectionRects($highlightCount))") { result ->
                        onSelectionRectsLoaded(
                            ReaderSelectionBridgePayload.rectsFromJavascriptResult(result).map { rect ->
                                ReaderSelectionRect(
                                    x = selectionOffsetHolder.offsetX + rect.x,
                                    y = selectionOffsetHolder.offsetY + rect.y,
                                    width = rect.width,
                                    height = rect.height,
                                )
                            },
                        )
                    }
                }
            }
            "duplicateCheck" -> {
                val messageId = payload.optString("id").takeIf { it.isNotBlank() } ?: return
                val expression = payload.optString("body").takeIf { it.isNotBlank() } ?: return
                callbacks.onDuplicateCheck(expression) { isDuplicate ->
                    mainHandler.post {
                        webView.evaluateJavascript(
                            "window.HoshiAndroidPopup && window.HoshiAndroidPopup.resolveMessage(${quote(messageId)}, $isDuplicate)",
                            null,
                        )
                    }
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
        sentenceOffset = opt("sentenceOffset")?.let { if (it == JSONObject.NULL) null else (it as? Number)?.toInt() },
    )
}
