package moe.antimony.hoshi.features.reader

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.ui.theme.HoshiWebView
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults

/** A single attached renderer; all methods are called from the Reader UI scope. */
@SuppressLint("SetJavaScriptEnabled")
internal class ReaderPageMeasurer(
    private val host: ViewGroup,
    private val book: EpubBook,
    assets: ReaderWebAssets,
    fontFileForRequest: (String) -> File?,
    private val scheduler: ReaderPageMeasurementScheduler? = null,
    private val setupScript: (String, ReaderPageMeasurementResources) -> String,
) {
    private val measurementResources = ReaderPageMeasurementResources(book, fontFileForRequest)
    @Volatile private var token: String? = null
    private var url: String? = null
    private var pending: CompletableDeferred<List<Int>>? = null
    private var setupIssued = false
    @Volatile private var closed = false
    private var preparation: ScriptHandler? = null
    private val webView = HoshiWebView(host.context).apply {
        applyHoshiWebViewSecurityDefaults()
        alpha = 0f
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isEnabled = false
        isFocusable = false
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            preparation = WebViewCompat.addDocumentStartJavaScript(this, assets.readerDocumentPreparationJs,
                setOf("https://appassets.androidplatform.net"))
        }
        addJavascriptInterface(object {
            @JavascriptInterface fun canMeasure(documentToken: String): Boolean =
                !closed && documentToken == token && scheduler?.isIdle != false

            @JavascriptInterface fun postMessage(documentToken: String, offsets: String) {
                post {
                    if (!closed && documentToken == token) {
                        try { pending?.complete(Json.decodeFromString<List<Int>>(offsets)) }
                        catch (error: Exception) { pending?.completeExceptionally(error) }
                    }
                }
            }
        }, "HoshiReaderPages")
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                measurementResources.resourceForUrl(request.url.toString())?.toWebResourceResponse()

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onPageFinished(view: WebView, finishedUrl: String?) {
                val current = token ?: return
                if (closed || setupIssued || finishedUrl != url) return
                setupIssued = true
                view.evaluateJavascript(setupScript(current, measurementResources), null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && request.url.toString() == url) {
                    pending?.completeExceptionally(IOException("${error.errorCode}: ${error.description}"))
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                pending?.completeExceptionally(IOException("Page measurement renderer terminated (crash=${detail.didCrash()})"))
                close()
                return true
            }
        }
    }

    init { host.addView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }

    suspend fun measure(spineIndex: Int): List<Int> {
        scheduler?.awaitIdle()
        check(!closed && pending == null)
        val result = CompletableDeferred<List<Int>>()
        pending = result
        token = UUID.randomUUID().toString()
        setupIssued = false
        url = measurementResources.url("/epub/${book.chapters[spineIndex].href}")
        // Same layout dimensions as the visible Reader, even before the next parent traversal.
        webView.measure(android.view.View.MeasureSpec.makeMeasureSpec(host.width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(host.height, android.view.View.MeasureSpec.EXACTLY))
        webView.layout(0, 0, host.width, host.height)
        webView.loadUrl(url!!)
        return try {
            if (scheduler != null) scheduler.withIdleTimeout(60_000) { result.await() }
            else withTimeout(60_000) { result.await() }
        }
        catch (timeout: TimeoutCancellationException) {
            coroutineContext.ensureActive()
            throw IOException("Timed out measuring spine $spineIndex", timeout)
        } finally { pending = null; token = null }
    }

    fun close() {
        if (closed) return
        closed = true
        token = null
        pending?.cancel()
        preparation?.remove()
        preparation = null
        webView.stopLoading()
        webView.removeJavascriptInterface("HoshiReaderPages")
        webView.webViewClient = WebViewClient()
        host.removeView(webView)
        webView.destroy()
    }
}
