package moe.antimony.hoshi.features.reader

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import moe.antimony.hoshi.features.audio.AudioPlaybackMode
import moe.antimony.hoshi.features.audio.AudioRequestHandler
import moe.antimony.hoshi.features.dictionary.DictionaryImageRequestHandler
import moe.antimony.hoshi.features.dictionary.LookupPopupAssets
import moe.antimony.hoshi.features.dictionary.LookupPopupHtml
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupLayout
import moe.antimony.hoshi.features.dictionary.popupSelectionOffsetY
import java.io.ByteArrayInputStream
import java.io.File

@Serializable
internal data class ReaderLookupPopupViewport(
    val width: Double,
    val height: Double,
)

@Serializable
internal data class ReaderLookupPopupFrameRect(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
)

@Serializable
internal data class ReaderLookupPopupHighlightRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    companion object {
        fun fromReaderRect(rect: ReaderSelectionRect): ReaderLookupPopupHighlightRect =
            ReaderLookupPopupHighlightRect(
                x = rect.x,
                y = rect.y,
                width = rect.width,
                height = rect.height,
            )
    }
}

@Serializable
internal data class ReaderLookupPopupRootHighlightPayload(
    val popupId: String? = null,
    val rects: List<ReaderLookupPopupHighlightRect> = emptyList(),
    val pending: Boolean = false,
    val darkMode: Boolean = false,
    val eInkMode: Boolean = false,
    val verticalWriting: Boolean = false,
) {
    companion object {
        fun fromReaderRects(
            popupId: String?,
            rects: List<ReaderSelectionRect>?,
            darkMode: Boolean,
            eInkMode: Boolean,
            verticalWriting: Boolean,
        ): ReaderLookupPopupRootHighlightPayload =
            ReaderLookupPopupRootHighlightPayload(
                popupId = popupId,
                rects = rects.orEmpty().map(ReaderLookupPopupHighlightRect::fromReaderRect),
                pending = rects == null,
                darkMode = darkMode,
                eInkMode = eInkMode,
                verticalWriting = verticalWriting,
            )
    }
}

@Serializable
internal data class ReaderLookupPopupFramePayload(
    val id: String,
    val frame: ReaderLookupPopupFrameRect,
    val entriesCount: Int,
    val initialEntryJson: String?,
    val popupActionBar: Boolean,
    val actionBarVisible: Boolean,
    val backCount: Int,
    val forwardCount: Int,
    val sasayakiVisible: Boolean,
    val sasayakiWasPaused: Boolean,
    val sasayakiIsPlaying: Boolean,
    val darkMode: Boolean,
    val eInkMode: Boolean,
    val clearSelectionSignal: Int,
    val selectionOffsetY: Double,
    val iframeUrl: String,
    val contentKey: String? = null,
) {
    companion object {
        fun fromPopup(
            popup: LookupPopupItem,
            popupIndex: Int,
            viewport: ReaderLookupPopupViewport,
            entriesCount: Int = popup.state.results.size,
            backCount: Int = 0,
            forwardCount: Int = 0,
            sasayakiWasPaused: Boolean = false,
            sasayakiIsPlaying: Boolean = false,
            iframeUrl: String = readerLookupPopupIframeUrl(),
            includeInitialEntryJson: Boolean = true,
        ): ReaderLookupPopupFramePayload {
            val state = popup.state
            val selectionRect = state.selection.rect
            val frame = LookupPopupLayout(
                selectionRect = selectionRect,
                screenWidth = viewport.width,
                screenHeight = viewport.height,
                maxWidth = state.width.toDouble(),
                maxHeight = state.height.toDouble(),
                isVertical = state.isVertical,
                isFullWidth = state.isFullWidth,
                topInset = state.topInset,
                bottomInset = state.bottomInset,
            ).calculate()
            val top = frame.centerY - frame.height / 2
            val actionBarVisible = state.popupActionBar || backCount > 0 || forwardCount > 0
            val hasSasayakiCue = popup.sasayakiCue != null
            val initialEntryJson = if (includeInitialEntryJson && entriesCount > 0) {
                state.results.firstOrNull()?.let(LookupPopupHtml::entryJsonString)
            } else {
                null
            }
            return ReaderLookupPopupFramePayload(
                id = popup.id,
                frame = ReaderLookupPopupFrameRect(
                    left = frame.centerX - frame.width / 2,
                    top = top,
                    width = frame.width,
                    height = frame.height,
                ),
                entriesCount = entriesCount,
                initialEntryJson = initialEntryJson,
                popupActionBar = state.popupActionBar,
                actionBarVisible = actionBarVisible,
                backCount = backCount,
                forwardCount = forwardCount,
                sasayakiVisible = hasSasayakiCue,
                sasayakiWasPaused = sasayakiWasPaused,
                sasayakiIsPlaying = sasayakiIsPlaying,
                darkMode = state.darkMode,
                eInkMode = state.eInkMode,
                clearSelectionSignal = popup.clearSelectionSignal,
                selectionOffsetY = popupSelectionOffsetY(
                    frameTopDp = top,
                    popupActionBar = state.popupActionBar,
                    backCount = backCount,
                    forwardCount = forwardCount,
                    hasSasayakiCue = hasSasayakiCue,
                ),
                iframeUrl = iframeUrl,
            )
        }
    }
}

