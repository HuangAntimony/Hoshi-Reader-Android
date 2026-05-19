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
import java.io.File
import de.manhhao.hoshi.HoshiDicts
import de.manhhao.hoshi.LookupResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray as KotlinJsonArray
import kotlinx.serialization.json.JsonObject as KotlinJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import moe.antimony.hoshi.dictionary.LookupEngine
import moe.antimony.hoshi.features.audio.AudioPlaybackMode
import moe.antimony.hoshi.features.audio.AudioRequestHandler
import moe.antimony.hoshi.features.reader.ReaderFontManager
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import moe.antimony.hoshi.features.reader.ReaderSelectionBridgePayload
import org.json.JSONArray
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

internal enum class PopupButtonKind(val rawValue: String) {
    Audio("audio"),
    Mine("mine");

    fun actionScript(entryIndex: Int): String =
        when (this) {
            Audio -> "playEntryAudio($entryIndex)"
            Mine -> "mineEntryAtIndex($entryIndex)"
        }

    companion object {
        fun fromRawValue(value: String): PopupButtonKind? =
            entries.firstOrNull { it.rawValue == value }
    }
}

internal enum class PopupButtonState(val rawValue: String) {
    Default("default"),
    Error("error"),
    Duplicate("duplicate");

    companion object {
        fun fromRawValue(value: String): PopupButtonState =
            entries.firstOrNull { it.rawValue == value } ?: Default
    }
}

internal data class PopupButtonFrame(
    val kind: PopupButtonKind,
    val entryIndex: Int,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val state: PopupButtonState = PopupButtonState.Default,
    val enabled: Boolean = true,
) {
    val key: String = "${kind.rawValue}-$entryIndex"
}

private val popupButtonFrameJson = Json { ignoreUnknownKeys = true }

internal fun popupButtonFramesFromMessageJson(message: String): List<PopupButtonFrame> =
    runCatching {
        val payload = popupButtonFrameJson.parseToJsonElement(message) as? KotlinJsonObject
            ?: return@runCatching emptyList()
        popupButtonFramesFromJsonArray(payload["body"] as? KotlinJsonArray ?: return@runCatching emptyList())
    }.getOrDefault(emptyList())

internal fun popupButtonFramesFromJson(array: JSONArray?): List<PopupButtonFrame> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val frame = array.optJSONObject(index)?.toPopupButtonFrame() ?: continue
            add(frame)
        }
    }
}

internal class PopupLookupResultsHolder(
    var results: List<LookupResult>,
)

internal class PopupSelectionOffsetHolder(
    var offsetX: Double = 0.0,
    var offsetY: Double = 0.0,
    var highlightOffsetX: Double = 0.0,
    var highlightOffsetY: Double = 0.0,
)

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
            fontFile.popupFontMediaType(),
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
    private val onShellReady: () -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var buttonFrameVisualStateRequestId = 0L

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
                val frames = popupButtonFramesFromMessageJson(message)
                updateActionButtonFrames(frames)
                callbackHolder.callbacks.onContentReady()
            }
            "popupScrolled" -> mainHandler.post(callbacks.onScroll)
            "buttonFrames" -> {
                val frames = popupButtonFramesFromMessageJson(message)
                mainHandler.post {
                    updateActionButtonFrames(frames)
                }
            }
            "visualStateButtonFrames" -> {
                val frames = popupButtonFramesFromMessageJson(message)
                mainHandler.post {
                    val requestId = buttonFrameVisualStateRequestId + 1
                    buttonFrameVisualStateRequestId = requestId
                    webView.postVisualStateCallback(
                        requestId,
                        object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                if (buttonFrameVisualStateRequestId == requestId) {
                                    updateActionButtonFrames(frames)
                                }
                            }
                        },
                    )
                }
            }
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
                                    x = selectionOffsetHolder.highlightOffsetX + rect.x,
                                    y = selectionOffsetHolder.highlightOffsetY + rect.y,
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

    private fun updateActionButtonFrames(frames: List<PopupButtonFrame>) {
        (webView as? PopupActionButtonWebView)?.updateActionButtonFrames(frames)
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

private fun JSONObject.toPopupButtonFrame(): PopupButtonFrame? {
    val kind = PopupButtonKind.fromRawValue(optString("kind")) ?: return null
    val entryIndex = optInt("entryIndex", -1).takeIf { it >= 0 } ?: return null
    val x = finiteDouble("x") ?: return null
    val y = finiteDouble("y") ?: return null
    val width = finiteDouble("width")?.takeIf { it > 0.0 } ?: return null
    val height = finiteDouble("height")?.takeIf { it > 0.0 } ?: return null
    return PopupButtonFrame(
        kind = kind,
        entryIndex = entryIndex,
        x = x,
        y = y,
        width = width,
        height = height,
        state = PopupButtonState.fromRawValue(optString("state")),
        enabled = if (has("enabled")) optBoolean("enabled") else true,
    )
}

private fun JSONObject.finiteDouble(name: String): Double? =
    optDouble(name, Double.NaN).takeIf { it.isFinite() }

private fun File.popupFontMediaType(): String =
    when (extension.lowercase()) {
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

private fun popupButtonFramesFromJsonArray(array: KotlinJsonArray): List<PopupButtonFrame> =
    array.mapNotNull { (it as? KotlinJsonObject)?.toPopupButtonFrame() }

private fun KotlinJsonObject.toPopupButtonFrame(): PopupButtonFrame? {
    val kind = PopupButtonKind.fromRawValue(this["kind"]?.jsonPrimitive?.content.orEmpty()) ?: return null
    val entryIndex = this["entryIndex"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 } ?: return null
    val x = this["x"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() } ?: return null
    val y = this["y"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() } ?: return null
    val width = this["width"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0.0 && it.isFinite() } ?: return null
    val height = this["height"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0.0 && it.isFinite() } ?: return null
    return PopupButtonFrame(
        kind = kind,
        entryIndex = entryIndex,
        x = x,
        y = y,
        width = width,
        height = height,
        state = PopupButtonState.fromRawValue(this["state"]?.jsonPrimitive?.content.orEmpty()),
        enabled = this["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
    )
}
