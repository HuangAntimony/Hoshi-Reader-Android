package moe.antimony.hoshi.features.reader

import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class ReaderSelectionBridge(
    private val webView: WebView,
    private val onTextSelected: (ReaderSelectionData) -> Int?,
) {
    @JavascriptInterface
    fun postMessage(message: String) {
        val data = ReaderSelectionBridgePayload.fromJson(message) ?: return
        webView.post {
            val highlightCount = onTextSelected(data) ?: return@post
            webView.evaluateJavascript(ReaderSelectionCommand.HighlightSelection(highlightCount).source, null)
        }
    }
}

internal object ReaderSelectionBridgePayload {
    private val json = Json { ignoreUnknownKeys = true }

    fun fromJson(message: String): ReaderSelectionData? {
        val payload = runCatching { json.decodeFromString<Payload>(message) }.getOrNull() ?: return null
        return ReaderSelectionData(
            text = payload.text,
            sentence = payload.sentence,
            rect = ReaderSelectionRect(
                x = payload.rect.x,
                y = payload.rect.y,
                width = payload.rect.width,
                height = payload.rect.height,
            ),
            normalizedOffset = payload.normalizedOffset,
            sentenceOffset = payload.sentenceOffset,
        )
    }

    @Serializable
    private data class Payload(
        val text: String,
        val sentence: String,
        val rect: Rect,
        val normalizedOffset: Int? = null,
        val sentenceOffset: Int? = null,
    )

    @Serializable
    private data class Rect(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    )
}