@Serializable
internal data class ReaderLookupPopupStackPayload(
    val popups: List<ReaderLookupPopupFramePayload>,
    val rootHighlight: ReaderLookupPopupRootHighlightPayload? = null,
) {
    fun toJson(): String = readerPopupJson.encodeToString(this)
}

internal fun readerLookupPopupTouchBlocksReaderGesture(
    popups: List<ReaderLookupPopupFramePayload>,
    x: Double,
    y: Double,
): Boolean =
    popups.any { popup ->
        val frame = popup.frame
        x >= frame.left &&
            x <= frame.left + frame.width &&
            y >= frame.top &&
            y <= frame.top + frame.height
    }

internal sealed class ReaderLookupPopupBridgeMessage {
    abstract val popupId: String
    abstract val messageId: String?

    data class OpenLink(
        override val popupId: String,
        override val messageId: String?,
        val url: String,
    ) : ReaderLookupPopupBridgeMessage()

    data class TextSelected(
        override val popupId: String,
        override val messageId: String?,
        val selection: ReaderSelectionData,
    ) : ReaderLookupPopupBridgeMessage()

    data class TapOutside(
        override val popupId: String,
        override val messageId: String?,
    ) : ReaderLookupPopupBridgeMessage()

    data class SwipeDismiss(
        override val popupId: String,
        override val messageId: String?,
    ) : ReaderLookupPopupBridgeMessage()

    data class PlayWordAudio(
        override val popupId: String,
        override val messageId: String?,
        val url: String,
        val mode: AudioPlaybackMode,
    ) : ReaderLookupPopupBridgeMessage()

    data class MineEntry(
        override val popupId: String,
        override val messageId: String?,
        val payloadJson: String,
    ) : ReaderLookupPopupBridgeMessage()

    data class DuplicateCheck(
        override val popupId: String,
        override val messageId: String?,
        val expression: String,
    ) : ReaderLookupPopupBridgeMessage()

    data class LookupRedirect(
        override val popupId: String,
        override val messageId: String?,
        val query: String,
    ) : ReaderLookupPopupBridgeMessage()

    data class GetEntry(
        override val popupId: String,
        override val messageId: String?,
        val index: Int,
    ) : ReaderLookupPopupBridgeMessage()

    data class ContentReady(
        override val popupId: String,
        override val messageId: String?,
    ) : ReaderLookupPopupBridgeMessage()

    data class PopupScrolled(
        override val popupId: String,
        override val messageId: String?,
    ) : ReaderLookupPopupBridgeMessage()

    data class ScrollState(
        override val popupId: String,
        override val messageId: String?,
        val atTop: Boolean,
        val scrollTop: Double,
    ) : ReaderLookupPopupBridgeMessage()

    data class NavigateBack(
        override val popupId: String,
        override val messageId: String?,
    ) : ReaderLookupPopupBridgeMessage()

    data class NavigateForward(
        override val popupId: String,
        override val messageId: String?,
    ) : ReaderLookupPopupBridgeMessage()

    data class SasayakiReplayCue(
        override val popupId: String,
        override val messageId: String?,
    ) : ReaderLookupPopupBridgeMessage()

    data class SasayakiTogglePlayback(
        override val popupId: String,
        override val messageId: String?,
    ) : ReaderLookupPopupBridgeMessage()

    data class SasayakiPlayForward(
        override val popupId: String,
        override val messageId: String?,
    ) : ReaderLookupPopupBridgeMessage()

