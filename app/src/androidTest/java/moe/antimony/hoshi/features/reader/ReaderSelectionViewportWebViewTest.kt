package moe.antimony.hoshi.features.reader

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.SystemClock
import android.view.ActionMode
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.JavascriptInterface
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import moe.antimony.hoshi.R

/** Generated chapters only. Selection sampling deliberately never reads layout during a drag. */
@RunWith(AndroidJUnit4::class)
class ReaderSelectionViewportWebViewTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

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
            destroyFixture(webView)
        }
    }

    @Test
    fun draggingPlainTextInFragmentedParagraphRestoresNativeMenu() {
        val webView = createWebView()
        try {
            loadFixture(webView, vertical = true, plain = true)
            val tail = JSONObject(evaluate(webView, "document.getElementById('tail').getBoundingClientRect().toJSON()"))
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
            val modeField = HoshiReaderWebView::class.java.getDeclaredField("nativeSelectionActionMode")
                .apply { isAccessible = true }
            fun currentMode(): ActionMode? {
                var mode: ActionMode? = null
                instrumentation.runOnMainSync { mode = modeField.get(webView) as? ActionMode }
                return mode
            }
            composeRule.waitUntil(3000) { currentMode() != null }
            val selectedRect = JSONObject(evaluate(webView, "getSelection().getRangeAt(0).getBoundingClientRect().toJSON()"))
            val handleX = x(selectedRect.getDouble("right") - 6)
            val startY = y(selectedRect.getDouble("top") + 10)
            gesture(handleX, startY, handleX, startY - density * 120, 1200)
            assertTrue("Dragging changes the selection",
                evaluate(webView, "selectionChanges").toInt() > 3)
            composeRule.waitUntil("Dragging restores the native menu for the new selection", 3000) {
                currentMode() != null
            }
            assertNotNull(currentMode())
            assertAppActionsVisible(listOf(R.string.reader_highlight_action))
        } finally {
            destroyFixture(webView)
        }
    }

    @Test
    fun recoveredMenuPreservesExactSelectionAndVisibleAppActions() {
        assumeTrue("Mouse dropdown regression requires an Android 14+ large screen",
            Build.VERSION.SDK_INT >= 34 && instrumentation.targetContext.resources.configuration.smallestScreenWidthDp >= 600)
        val webView = createWebView()
        try {
            for (plain in listOf(true, false)) for (vertical in listOf(true, false)) {
                loadFixture(webView, vertical, plain)
                selectFixtureText(webView, fromRuby = !plain)
                val selected = evaluate(webView, "getSelection().toString()")
                assertTrue("Fixture has selected text", selected.length > 2)
                requestMenuRecovery(webView)
                composeRule.waitUntil("Recovery produces a native menu request", 5000) {
                    evaluate(webView, "recoveryMenuEvents.length").toInt() > 0
                }
                assertTrue("Recovery must not turn a touch selection into a mouse right-click",
                    evaluate(webView, "recoveryMenuEvents[0].button !== 2").toBoolean())
                assertAppActionsVisible()
                assertEquals("Recovery preserves the complete selected text", selected,
                    evaluate(webView, "getSelection().toString()"))
                assertTrue("Recovery preserves native handle endpoints", evaluate(webView, """
                    (()=>{const s=getSelection();return recoveryEndpoints.every((v,i)=>v===
                        [s.anchorNode,s.anchorOffset,s.focusNode,s.focusOffset][i]);})()
                """.trimIndent()).toBoolean())
            }
        } finally {
            destroyFixture(webView)
        }
    }

    @Test
    fun pendingMenuRecoveryStopsWhenSelectionOrDocumentChanges() {
        val webView = createWebView()
        try {
            for (changeDocument in listOf(false, true)) {
                loadFixture(webView, vertical = true, plain = true)
                selectFixtureText(webView)
                evaluate(webView, """
                    window.recoveryTouchStarted=false;
                    document.addEventListener('touchstart',()=>window.recoveryTouchStarted=true,{once:true});true
                """.trimIndent())
                requestMenuRecovery(webView)
                composeRule.waitUntil("Recovery touch has started", 3000) {
                    evaluate(webView, "recoveryTouchStarted").toBoolean()
                }
                if (changeDocument) {
                    instrumentation.runOnMainSync { webView.prepareForDocument("next-document") }
                } else {
                    evaluate(webView, "getSelection().removeAllRanges();true")
                }
                // Observe past the gesture deadline to detect a late menu or reselection.
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 300L)
                assertEquals("Cancelled recovery cannot open a menu", "0", evaluate(webView, "recoveryMenuEvents.length"))
                assertTrue("Cancelled recovery releases the native selection guard", !webView.nativeSelectionState.value)
                if (!changeDocument) assertEquals("Cancelled recovery cannot reselect text", "\"\"", evaluate(webView, "getSelection().toString()"))
            }
        } finally {
            destroyFixture(webView)
        }
    }

    @Test
    fun menuRecoveryReplyCannotOutliveNewTouch() {
        val webView = createWebView()
        val waitingForInteraction = CountDownLatch(1)
        val interactionComplete = CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                webView.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun waitForInteraction() {
                        waitingForInteraction.countDown()
                        interactionComplete.await(5, TimeUnit.SECONDS)
                    }
                }, "RecoveryProbe")
            }
            loadFixture(webView, vertical = true, plain = true)
            selectFixtureText(webView)
            val rect = JSONObject(evaluate(webView,
                "document.getElementById('tail').getBoundingClientRect().toJSON()"))
            evaluate(webView, """
                const takeRecovery=hoshiSelection.takeNativeMenuRecovery;
                hoshiSelection.takeNativeMenuRecovery=function(...args){
                    const point=takeRecovery.apply(this,args);
                    RecoveryProbe.waitForInteraction();
                    return point;
                };true
            """.trimIndent())
            requestMenuRecovery(webView)
            assertTrue("Native recovery is waiting for the JS reply", waitingForInteraction.await(3, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val density = webView.resources.displayMetrics.density
                val x = ((rect.getDouble("left") + rect.getDouble("right")) / 2 * density).toFloat()
                val y = ((rect.getDouble("top") + rect.getDouble("bottom")) / 2 * density).toFloat()
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL)) {
                    val event = MotionEvent.obtain(now, now, action, x, y, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { webView.onTouchEvent(event) } finally { event.recycle() }
                }
            }
            interactionComplete.countDown()
            evaluate(webView, "true")
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 300L)
            assertEquals("A stale JS reply cannot start another long press", "0", evaluate(webView, "recoveryMenuEvents.length"))
        } finally {
            interactionComplete.countDown()
            destroyFixture(webView)
        }
    }

    private fun selectFixtureText(webView: HoshiReaderWebView, fromRuby: Boolean = false) {
        evaluate(webView, """
            (()=>{const r=document.createRange();r.selectNodeContents(document.getElementById('tail'));
                if($fromRuby){const rubies=document.querySelectorAll('ruby');
                    r.setStart(rubies[rubies.length-1].querySelector('rt').firstChild,1);}
                getSelection().removeAllRanges();getSelection().addRange(r);return true;})()
        """.trimIndent())
        composeRule.waitUntil(3000) { webView.nativeSelectionState.value }
    }

    private fun requestMenuRecovery(webView: WebView) {
        evaluate(webView, """
            (() => {
                const s=getSelection();
                window.recoveryEndpoints=[s.anchorNode,s.anchorOffset,s.focusNode,s.focusOffset];
                window.recoveryMenuEvents=[];
                document.addEventListener('contextmenu',e=>{
                    if(e.isTrusted) recoveryMenuEvents.push({button:e.button,pointer:e.pointerType});
                },{once:true});
                hoshiSelection.recoverNativeSelectionMenu({isTrusted:true,pointerType:'touch',button:0,
                    clientX:-1,clientY:-1,preventDefault(){}});
                return true;
            })()
        """.trimIndent())
    }

    private fun assertAppActionsVisible(resources: List<Int> = listOf(R.string.reader_highlight_action, R.string.tts_replace)) {
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            for (resource in resources) {
                val title = instrumentation.targetContext.getString(resource)
                composeRule.waitUntil("Selection menu displays $title", 5000) {
                    automation.windows.any { window ->
                        window.root?.findAccessibilityNodeInfosByText(title)?.any {
                            it.isVisibleToUser && it.text?.toString() == title
                        } == true
                    }
                }
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }

    private fun destroyFixture(webView: HoshiReaderWebView) {
        try {
            val selectionCleared = CountDownLatch(1)
            instrumentation.runOnMainSync {
                webView.evaluateJavascript("getSelection().removeAllRanges();true") { selectionCleared.countDown() }
            }
            selectionCleared.await(1, TimeUnit.SECONDS)
        } finally {
            instrumentation.runOnMainSync {
                webView.releaseForDestroy()
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
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
            destroyFixture(webView)
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

    private fun loadFixture(webView: HoshiReaderWebView, vertical: Boolean, plain: Boolean = false) {
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
                    "});</script></head><body>" + (if (plain)
                        "<p id='before'></p><p>${"あいうえおかきくけこさしすせそ".repeat(45)}<span id='tail'>時間</span>のようだった。</p>"
                    else "<p id='before'>${"前のページの文章。".repeat(60)}</p>" +
                    "<p style='break-before:column'>${"あいうえおかきくけこさしすせそ".repeat(3)}<ruby>濫<rt>らん</rt></ruby>" +
                    "<ruby><span><span>読</span></span><rt>どく</rt></ruby><span id='tail'>派</span>のようだった。</p>" +
                    "<p style='break-before:column'>次のページです。</p>") + "</body></html>", "text/html", "utf-8", null)
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
