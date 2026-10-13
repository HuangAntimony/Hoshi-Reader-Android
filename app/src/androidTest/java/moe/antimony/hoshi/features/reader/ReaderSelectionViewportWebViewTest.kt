package moe.antimony.hoshi.features.reader

import android.os.SystemClock
import android.view.InputDevice
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Generated chapters only. Selection sampling deliberately never reads layout during a drag. */
@RunWith(AndroidJUnit4::class)
class ReaderSelectionViewportWebViewTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun narrationCueUpdatesPreserveNativeSelection() {
        val webView = createWebView()
        try {
            loadFixture(webView, vertical = true)
            evaluate(webView, """
                (()=>{const node=document.getElementById('tail').firstChild;
                  getSelection().setBaseAndExtent(node,0,node,node.length);
                  window.selectedNode=node;return true;})()
            """.trimIndent())
            composeRule.waitUntil(3000) { webView.nativeSelectionState.value }
            val result = JSONObject(evaluate(webView, """
                (()=>{const start=hoshiReader.nodeStartOffsets.get(selectedNode);
                  hoshiReader.applySasayakiCues([{id:'selected',start:start,length:1}]);
                  hoshiReader.highlightSasayakiCue('selected',false);
                  const selection=getSelection();
                  return {text:selection.toString(),sameAnchor:selection.anchorNode===selectedNode,
                    highlighted:[...CSS.highlights.get('hoshi-sasayaki')].map(r=>r.toString()).join('')};})()
            """.trimIndent()))
            assertEquals("Cue preparation preserves the selected word", "派", result.getString("text"))
            assertTrue("Native handle keeps its original anchor", result.getBoolean("sameAnchor"))
            assertEquals("Narration still paints the cue", "派", result.getString("highlighted"))
        } finally {
            instrumentation.runOnMainSync { webView.releaseForDestroy(); webView.destroy() }
        }
    }

    @Test
    fun draggingRubyHandleKeepsSelectionWithinDraggedText() {
        val webView = createWebView()
        try {
            loadFixture(webView, vertical = true)
            val tail = JSONObject(evaluate(webView, "document.getElementById('tail').getBoundingClientRect().toJSON()"))
            val position = evaluate(webView, "hoshiReader.getPagePosition(hoshiReader.getScrollContext())").toDouble()
            val location = IntArray(2)
            var density = 1f
            instrumentation.runOnMainSync {
                webView.getLocationOnScreen(location)
                density = webView.resources.displayMetrics.density
            }
            fun x(value: Double) = location[0] + (value * density).toFloat()
            fun y(value: Double) = location[1] + (value * density).toFloat()
            gesture(x(tail.getDouble("x") + tail.getDouble("width") / 2),
                y(tail.getDouble("y") + tail.getDouble("height") / 2), duration = 900)
            composeRule.waitUntil(3000) { webView.nativeSelectionState.value }
            val handleX = x(tail.getDouble("right") - 6)
            val startY = y(tail.getDouble("top") + 10)
            val endY = startY - density * 180
            gesture(handleX, startY, handleX, endY, 1500)
            gesture(handleX, endY, handleX, startY - density * 45, 1500)
            val result = JSONObject(evaluate(webView, """
                ({escaped:window.selectionEscapedPage, changes:window.selectionChanges,
                  selected:getSelection().toString(), top:document.getElementById('tail').getBoundingClientRect().top,
                  position:hoshiReader.getPagePosition(hoshiReader.getScrollContext())})
            """.trimIndent()))
            assertTrue("Native handles actually changed the selection", result.getInt("changes") > 5)
            assertTrue("Drag includes ruby base text", result.getString("selected").contains("読"))
            assertEquals("No transient expansion into distant text", "", result.getString("escaped"))
            assertEquals("Visible page stays in place", tail.getDouble("top"), result.getDouble("top"), 1.0)
            assertEquals("Logical position stays in place", position, result.getDouble("position"), 1.0)
        } finally {
            instrumentation.runOnMainSync { webView.releaseForDestroy(); webView.destroy() }
        }
    }

    @Test
    fun selectionPreservesVisiblePositionAndResumesPagingInBothWritingDirections() {
        val webView = createWebView()
        try {
            for (vertical in listOf(false, true)) {
                loadFixture(webView, vertical)
                val before = JSONObject(evaluate(webView, """
                    ({rect:document.getElementById('tail').getBoundingClientRect().toJSON(),
                      position:hoshiReader.getPagePosition(hoshiReader.getScrollContext())})
                """.trimIndent()))
                assertTrue("Fixture is past the first page", before.getDouble("position") > 0)
                evaluate(webView, """
                    (()=>{const r=document.createRange();r.selectNodeContents(document.getElementById('tail'));
                      getSelection().removeAllRanges();getSelection().addRange(r);return true;})()
                """.trimIndent())
                composeRule.waitUntil(3000) { webView.nativeSelectionState.value }
                val selected = JSONObject(evaluate(webView, """
                    (()=>{document.getElementById('before').scrollIntoView();
                      return {rect:document.getElementById('tail').getBoundingClientRect().toJSON(),
                        position:hoshiReader.getPagePosition(hoshiReader.getScrollContext()),
                        navigation:hoshiReader.paginate('forward')};})()
                """.trimIndent()))
                assertEquals("Navigation is blocked during selection", "blocked", selected.getString("navigation"))
                for (axis in listOf("x", "y")) assertEquals("No scroll/rebound even before scroll events: $vertical $axis",
                    before.getJSONObject("rect").getDouble(axis), selected.getJSONObject("rect").getDouble(axis), 1.0)
                assertEquals(before.getDouble("position"), selected.getDouble("position"), 1.0)
                evaluate(webView, "getSelection().removeAllRanges();true")
                composeRule.waitUntil(3000) { !webView.nativeSelectionState.value }
                val cleared = JSONObject(evaluate(webView, """
                    ({rect:document.getElementById('tail').getBoundingClientRect().toJSON(),
                      position:hoshiReader.getPagePosition(hoshiReader.getScrollContext()),
                      navigation:hoshiReader.paginate('forward')})
                """.trimIndent()))
                for (axis in listOf("x", "y")) assertEquals("Deselect does not move text: $vertical $axis",
                    before.getJSONObject("rect").getDouble(axis), cleared.getJSONObject("rect").getDouble(axis), 1.0)
                assertEquals(before.getDouble("position"), cleared.getDouble("position"), 1.0)
                assertEquals("Paging resumes", "scrolled", cleared.getString("navigation"))
            }
        } finally {
            instrumentation.runOnMainSync { webView.releaseForDestroy(); webView.destroy() }
        }
    }

    private fun createWebView(): HoshiReaderWebView {
        lateinit var webView: HoshiReaderWebView
        composeRule.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                HoshiReaderWebView(context).also { webView = it; it.settings.javaScriptEnabled = true }
            })
        }
        composeRule.waitForIdle()
        return webView
    }

    private fun loadFixture(webView: HoshiReaderWebView, vertical: Boolean) {
        val assets = ReaderWebAssets.load(instrumentation.targetContext)
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.prepareForDocument("selection-$vertical")
            webView.tag = "selection-$vertical"
            val settings = ReaderSettings(verticalWriting = vertical, fontSize = 27)
            val density = webView.resources.displayMetrics.density
            val viewport = readerViewportCssLayout(settings,
                (webView.width / density).toInt(), (webView.height / density).toInt()).cssVariables()
            val styles = ReaderContentStyles.styleTag(settings = settings, readerCssTemplate = assets.readerCss)
            val script = ReaderPaginationScripts.shellScript(settings = settings, assets = assets)
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
            }
            webView.loadDataWithBaseURL(null,
                "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                    "<script>window.HoshiReaderRestore={postMessage:()=>window.testRestored=true};</script>" +
                    "$styles<style>$viewport</style>$script" +
                    "<script>${assets.selectionJs};hoshiSelection.configure({bridge:'android-reader',nativeSelectionToken:'selection-$vertical'});" +
                    "window.selectionChanges=0;window.selectionEscapedPage='';document.addEventListener('selectionchange',()=>{" +
                    "const s=getSelection();if(s.isCollapsed||!s.rangeCount)return;selectionChanges++;" +
                    "const range=s.getRangeAt(0),text=range.toString();" +
                    "if(range.intersectsNode(document.getElementById('before'))||text.length>24)selectionEscapedPage=text;" +
                    "});</script></head><body><p id='before'>${"前のページの文章。".repeat(60)}</p>" +
                    "<p style='break-before:column'>${"あいうえおかきくけこさしすせそ".repeat(3)}<ruby>濫<rt>らん</rt></ruby>" +
                    "<ruby><span><span>読</span></span><rt>どく</rt></ruby><span id='tail'>派</span>のようだった。</p>" +
                    "<p style='break-before:column'>次のページです。</p></body></html>", "text/html", "utf-8", null)
        }
        assertTrue(loaded.await(15, TimeUnit.SECONDS))
        composeRule.waitUntil(15_000) { evaluate(webView, "Boolean(window.testRestored)") == "true" }
        evaluate(webView, """
            (()=>{const r=document.createRange();r.selectNodeContents(document.getElementById('tail'));
              hoshiReader.applySasayakiCues([{id:'chapter',start:0,length:hoshiReader.countChars(document.body.textContent)}]);
              hoshiReader.scrollToRange(r);return true;})()
        """.trimIndent())
    }

    private fun gesture(x: Float, y: Float, endX: Float = x, endY: Float = y, duration: Long) {
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, px: Float, py: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, px, py, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, x, y)
        if (x == endX && y == endY) SystemClock.sleep(duration)
        else for (step in 1..40) {
            SystemClock.sleep(duration / 40)
            send(MotionEvent.ACTION_MOVE, x + (endX - x) * step / 40, y + (endY - y) * step / 40)
        }
        send(MotionEvent.ACTION_UP, endX, endY)
    }

    private fun evaluate(webView: WebView, script: String): String {
        val done = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { webView.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return result
    }
}