    companion object {
        fun fromJson(message: String): ReaderLookupPopupBridgeMessage? {
            val payload = runCatching { readerPopupJson.parseToJsonElement(message).jsonObject }.getOrNull() ?: return null
            val name = payload.string("name") ?: return null
            val popupId = payload.string("popupId") ?: return null
            val messageId = payload.string("id")
            return when (name) {
                "openLink" -> OpenLink(
                    popupId = popupId,
                    messageId = messageId,
                    url = payload.string("body") ?: return null,
                )
                "textSelected" -> TextSelected(
                    popupId = popupId,
                    messageId = messageId,
                    selection = payload.obj("body")?.toReaderSelectionData() ?: return null,
                )
                "tapOutside" -> TapOutside(popupId, messageId)
                "swipeDismiss" -> SwipeDismiss(popupId, messageId)
                "playWordAudio" -> {
                    val body = payload.obj("body") ?: return null
                    PlayWordAudio(
                        popupId = popupId,
                        messageId = messageId,
                        url = body.string("url") ?: return null,
                        mode = AudioPlaybackMode.fromRawValue(body.string("mode")),
                    )
                }
                "mineEntry" -> MineEntry(
                    popupId = popupId,
                    messageId = messageId ?: return null,
                    payloadJson = payload["body"]?.takeIf { it !is JsonNull }?.toString() ?: return null,
                )
                "duplicateCheck" -> DuplicateCheck(
                    popupId = popupId,
                    messageId = messageId ?: return null,
                    expression = payload.string("body") ?: return null,
                )
                "lookupRedirect" -> LookupRedirect(
                    popupId = popupId,
                    messageId = messageId ?: return null,
                    query = payload.string("body") ?: return null,
                )
                "getEntry" -> GetEntry(
                    popupId = popupId,
                    messageId = messageId ?: return null,
                    index = payload.int("body")?.takeIf { it >= 0 } ?: return null,
                )
                "contentReady" -> ContentReady(popupId, messageId)
                "popupScrolled" -> PopupScrolled(popupId, messageId)
                "scrollState" -> {
                    val body = payload.obj("body") ?: return null
                    ScrollState(
                        popupId = popupId,
                        messageId = messageId,
                        atTop = body.boolean("atTop") ?: return null,
                        scrollTop = body.double("scrollTop") ?: return null,
                    )
                }
                "navigateBack" -> NavigateBack(popupId, messageId)
                "navigateForward" -> NavigateForward(popupId, messageId)
                "sasayakiReplayCue" -> SasayakiReplayCue(popupId, messageId)
                "sasayakiTogglePlayback" -> SasayakiTogglePlayback(popupId, messageId)
                "sasayakiPlayForward" -> SasayakiPlayForward(popupId, messageId)
                else -> null
            }
        }
    }
}

private fun JsonObject.toReaderSelectionData(): ReaderSelectionData? {
    val rect = obj("rect") ?: return null
    return ReaderSelectionData(
        text = string("text") ?: return null,
        sentence = stringOrEmpty("sentence"),
        rect = ReaderSelectionRect(
            x = rect.double("x") ?: return null,
            y = rect.double("y") ?: return null,
            width = rect.double("width")?.takeIf { it > 0.0 } ?: return null,
            height = rect.double("height")?.takeIf { it > 0.0 } ?: return null,
        ),
        normalizedOffset = int("normalizedOffset"),
        sentenceOffset = int("sentenceOffset"),
    )
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)
        ?.content
        ?.takeIf { it.isNotBlank() }

private fun JsonObject.stringOrEmpty(name: String): String =
    string(name).orEmpty()

private fun JsonObject.obj(name: String): JsonObject? =
    (this[name] as? JsonObject)

private fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)
        ?.intOrNull

private fun JsonObject.double(name: String): Double? =
    (this[name] as? JsonPrimitive)
        ?.doubleOrNull
        ?.takeIf { it.isFinite() }

private fun JsonObject.boolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)
        ?.booleanOrNull

private val readerPopupJson = Json { encodeDefaults = true }

private const val PopupIframeUrl = "https://appassets.androidplatform.net/popup/iframe.html"

internal fun readerLookupPopupIframeUrl(cacheKey: Int? = null): String =
    cacheKey?.let { "$PopupIframeUrl?v=$it" } ?: PopupIframeUrl

internal class ReaderLookupPopupBridgeCallbacks(
    val onMessage: (ReaderLookupPopupBridgeMessage) -> Unit = {},
)

internal class ReaderLookupPopupBridgeCallbackHolder(
    var callbacks: ReaderLookupPopupBridgeCallbacks = ReaderLookupPopupBridgeCallbacks(),
)

