package moe.antimony.hoshi.features.reader

import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Generated content and isolated WebViews only; never changes books or preferences. */
@RunWith(AndroidJUnit4::class)
class ReaderContinuousPopupViewportWebViewTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun popupUsesFullViewportWhileContinuousBodyKeepsItsInsets() {
        lateinit var webView: WebView
        composeRule.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).also { webView = it; it.settings.javaScriptEnabled = true }
            })
        }
        composeRule.waitForIdle()
        try {
            val assets = ReaderWebAssets.load(instrumentation.targetContext)
            val popupHost = instrumentation.targetContext.assets.open("hoshi-web/popup/reader-popup-host.js")
                .bufferedReader().use { it.readText() }
            for (mode in ReaderViewMode.entries) for (vertical in listOf(true, false)) for (padding in listOf(0, 5, 30)) {
                val label = "$mode vertical=$vertical padding=$padding"
                val settings = ReaderSettings(viewMode = mode, verticalWriting = vertical,
                    horizontalPadding = padding, verticalPadding = padding, popupFullWidth = true)
                val loaded = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    val styles = ReaderContentStyles.styleTag(settings = settings, readerCssTemplate = assets.readerCss)
                    val script = ReaderPaginationScripts.shellScript(settings = settings, assets = assets)
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                    }
                    webView.loadDataWithBaseURL("https://appassets.androidplatform.net/reader/fixture.html", """
                        <!doctype html><html><head>
                        <script>window.HoshiReaderRestore={postMessage:()=>window.testRestored=true};
                        window.testScrollTokens=[]; window.testPopupMessages=[];
                        window.HoshiReaderScroll={postMessage:token=>testScrollTokens.push(token)};
                        window.HoshiTextSelection={postMessage:value=>window.testLookupSelection=JSON.parse(value)};
                        window.HoshiReaderPopup={postMessage:value=>testPopupMessages.push(JSON.parse(value))};</script>
                        $styles$script
                        <script>${assets.languageJapaneseJs}
                        ${assets.selectionJapaneseJs}
                        ${assets.selectionJs}
                        hoshiSelection.configure({bridge:'android-reader',scaleRects:false,rubyAwareRects:true});
                        $popupHost</script></head><body>
                        ${(1..100).joinToString("") { "<p><ruby>読書<rt>どくしょ</rt></ruby>の時間です。前後の文章を読みます。</p>" }}
                        </body></html>
                    """.trimIndent(), "text/html", "utf-8", null)
                }
                assertTrue(label, loaded.await(15, TimeUnit.SECONDS))
                composeRule.waitUntil(15_000) { evaluate(webView, "Boolean(window.testRestored)") == "true" }
                val viewport = JSONObject(evaluate(webView,
                    "({width:innerWidth,height:innerHeight,body:document.body.getBoundingClientRect().toJSON()})"))
                val width = viewport.getDouble("width")
                val height = viewport.getDouble("height")
                var sourceSelection = ReaderSelectionData("読書", "読書の時間です。",
                    ReaderSelectionRect(100.0, 100.0, 22.0, 44.0), normalizedOffset = 0)
                if (mode == ReaderViewMode.Continuous) {
                    val body = viewport.getJSONObject("body")
                    assertEquals(label, if (vertical) width * padding / 200 else 0.0, body.getDouble("left"), 1.0)
                    assertEquals(label, if (vertical) 0.0 else height * padding / 200, body.getDouble("top"), 1.0)
                    assertEquals(label, if (vertical) width * (1 - padding / 100.0) else width, body.getDouble("width"), 1.0)
                    assertEquals("$label $viewport", if (vertical) height else height * (1 - padding / 100.0), body.getDouble("height"), 1.0)
                    val navigation = evaluate(webView, "hoshiReader.paginate('forward')")
                    assertEquals(label, "\"scrolled\"", navigation)
                    composeRule.waitUntil(3000) { evaluate(webView, "testScrollTokens.length").toInt() > 0 }
                    assertEquals(label, "0", evaluate(webView, "window.scrollX"))
                    assertEquals(label, "0", evaluate(webView, "window.scrollY"))
                    val progress = evaluate(webView, "hoshiReader.calculateProgress()").toDouble()
                    assertTrue("$label scroll updates progress", progress > 0)
                    evaluate(webView, "window.testRestored=false; hoshiReader.restoreProgress($progress); true")
                    composeRule.waitUntil(3000) { evaluate(webView, "Boolean(testRestored)") == "true" }
                    val blank = if (vertical) "[1,innerHeight/2]" else "[innerWidth/2,1]"
                    if (padding > 0) assertEquals("$label padding is not text", "null",
                        evaluate(webView, "hoshiSelection.selectText(...$blank,20)"))
                    val selected = JSONObject(evaluate(webView, """
                        (()=>{
                            const viewport=hoshiReader.contentViewport(), walker=hoshiReader.createWalker();
                            let node;
                            while(node=walker.nextNode()) {
                                if(!node.textContent.trim()) continue;
                                const range=document.createRange(); range.setStart(node,0); range.setEnd(node,1);
                                const r=range.getBoundingClientRect();
                                if(r.left<viewport.left || r.right>viewport.right || r.top<viewport.top || r.bottom>viewport.bottom) continue;
                                hoshiSelection.selectText((r.left+r.right)/2,(r.top+r.bottom)/2,20);
                                if(window.testLookupSelection) return testLookupSelection;
                            }
                            return null;
                        })()
                    """.trimIndent()))
                    val selectedRect = selected.getJSONObject("rect")
                    sourceSelection = ReaderSelectionData(selected.getString("text"), selected.getString("sentence"),
                        ReaderSelectionRect(selectedRect.getDouble("x"), selectedRect.getDouble("y"),
                            selectedRect.getDouble("width"), selectedRect.getDouble("height")),
                        selected.getInt("normalizedOffset"))
                    assertTrue("$label source x", sourceSelection.rect.x >= body.getDouble("left") - 1)
                    assertTrue("$label source y", sourceSelection.rect.y >= body.getDouble("top") - 1)
                }
                val popup = LookupPopupItem(id = "root", state = LookupPopupState(
                    selection = sourceSelection,
                    results = emptyList(), isVertical = vertical, isFullWidth = true, width = 320, height = 250,
                ))
                val payload = ReaderLookupPopupFramePayload.fromPopup(popup, 0,
                    ReaderLookupPopupViewport(width, height), iframeUrl = "about:blank")
                val stack = ReaderLookupPopupStackPayload(popups = listOf(payload)).toJson()
                val geometry = JSONObject(evaluate(webView, """
                    (()=>{
                        hoshiReaderPopupHost.renderStack($stack);
                        const shell=document.querySelector('.hoshi-reader-popup-shell');
                        const iframe=shell.querySelector('iframe');
                        window.dispatchEvent(new MessageEvent('message',{origin:'https://appassets.androidplatform.net',
                            source:iframe.contentWindow,data:{source:'hoshi-popup-iframe',name:'contentReady',popupId:'root'}}));
                        return shell.getBoundingClientRect().toJSON();
                    })()
                """.trimIndent()))
                assertEquals(label, 6.0, geometry.getDouble("left"), 1.0)
                assertEquals(label, width - 6, geometry.getDouble("right"), 1.0)
                assertEquals(label, height - 6, geometry.getDouble("bottom"), 1.0)
                assertEquals(label, minOf(250.0, height - 12), geometry.getDouble("height"), 1.0)
                val childAnchor = JSONObject(evaluate(webView, """
                    (()=>{
                        const iframe=document.querySelector('.hoshi-reader-popup-iframe');
                        window.dispatchEvent(new MessageEvent('message',{origin:'https://appassets.androidplatform.net',
                            source:iframe.contentWindow,data:{source:'hoshi-popup-iframe',name:'textSelected',popupId:'root',
                                body:{text:'辞書',sentence:'辞書の説明',rect:{x:10,y:20,width:22,height:22}}}}));
                        return testPopupMessages.find(m=>m.name==='textSelected').body.rect;
                    })()
                """.trimIndent()))
                assertEquals(label, payload.frame.left + 10, childAnchor.getDouble("x"), 0.01)
                assertEquals(label, payload.selectionOffsetY + 20, childAnchor.getDouble("y"), 0.01)
                val child = popup.copy(id = "child", state = popup.state.copy(isFullWidth = false, isVertical = false,
                    selection = ReaderSelectionData("辞書", "辞書の説明",
                        ReaderSelectionRect(childAnchor.getDouble("x"), childAnchor.getDouble("y"), 22.0, 22.0), null)))
                val childPayload = ReaderLookupPopupFramePayload.fromPopup(child, 1,
                    ReaderLookupPopupViewport(width, height), iframeUrl = "about:blank")
                val childStack = ReaderLookupPopupStackPayload(popups = listOf(payload, childPayload)).toJson()
                val childBounds = JSONObject(evaluate(webView, """
                    hoshiReaderPopupHost.renderStack($childStack);
                    document.querySelector('[data-popup-id="child"]').getBoundingClientRect().toJSON()
                """.trimIndent()))
                assertTrue("$label child stays horizontally visible", childBounds.getDouble("left") >= 5 && childBounds.getDouble("right") <= width - 5)
                assertTrue("$label child stays vertically visible", childBounds.getDouble("top") >= 5 && childBounds.getDouble("bottom") <= height - 5)
            }
        } finally { instrumentation.runOnMainSync { webView.destroy() } }
    }

    @Test
    fun continuousScrollBridgeRejectsPreviousDocumentsAndReleasedWebViews() {
        lateinit var webView: HoshiReaderWebView
        var events = 0
        composeRule.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                HoshiReaderWebView(context).also {
                    webView = it
                    it.settings.javaScriptEnabled = true
                    it.prepareForDocument("current")
                    it.onContinuousScroll = { events++ }
                    it.loadData("<html><body>fixture</body></html>", "text/html", "utf-8")
                }
            })
        }
        composeRule.waitForIdle()
        try {
            evaluate(webView, "HoshiReaderScroll.postMessage('old'); HoshiReaderScroll.postMessage('current'); true")
            composeRule.waitUntil(3000) { events == 1 }
            instrumentation.runOnMainSync { webView.prepareForDocument("next") }
            evaluate(webView, "HoshiReaderScroll.postMessage('current'); HoshiReaderScroll.postMessage('next'); true")
            composeRule.waitUntil(3000) { events == 2 }
            instrumentation.runOnMainSync {
                ReaderContinuousScrollBridge(webView).postMessage("next")
                webView.releaseForDestroy()
            }
            instrumentation.waitForIdleSync()
            assertEquals(2, events)
        } finally { instrumentation.runOnMainSync { webView.destroy() } }
    }

    @Test
    fun chapterBoundaryRepliesExpireAfterAnotherGestureOrDocument() {
        lateinit var webView: HoshiReaderWebView
        var chapters = 0
        composeRule.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                HoshiReaderWebView(context).also {
                    webView = it
                    it.settings.javaScriptEnabled = true
                    it.prepareForDocument("current")
                    it.loadData("<html><body><script>window.hoshiReader={isAtChapterBoundary:()=>true};</script></body></html>",
                        "text/html", "utf-8")
                }
            })
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(3000) { evaluate(webView, "Boolean(window.hoshiReader)") == "true" }
        val listener = ContinuousScrollTouchListener(ReaderSettings(verticalWriting = true, chapterSwipeDistance = 1),
            shouldIgnoreReaderGesture = { false }, onTap = { _, _ -> }, onScrollGesture = {},
            onNextChapter = { chapters++; true }, onPreviousChapter = { chapters--; true })
        fun touch(action: Int, x: Float) {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, x, 100f, 0)
            listener.onTouch(webView, event)
            event.recycle()
        }
        try {
            instrumentation.runOnMainSync {
                webView.bindReaderTouchListener("fixture") { listener }
                touch(MotionEvent.ACTION_DOWN, 0f)
                touch(MotionEvent.ACTION_UP, 300f)
                webView.prepareForDocument("next")
            }
            evaluate(webView, "true")
            assertEquals("Old document cannot turn a chapter", 0, chapters)
            instrumentation.runOnMainSync {
                touch(MotionEvent.ACTION_DOWN, 0f)
                touch(MotionEvent.ACTION_UP, 300f)
                touch(MotionEvent.ACTION_DOWN, 0f)
            }
            evaluate(webView, "true")
            assertEquals("A newer gesture cancels the queued turn", 0, chapters)
            instrumentation.runOnMainSync {
                touch(MotionEvent.ACTION_CANCEL, 0f)
                touch(MotionEvent.ACTION_DOWN, 0f)
                webView.bindReaderTouchListener("fixture") { error("Recomposition must retain the gesture listener") }
                touch(MotionEvent.ACTION_UP, 300f)
            }
            composeRule.waitUntil(3000) { chapters == 1 }
        } finally {
            instrumentation.runOnMainSync { webView.releaseForDestroy(); webView.destroy() }
        }
    }

    private fun evaluate(webView: WebView, source: String): String {
        val done = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { webView.evaluateJavascript(source) { result = it; done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return result
    }
}
