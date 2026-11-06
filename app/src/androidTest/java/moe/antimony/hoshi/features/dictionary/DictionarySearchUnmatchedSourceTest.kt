package moe.antimony.hoshi.features.dictionary

import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupOptions
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.TermResult
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonPrimitive
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.reader.ReaderLookupPopupBridgeCallbacks
import moe.antimony.hoshi.features.reader.ReaderLookupPopupBridgeMessage
import moe.antimony.hoshi.features.reader.ReaderLookupPopupIframeSync
import moe.antimony.hoshi.features.reader.ReaderLookupPopupViewport
import moe.antimony.hoshi.features.reader.ReaderLookupPopupWebBridge
import moe.antimony.hoshi.features.reader.ReaderPopupHistoryCounts
import moe.antimony.hoshi.features.reader.readerLookupPopupIframeUrl
import moe.antimony.hoshi.navigation.RetainedTabContent
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real popup assets and in-memory lookup/audio responses; leaves installed app data untouched. */
@RunWith(AndroidJUnit4::class)
class DictionarySearchUnmatchedSourceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun unmatchedSourceRemainsClickableAndSubsequentSubmissionsReplaceIt() {
        val repository = UnmatchedSearchRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val history = DictionaryLookupHistoryRepository(DictionaryHistoryDeviceStore(), scope, Dispatchers.Unconfined)
        val viewModel = DictionarySearchViewModel(repository, history, scope, Dispatchers.Unconfined)
        val sourceText = "──今、猫がいる。\n次の行。"
        viewModel.applyExternalLookup(sourceText)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = LookupPopupAssets.load(context)
        lateinit var session: DictionarySearchSession
        lateinit var webView: WebView
        try {
            composeRule.setContent {
                session = rememberDictionarySearchSession()
                RetainedTabContent(true) {
                    val state by rememberDictionarySearchState(session, viewModel)
                    val document = LookupPopupHtml.renderIframeDocument(
                        assets = assets,
                        audioSettings = state.audioSettings,
                        dictionaryStyles = state.dictionaryStyles,
                    )
                    val currentDocument = rememberUpdatedState(document)
                    val currentWebView = remember { mutableStateOf<WebView?>(null) }
                    session.bridge.callbacks = ReaderLookupPopupBridgeCallbacks { message ->
                        when (message) {
                            is ReaderLookupPopupBridgeMessage.LookupRedirect -> {
                                val count = viewModel.lookupRootRedirect(message.query).size
                                currentWebView.value?.evaluateJavascript(
                                    "window.hoshiReaderPopupHost.resolveMessage(${JsonPrimitive(message.popupId)}, ${JsonPrimitive(message.messageId)}, $count)",
                                    null,
                                )
                            }
                            is ReaderLookupPopupBridgeMessage.GetEntry -> {
                                val entry = viewModel.entryForPopup(message.popupId, message.index)
                                    ?.let(LookupPopupHtml::entryJsonString) ?: "null"
                                currentWebView.value?.evaluateJavascript(
                                    "window.hoshiReaderPopupHost.resolveMessage(${JsonPrimitive(message.popupId)}, ${JsonPrimitive(message.messageId)}, $entry)",
                                    null,
                                )
                            }
                            else -> Unit
                        }
                    }
                    DictionarySearchSessionWebView(
                        session = session,
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            WebView(viewContext).apply {
                                settings.javaScriptEnabled = true
                                ReaderLookupPopupWebBridge.install(this, session.bridge)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                                        val (mime, body) = when (request.url.path) {
                                            "/popup/reader-popup-host.js" -> "text/javascript" to assets.readerPopupHostJs
                                            "/popup/iframe.html" -> "text/html" to currentDocument.value
                                            else -> "text/plain" to ""
                                        }
                                        return WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(body.toByteArray())).apply {
                                            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
                                        }
                                    }
                                }
                                loadDataWithBaseURL(
                                    "https://appassets.androidplatform.net/dictionary/iframe-host.html",
                                    lookupPopupIframeHostHtml(), "text/html", "UTF-8", null,
                                )
                                webView = this
                                currentWebView.value = this
                            }
                        },
                        update = {},
                    )
                    ReaderLookupPopupIframeSync(
                        webView = currentWebView.value,
                        payloads = dictionarySearchIframePayloads(
                            rootResults = state.results,
                            sourceText = state.lastQuery.takeIf { state.hasLookupContent },
                            sourceSentenceOffset = state.sentenceOffset,
                            rootHistory = ReaderPopupHistoryCounts(state.backCount, state.forwardCount),
                            childPopups = state.popups,
                            childHistories = session.childHistories.value,
                            viewport = ReaderLookupPopupViewport(390.0, 600.0),
                            searchBarBottomDp = 80.0,
                            darkMode = false,
                            eInkMode = false,
                            iframeUrl = readerLookupPopupIframeUrl(document.hashCode()),
                        ),
                        rootHighlight = null,
                    )
                }
            }
            val frame = "document.querySelector('iframe').contentWindow"
            composeRule.waitUntil(10_000) {
                evaluate(webView, "$frame.document.querySelector('#search-text')?.textContent") == JsonPrimitive(sourceText).toString()
            }
            assertEquals("0", evaluate(webView, "$frame.entryCount"))
            val position = JSONArray(evaluate(webView, """
                (() => {
                    const frame = document.querySelector('iframe');
                    const outer = frame.getBoundingClientRect();
                    const span = frame.contentDocument.querySelector('#search-text [data-index="4"]');
                    const rect = span.getBoundingClientRect();
                    return [(outer.left + rect.left + rect.width / 2) / innerWidth,
                        (outer.top + rect.top + rect.height / 2) / innerHeight];
                })()
            """.trimIndent()))
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val time = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(time, time + if (action == MotionEvent.ACTION_UP) 50 else 0,
                        action, (position.getDouble(0) * webView.width).toFloat(),
                        (position.getDouble(1) * webView.height).toFloat(), 0)
                    webView.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            composeRule.waitUntil(10_000) {
                evaluate(webView, "$frame.document.querySelector('.glossary-content')?.textContent?.includes('fixture cat')") == "true"
            }
            assertEquals(JsonPrimitive(sourceText).toString(), evaluate(webView, "$frame.document.querySelector('#search-text').textContent"))
            composeRule.runOnIdle {
                assertEquals(4, viewModel.uiState.value.sentenceOffset)
                assertEquals(listOf(sourceText), history.state.value.entries)
                viewModel.updateQuery("")
                assertTrue(viewModel.uiState.value.hasLookupContent)
                assertFalse(viewModel.uiState.value.showLookupHistory)
            }
            for (nextSource in listOf("──別の文章。", "──もう一つ。")) {
                composeRule.runOnIdle { viewModel.applyExternalLookup(nextSource) }
                composeRule.waitUntil(10_000) {
                    evaluate(webView, "$frame.document.querySelector('#search-text')?.textContent") == JsonPrimitive(nextSource).toString()
                }
                assertEquals("0", evaluate(webView, "$frame.entryCount"))
            }
        } finally {
            scope.cancel()
        }
    }

    private fun evaluate(webView: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript(script) { result = it; latch.countDown() }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result
    }
}

private class UnmatchedSearchRepository : DictionarySearchRepository {
    override val dictionarySettings = MutableStateFlow(DictionarySettings())
    override val audioSettings = MutableStateFlow(AudioSettings(enableAutoplay = false))
    override suspend fun ensureLookupQueryReady() = Unit
    override fun dictionaryStyles(): Map<String, String> = mapOf("Fixture" to ".glossary-content { color: black; }")
    override fun lookup(query: String, maxResults: Int, scanLength: Int, options: LookupOptions): List<LookupResult> =
        if (!query.startsWith("猫")) emptyList() else listOf(
            LookupResult(
                matched = "猫",
                term = TermResult("猫", "ねこ", "", arrayOf(GlossaryEntry("Fixture", "fixture cat", "", "")), emptyArray(), emptyArray()),
                traceCandidates = emptyArray(),
            ),
        )
}
