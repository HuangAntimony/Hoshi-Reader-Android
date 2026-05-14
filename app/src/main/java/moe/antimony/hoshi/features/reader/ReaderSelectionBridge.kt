package moe.antimony.hoshi.features.reader

import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray

internal class ReaderSelectionBridge(
    private val webView: WebView,
    private val onTextSelected: (ReaderSelectionData, selectionRects: (Int, (List<ReaderSelectionRect>) -> Unit) -> Unit) -> Unit,
) {
    @JavascriptInterface
    fun postMessage(message: String) {
        val data = ReaderSelectionBridgePayload.fromJson(message) ?: return
        webView.post {
            onTextSelected(data) { highlightCount, onRectsLoaded ->
                webView.evaluateJavascript(ReaderSelectionCommand.SelectionRects(highlightCount).source) { result ->
                    onRectsLoaded(ReaderSelectionBridgePayload.rectsFromJavascriptResult(result))
                }
            }
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

    fun rectsFromJavascriptResult(result: String?): List<ReaderSelectionRect> {
        val rectsJson = runCatching { json.decodeFromString<String>(result.orEmpty()) }.getOrNull()
            ?: return emptyList()
        val array = runCatching { JSONArray(rectsJson) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val rect = array.optJSONObject(index) ?: continue
                add(
                    ReaderSelectionRect(
                        x = rect.optDouble("x"),
                        y = rect.optDouble("y"),
                        width = rect.optDouble("width"),
                        height = rect.optDouble("height"),
                    ),
                )
            }
        }
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
