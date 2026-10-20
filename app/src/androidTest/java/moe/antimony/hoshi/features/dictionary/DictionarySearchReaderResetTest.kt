package moe.antimony.hoshi.features.dictionary

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
import de.manhhao.hoshi.LookupOptions
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.TermResult
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonPrimitive
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.audio.AudioSource
import moe.antimony.hoshi.features.reader.ReaderLookupPopupBridgeCallbacks
import moe.antimony.hoshi.features.reader.ReaderLookupPopupBridgeMessage
import moe.antimony.hoshi.features.reader.ReaderLookupPopupIframeSync
import moe.antimony.hoshi.features.reader.ReaderLookupPopupViewport
import moe.antimony.hoshi.features.reader.ReaderLookupPopupWebBridge
import moe.antimony.hoshi.features.reader.ReaderPopupHistoryCounts
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import moe.antimony.hoshi.features.reader.readerLookupPopupIframeUrl
import moe.antimony.hoshi.navigation.RetainedTabContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real popup assets and in-memory lookup/audio responses; leaves installed app data untouched. */
@RunWith(AndroidJUnit4::class)
class DictionarySearchReaderResetTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun clearingInactiveSearchRemovesItsPageAndSurvivesBookProfileChanges() {
        val repository = MemorySearchRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewModel = DictionarySearchViewModel(repository, scope, Dispatchers.Unconfined)
        viewModel.applyExternalLookup("猫")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = LookupPopupAssets.load(context)
        val active = mutableStateOf(true)
        val popupScale = mutableStateOf(1.0)
        val playbackRequests = AtomicInteger()
        lateinit var session: DictionarySearchSession
        lateinit var renderedState: DictionarySearchUiState
        lateinit var webView: WebView
        try {
            composeRule.setContent {
                session = rememberDictionarySearchSession()
                RetainedTabContent(active.value) {
                    val state by rememberDictionarySearchState(session, viewModel)
                    renderedState = state
                    val document = LookupPopupHtml.renderIframeDocument(
                        assets = assets,
                        audioSettings = state.audioSettings,
                        popupScale = popupScale.value,
                    )
                    val currentDocument = rememberUpdatedState(document)
                    val currentWebView = remember { mutableStateOf<WebView?>(null) }
                    session.bridge.callbacks = ReaderLookupPopupBridgeCallbacks { message ->
                        when (message) {
                            is ReaderLookupPopupBridgeMessage.PlayWordAudio -> playbackRequests.incrementAndGet()
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
                                            "/audio" -> "application/json" to """{"type":"audioSourceList","audioSources":[{"name":"Memory","url":"https://example.invalid/word.mp3"}]}"""
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
            composeRule.waitUntil(10_000) { playbackRequests.get() == 1 }
            evaluate(webView, "document.querySelector('iframe').contentWindow.redirect(1); true")
            composeRule.waitUntil(10_000) { playbackRequests.get() == 2 }
            composeRule.runOnIdle {
                viewModel.setPopups(
                    listOf(
                        LookupPopupItem(
                            id = "old-child",
                            state = LookupPopupState(
                                selection = ReaderSelectionData("猫", "猫", ReaderSelectionRect(120.0, 200.0, 16.0, 18.0), null),
                                results = viewModel.uiState.value.results,
                            ),
                        ),
                    ),
                )
            }
            composeRule.waitUntil(10_000) { playbackRequests.get() == 3 }
            assertEquals("2", evaluate(webView, "document.querySelectorAll('iframe').length"))
            composeRule.runOnIdle {
                viewModel.recordLookupRedirected(1)
                session.rootAtTop.value = false
                session.childHistories.value = mapOf("old-child" to ReaderPopupHistoryCounts(1, 1))
                active.value = false
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                viewModel.updateQuery("uncollected while inactive")
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals("猫", renderedState.query)
                session.clearForReader()
                popupScale.value = 1.5
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertFalse(renderedState.hasResults)
                assertEquals("", renderedState.query)
                assertEquals(0, renderedState.backCount)
                assertTrue(session.rootAtTop.value)
                assertTrue(session.childHistories.value.isEmpty())
                popupScale.value = 1.0
            }
            composeRule.waitUntil(10_000) { evaluate(webView, "document.querySelector('iframe')?.contentWindow?.entryCount") == "0" }
            assertEquals("1", evaluate(webView, "document.querySelectorAll('iframe').length"))
            assertEquals("0", evaluate(webView, "document.querySelector('iframe').contentWindow.eval('backStack.length + forwardStack.length')"))
            composeRule.runOnIdle { active.value = true }
            composeRule.waitForIdle()
            assertEquals(3, playbackRequests.get())
            composeRule.runOnIdle { viewModel.applyExternalLookup("犬") }
            composeRule.waitUntil(10_000) { playbackRequests.get() == 4 }
            composeRule.runOnIdle { assertEquals("犬", renderedState.results.single().matched) }
            evaluate(webView, "document.querySelector('iframe').contentWindow.playEntryAudio(0); true")
            composeRule.waitUntil(10_000) { playbackRequests.get() == 5 }
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

private class MemorySearchRepository : DictionarySearchRepository {
    override val dictionarySettings = MutableStateFlow(DictionarySettings())
    override val audioSettings = MutableStateFlow(
        AudioSettings(
            enableAutoplay = true,
            audioSources = listOf(AudioSource("Memory", "https://example.invalid/list?term={term}")),
        ),
    )
    override suspend fun rebuildLookupQuery() = Unit
    override fun dictionaryStyles(): Map<String, String> = emptyMap()
    override fun lookup(query: String, maxResults: Int, scanLength: Int, options: LookupOptions): List<LookupResult> =
        listOf(
            LookupResult(
                matched = query,
                term = TermResult(query, query, "", emptyArray(), emptyArray(), emptyArray()),
                traceCandidates = emptyArray(),
            ),
        )
}