internal object ReaderLookupPopupWebBridge {
    private fun isSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    fun install(webView: WebView, callbackHolder: ReaderLookupPopupBridgeCallbackHolder) {
        if (!isSupported()) return
        WebViewCompat.addWebMessageListener(
            webView,
            "HoshiReaderPopup",
            setOf("https://appassets.androidplatform.net"),
        ) { _, message, _, _, _ ->
            val data = message.data ?: return@addWebMessageListener
            val parsed = ReaderLookupPopupBridgeMessage.fromJson(data) ?: return@addWebMessageListener
            webView.post {
                callbackHolder.callbacks.onMessage(parsed)
            }
        }
    }
}

internal class ReaderLookupPopupResourceHandler(
    private val context: Context,
    private val assets: LookupPopupAssets,
    private val fontManager: ReaderFontManager,
    private val audioRequestHandler: AudioRequestHandler,
    private val imageRequestHandler: DictionaryImageRequestHandler = DictionaryImageRequestHandler(),
    private val iframeDocument: () -> String,
) {
    fun handle(uri: Uri): WebResourceResponse? =
        when (readerLookupPopupAppAssetRoute(uri.scheme, uri.host, uri.path)) {
            ReaderLookupPopupAppAssetRoute.Popup -> handlePopupAssetRequest(uri) ?: notFoundResponse()
            ReaderLookupPopupAppAssetRoute.Font -> handleFontRequest(uri) ?: notFoundResponse()
            ReaderLookupPopupAppAssetRoute.Image -> imageRequestHandler.handleImageRequest(uri) ?: notFoundResponse()
            null -> imageRequestHandler.handleImageRequest(uri) ?: audioRequestHandler.handleAudioRequest(uri.toString())
        }

    private fun handlePopupAssetRequest(uri: Uri): WebResourceResponse? {
        if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net" || !uri.path.orEmpty().startsWith("/popup/")) return null
        val path = uri.path.orEmpty()
        if (path == "/popup/iframe.html") {
            return textResponse("text/html", iframeDocument())
        }
        if (path.startsWith("/popup/icons/")) {
            val name = uri.lastPathSegment?.takeIf { it.endsWith(".svg") } ?: return null
            return runCatching {
                WebResourceResponse(
                    "image/svg+xml",
                    "UTF-8",
                    context.assets.open("hoshi-web/popup/icons/$name"),
                )
            }.getOrNull()
        }
        val content = when (uri.lastPathSegment) {
            "popup.css" -> assets.popupCss
            "selection.js" -> assets.selectionJs
            "popup.js" -> assets.popupJs
            "reader-popup-host.js" -> assets.readerPopupHostJs
            else -> return null
        }
        val mimeType = if (uri.lastPathSegment == "popup.css") "text/css" else "application/javascript"
        return textResponse(mimeType, content)
    }

    private fun handleFontRequest(uri: Uri): WebResourceResponse? {
        if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net") return null
        val fileName = uri.lastPathSegment?.takeIf { uri.path.orEmpty().startsWith("/fonts/") } ?: return null
        val fontFile = fontManager.fontFileForRequest(fileName) ?: return null
        return WebResourceResponse(
            fontFile.popupFontMediaType(),
            null,
            fontFile.inputStream(),
        )
    }

    private fun textResponse(mimeType: String, content: String): WebResourceResponse =
        WebResourceResponse(
            mimeType,
            "UTF-8",
            ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)),
        )

    private fun notFoundResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            404,
            "Not Found",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0)),
        )
}

internal enum class ReaderLookupPopupAppAssetRoute {
    Popup,
    Font,
    Image,
}

internal fun readerLookupPopupAppAssetRoute(
    scheme: String?,
    host: String?,
    path: String?,
): ReaderLookupPopupAppAssetRoute? {
    if (!scheme.equals("https", ignoreCase = true) || !host.equals("appassets.androidplatform.net", ignoreCase = true)) {
        return null
    }
    val localPath = path.orEmpty()
    return when {
        localPath.startsWith("/popup/") -> ReaderLookupPopupAppAssetRoute.Popup
        localPath.startsWith("/fonts/") -> ReaderLookupPopupAppAssetRoute.Font
        localPath == "/image" -> ReaderLookupPopupAppAssetRoute.Image
        else -> null
    }
}

private fun File.popupFontMediaType(): String =
    when (extension.lowercase()) {
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }
