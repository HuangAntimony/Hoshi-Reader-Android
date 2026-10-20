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
    fun initialLongPressKeepsItsNativeMenuWhenCaretProbeDisagrees() {
        val webView = createWebView()
        try {
            loadFixture(webView, vertical = true, plain = true)
            evaluate(webView, """
                window.initialMenuEvents=[];
                const recover=hoshiSelection.recoverNativeSelectionMenu;
                hoshiSelection.recoverNativeSelectionMenu=function(e){return recover.call(this,{
                    isTrusted:e.isTrusted,pointerType:e.pointerType,button:e.button,clientX:-1,clientY:-1,
                    preventDefault(){e.preventDefault();}});};
                document.addEventListener('contextmenu',e=>{
                    if(e.isTrusted)initialMenuEvents.push({pointer:e.pointerType,prevented:e.defaultPrevented});
                });true
            """.trimIndent())
            val rect = JSONObject(evaluate(webView,
                "document.getElementById('tail').getBoundingClientRect().toJSON()"))
            val location = IntArray(2)
            var density = 1f
            instrumentation.runOnMainSync {
                webView.getLocationOnScreen(location)
                density = webView.resources.displayMetrics.density
            }
            gesture(location[0] + ((rect.getDouble("left") + rect.getDouble("right")) / 2 * density).toFloat(),
                location[1] + ((rect.getDouble("top") + rect.getDouble("bottom")) / 2 * density).toFloat(), duration = 900)
            assertEquals("The original touch menu must pass through: ${evaluate(webView, "JSON.stringify(initialMenuEvents)")}", "true",
                evaluate(webView, "initialMenuEvents.some(e=>e.pointer==='touch'&&!e.prevented)"))
            assertAppActionsVisible()
        } finally {
            destroyFixture(webView)
        }
    }

    @Test
    fun shortSelectionsRecoverThroughContentAfterDraggingEitherHandle() {
        val webView = createWebView()
        try {
            loadFixture(webView, vertical = true, plain = true, fontSize = 18)
            val location = IntArray(2)
            var density = 1f
            instrumentation.runOnMainSync {
                webView.getLocationOnScreen(location)
                density = webView.resources.displayMetrics.density
            }
            fun x(value: Double) = location[0] + value.toFloat() * density
            fun y(value: Double) = location[1] + value.toFloat() * density
            val initial = JSONObject(evaluate(webView, """
                (()=>{window.shortCaret=offset=>{
                        const w=document.createTreeWalker(document.getElementById('tail'),NodeFilter.SHOW_TEXT);
                        let n;while(n=w.nextNode()){
                            if(!n.length)continue;
                            if(offset<=n.length){const r=document.createRange();r.setStart(n,offset);r.collapse(true);return r;}
                            offset-=n.length;
                        }
                        throw new Error('Missing short-selection fixture offset');
                    };
                    const r=shortCaret(0),end=shortCaret(1);r.setEnd(end.startContainer,end.startOffset);
                    return r.getBoundingClientRect().toJSON();})()
            """.trimIndent()))
            gesture(x((initial.getDouble("left") + initial.getDouble("right")) / 2),
                y((initial.getDouble("top") + initial.getDouble("bottom")) / 2), duration = 900)
            composeRule.waitUntil(3000) { webView.nativeSelectionState.value }
            evaluate(webView, """
                const recover=hoshiSelection.recoverNativeSelectionMenu;
                hoshiSelection.recoverNativeSelectionMenu=function(e){return recover.call(this,{
                    isTrusted:e.isTrusted,pointerType:e.pointerType,button:e.button,clientX:-1,clientY:-1,
                    preventDefault(){e.preventDefault();}});};
                document.addEventListener('contextmenu',e=>{
                    if(!e.isTrusted)return;
                    const s=getSelection();
                    if(e.defaultPrevented&&!window.shortEndpoints)
                        window.shortEndpoints=[s.anchorNode,s.anchorOffset,s.focusNode,s.focusOffset];
                    shortMenuEvents.push({pointer:e.pointerType,prevented:e.defaultPrevented});
                });true
            """.trimIndent())
            for ((startHandle, offset) in listOf(false to 1, false to 2, true to 1)) {
                evaluate(webView, "window.shortMenuEvents=[];window.shortEndpoints=null;true")
                val points = JSONObject(evaluate(webView, """
                    (()=>{const r=getSelection().getRangeAt(0).cloneRange();r.collapse($startHandle);
                        return {from:r.getBoundingClientRect().toJSON(),to:shortCaret($offset).getBoundingClientRect().toJSON()};})()
                """.trimIndent()))
                val from = points.getJSONObject("from")
                val to = points.getJSONObject("to")
                fun handleX(rect: JSONObject) = if (startHandle) rect.getDouble("right") - 6 else rect.getDouble("left") + 6
                gesture(x(handleX(from)), y(from.getDouble("top") + 10),
                    x(handleX(to)), y(to.getDouble("top") + 10), 700)
                composeRule.waitUntil("Short selections recover through content instead of pressing a handle", 4000) {
                    evaluate(webView, "shortMenuEvents.some(e=>e.pointer==='touch'&&!e.prevented)").toBoolean()
                }
                assertAppActionsVisible()
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() * 2 + 300L)
                assertTrue("Recovery preserves the exact native endpoints and selection direction", evaluate(webView, """
                    (()=>{const s=getSelection();return shortEndpoints&&shortEndpoints.every((v,i)=>v===
                        [s.anchorNode,s.anchorOffset,s.focusNode,s.focusOffset][i]);})()
                """.trimIndent()).toBoolean())
                val expected = if (startHandle) "間" else if (offset == 1) "時" else "時間"
                assertEquals("A real handle produces the expected short range", "\"$expected\"",
                    evaluate(webView, "getSelection().toString()"))
                assertAppActionsVisible()
            }
        } finally {
            destroyFixture(webView)
        }
    }

    @Test
    fun reversedCrossParagraphRecoverySettlesAndKeepsBothHandlesUsable() {
        val webView = createWebView()
        val menuActions = listOf(R.string.reader_highlight_action)
        try {
            loadFixture(webView, vertical = true, crossParagraph = true)
            val location = IntArray(2)
            var density = 1f
            instrumentation.runOnMainSync {
                webView.getLocationOnScreen(location)
                density = webView.resources.displayMetrics.density
            }
            val tail = JSONObject(evaluate(webView, "document.getElementById('tail').getBoundingClientRect().toJSON()"))
            val initial = JSONObject(evaluate(webView, """
                (()=>{const r=document.createRange(),n=document.getElementById('reverse-end').firstChild;
                    r.setStart(n,3);r.setEnd(n,4);return r.getBoundingClientRect().toJSON();})()
            """.trimIndent()))
            // Build the reverse range with native gestures: replacing a selection in
            // JavaScript can hide its native handles independently of menu recovery.
            gesture(location[0] + (initial.getDouble("left") + initial.getDouble("width") / 2).toFloat() * density,
                location[1] + (initial.getDouble("top") + initial.getDouble("height") / 2).toFloat() * density, duration = 900)
            composeRule.waitUntil(3000) { webView.nativeSelectionState.value }
            fun dragHandle(start: Boolean, targetX: Double, targetY: Double) {
                val rect = JSONObject(evaluate(webView, """
                    (()=>{const rects=[...getSelection().getRangeAt(0).getClientRects()].filter(r=>r.width&&r.height);
                        return rects[$start?0:rects.length-1].toJSON();})()
                """.trimIndent()))
                val x = if (start) rect.getDouble("right") - 6 else rect.getDouble("left") + 6
                val y = if (start) rect.getDouble("top") + 10 else rect.getDouble("bottom") + 10
                gesture(location[0] + x.toFloat() * density, location[1] + y.toFloat() * density,
                    location[0] + targetX.toFloat() * density, location[1] + targetY.toFloat() * density, 700)
            }
            dragHandle(false, tail.getDouble("left") + 6, tail.getDouble("bottom") + 10)
            val first = JSONObject(evaluate(webView, """
                (()=>{const r=document.createRange(),n=document.getElementById('reverse-start').firstChild;
                    r.setStart(n,3);r.setEnd(n,4);return r.getBoundingClientRect().toJSON();})()
            """.trimIndent()))
            dragHandle(true, first.getDouble("right") - 6, first.getDouble("top") + 10)
            assertTrue("Fixture is a native backward selection across paragraphs", evaluate(webView, """
                (()=>{const s=getSelection(),r=s.getRangeAt(0);return s.anchorNode===r.endContainer&&
                    s.anchorOffset===r.endOffset&&r.startContainer.parentElement.closest('#reverse-start')!==null&&
                    r.endContainer.parentElement.closest('#reverse-end')!==null;})()
            """.trimIndent()).toBoolean())
            val selected = evaluate(webView, "getSelection().toString()")
            // Reproduce Chromium repeating an invalid anchor for the genuine recovery
            // contextmenu; generated chapter metrics need not match the user's font.
            evaluate(webView, """
                const recover=hoshiSelection.recoverNativeSelectionMenu;
                hoshiSelection.recoverNativeSelectionMenu=function(e){
                    const result=recover.call(this,{
                        isTrusted:e.isTrusted,pointerType:e.pointerType,button:e.button,clientX:-1,clientY:-1,
                        preventDefault(){e.preventDefault();}});
                    if(this.nativeMenuRecovery)document.dispatchEvent(new Event('selectionchange'));
                    return result;
                };true
            """.trimIndent())
            requestMenuRecovery(webView)
            assertAppActionsVisible(menuActions)
            // A repeated replay starts about once per long-press timeout and hides a handle.
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() * 2 + 300L)
            assertAppActionsVisible(menuActions)
            assertEquals("One gesture produces one native menu", "1", evaluate(webView, "recoveryMenuEvents.length"))
            assertEquals("Recovery preserves the reverse cross-paragraph range", selected, evaluate(webView, "getSelection().toString()"))
            assertTrue("Recovery preserves direction and endpoints", evaluate(webView, """
                (()=>{const s=getSelection();return recoveryEndpoints.every((v,i)=>v===
                    [s.anchorNode,s.anchorOffset,s.focusNode,s.focusOffset][i]);})()
            """.trimIndent()).toBoolean())
            for (startHandle in listOf(true, false)) {
                val endpoint = JSONObject(evaluate(webView, """
                    (()=>{const range=getSelection().getRangeAt(0);
                        window.beforeHandleDrag=[range.startContainer,range.startOffset,range.endContainer,range.endOffset];
                        const r=document.createRange();r.setStart($startHandle?range.startContainer:range.endContainer,
                            $startHandle?range.startOffset:range.endOffset);r.collapse(true);return r.getBoundingClientRect().toJSON();})()
                """.trimIndent()))
                val handleX = location[0] + (if (startHandle) endpoint.getDouble("right") - 6 else endpoint.getDouble("left") + 6).toFloat() * density
                val handleY = location[1] + (endpoint.getDouble("top") + 10).toFloat() * density
                gesture(handleX, handleY, handleX, handleY + (if (startHandle) 27 else -27) * density, 700)
                assertTrue("The ${if (startHandle) "start" else "end"} handle can still be dragged", evaluate(webView, """
                    (()=>{const s=getSelection(),r=s.getRangeAt(0),now=[r.startContainer,r.startOffset,r.endContainer,r.endOffset],
                        moved=$startHandle?0:2,still=$startHandle?2:0;
                        return !s.isCollapsed&&(now[moved]!==beforeHandleDrag[moved]||now[moved+1]!==beforeHandleDrag[moved+1])&&
                            now[still]===beforeHandleDrag[still]&&now[still+1]===beforeHandleDrag[still+1];})()
                """.trimIndent()).toBoolean())
                assertAppActionsVisible(menuActions)
            }
        } finally {
            destroyFixture(webView)
        }
    }

    @Test
    fun draggingStartToRubyBoundaryRecoversThroughSelectedTextInsteadOfHandle() {
        val webView = createWebView()
        try {
            loadFixture(webView, vertical = true, rubyBoundary = true)
            evaluate(webView, """
                (()=>{let n=document.getElementById('ruby-body').firstChild;
                    while(n.length>1)n=n.splitText(1);
                    window.boundaryMenuEvents=[];
                    document.addEventListener('contextmenu',e=>{
                        if(e.isTrusted)boundaryMenuEvents.push({pointer:e.pointerType,prevented:e.defaultPrevented});
                    });return true;})()
            """.trimIndent())
            val location = IntArray(2)
            var density = 1f
            instrumentation.runOnMainSync {
                webView.getLocationOnScreen(location)
                density = webView.resources.displayMetrics.density
            }
            fun x(value: Double) = location[0] + value.toFloat() * density
            fun y(value: Double) = location[1] + value.toFloat() * density
            val tail = JSONObject(evaluate(webView, "document.getElementById('tail').getBoundingClientRect().toJSON()"))
            gesture(x(tail.getDouble("left") + tail.getDouble("width") / 2),
                y(tail.getDouble("top") + tail.getDouble("height") / 2), duration = 900)
            composeRule.waitUntil(3000) { webView.nativeSelectionState.value }
            val start = JSONObject(evaluate(webView, """
                (()=>{const r=getSelection().getRangeAt(0).cloneRange();r.collapse(true);
                    return r.getBoundingClientRect().toJSON();})()
            """.trimIndent()))
            val target = JSONObject(evaluate(webView, """
                (()=>{const r=document.createRange();r.selectNodeContents(document.getElementById('ruby-body').firstChild);
                    return r.getBoundingClientRect().toJSON();})()
            """.trimIndent()))
            evaluate(webView, "boundaryMenuEvents.length=0;true")
            gesture(x(start.getDouble("right") - 6), y(start.getDouble("top") + 10),
                x(target.getDouble("right") - 6), y(target.getDouble("top") + 10), 900)
            assertEquals("Dragging starts immediately after ruby and retains the native end",
                "\"は大学を１年目でやめてしまったので大学時代の知り合いは少なく、大学で知り合った人\"",
                evaluate(webView, "getSelection().toString()"))
            assertAppActionsVisible(listOf(R.string.reader_highlight_action))
            assertTrue("Recovery reaches selected content as a touch, rather than pressing a handle again",
                evaluate(webView, "boundaryMenuEvents.some(e=>e.pointer==='touch'&&!e.prevented)").toBoolean())
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() * 2 + 300L)
            assertAppActionsVisible(listOf(R.string.reader_highlight_action))
            assertTrue("Recovery retains both exact range endpoints at the ruby boundary", evaluate(webView, """
                (()=>{const r=getSelection().getRangeAt(0);
                    return r.startContainer===document.getElementById('ruby-body').firstChild&&r.startOffset===0&&
                    r.endContainer===document.getElementById('tail').firstChild&&r.endOffset===1;})()
            """.trimIndent()).toBoolean())
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
                });
                hoshiSelection.recoverNativeSelectionMenu({isTrusted:true,pointerType:'mouse',button:-1,
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

    @Test
    fun continuousNativeHandleAutoScrollsTheInsetBodyInBothWritingDirections() {
        val webView = createWebView()
        try {
            for (vertical in listOf(true, false)) {
                val settings = ReaderSettings(viewMode = ReaderViewMode.Continuous, verticalWriting = vertical,
                    horizontalPadding = 30, verticalPadding = 30, fontSize = 27)
                val assets = ReaderWebAssets.load(instrumentation.targetContext)
                val loaded = CountDownLatch(1)
                var chapters = 0
                instrumentation.runOnMainSync {
                    webView.prepareForDocument("continuous-$vertical")
                    val listener = ContinuousScrollTouchListener(settings,
                        shouldIgnoreReaderGesture = { webView.isNativeSelectionActive() },
                        onTap = { _, _ -> }, onScrollGesture = {},
                        onNextChapter = { chapters++; true }, onPreviousChapter = { chapters--; true })
                    webView.bindReaderTouchListener(settings) { listener }
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                    }
                    val styles = ReaderContentStyles.styleTag(settings = settings, readerCssTemplate = assets.readerCss)
                    val script = ReaderPaginationScripts.shellScript(settings = settings, assets = assets)
                    webView.loadDataWithBaseURL(null, """
                        <!doctype html><html><head>$styles$script
                        <script>window.HoshiReaderRestore={postMessage:()=>window.testRestored=true};
                        ${assets.selectionJs}
                        hoshiSelection.configure({bridge:'android-reader',nativeSelectionToken:'continuous-$vertical'});
                        </script></head><body><p>${"読書の時間です。文章を読み続けます。".repeat(600)}</p></body></html>
                    """.trimIndent(), "text/html", "utf-8", null)
                }
                assertTrue(loaded.await(15, TimeUnit.SECONDS))
                composeRule.waitUntil(15_000) { evaluate(webView, "Boolean(window.testRestored)") == "true" }
                evaluate(webView, "hoshiReader.paginate('forward');hoshiReader.paginate('forward');true")
                val bounds = JSONObject(evaluate(webView, "hoshiReader.contentViewport().toJSON()"))
                val location = IntArray(2)
                var density = 1f
                instrumentation.runOnMainSync {
                    webView.getLocationOnScreen(location)
                    density = webView.resources.displayMetrics.density
                }
                fun x(value: Double) = location[0] + value.toFloat() * density
                fun y(value: Double) = location[1] + value.toFloat() * density
                val hit = JSONObject(evaluate(webView, """
                    (()=>{const b=hoshiReader.contentViewport(),r=document.caretRangeFromPoint(
                        (b.left+b.right)/2,(b.top+b.bottom)/2);
                        r.setEnd(r.startContainer,r.startOffset+1);return r.getBoundingClientRect().toJSON();})()
                """.trimIndent()))
                gesture(x((hit.getDouble("left") + hit.getDouble("right")) / 2),
                    y((hit.getDouble("top") + hit.getDouble("bottom")) / 2), duration = 900)
                composeRule.waitUntil(3000) { webView.nativeSelectionState.value }
                val before = evaluate(webView, "hoshiReader.sasayakiScrollPosition()").toDouble()
                val endpoint = JSONObject(evaluate(webView, """
                    (()=>{const rects=[...getSelection().getRangeAt(0).getClientRects()].filter(r=>r.width&&r.height);
                        return rects[$vertical?rects.length-1:0].toJSON();})()
                """.trimIndent()))
                // Vertical end handle moves forward; horizontal start handle moves backward.
                // Start mid-chapter so either edge has content available for native auto-scroll.
                gesture(x(if (vertical) endpoint.getDouble("left") + 6 else endpoint.getDouble("left") - 6),
                    y(endpoint.getDouble("bottom") + 10),
                    x(if (vertical) 5.0 else (bounds.getDouble("left") + bounds.getDouble("right")) / 2),
                    y(if (vertical) (bounds.getDouble("top") + bounds.getDouble("bottom")) / 2 else 5.0),
                    duration = 1200, holdMillis = 1000)
                val after = evaluate(webView, "hoshiReader.sasayakiScrollPosition()").toDouble()
                assertTrue("Native handle scrolls the continuous body: vertical=$vertical before=$before after=$after", kotlin.math.abs(after - before) > 1)
                assertTrue("Native selection survives scrolling", evaluate(webView, "!getSelection().isCollapsed && getSelection().toString().length>0").toBoolean())
                assertEquals("Handle drag never changes chapter", 0, chapters)
                assertEquals("Window stays still", "0", evaluate(webView, "Math.abs(scrollX)+Math.abs(scrollY)"))
                val menu = JSONObject(evaluate(webView, "hoshiSelection.nativeSelectionMenuRect()"))
                assertTrue("Menu remains inside the body", menu.getDouble("left") >= bounds.getDouble("left") - 1 &&
                    menu.getDouble("right") <= bounds.getDouble("right") + 1 && menu.getDouble("top") >= bounds.getDouble("top") - 1 &&
                    menu.getDouble("bottom") <= bounds.getDouble("bottom") + 1)
                evaluate(webView, "getSelection().removeAllRanges();true")
                composeRule.waitUntil(3000) { !webView.nativeSelectionState.value }
            }
        } finally { destroyFixture(webView) }
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

    private fun loadFixture(webView: HoshiReaderWebView, vertical: Boolean, plain: Boolean = false, crossParagraph: Boolean = false, rubyBoundary: Boolean = false, fontSize: Int = 27) {
        val assets = ReaderWebAssets.load(instrumentation.targetContext)
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.prepareForDocument("selection-$vertical")
            webView.tag = "selection-$vertical"
            val settings = ReaderSettings(verticalWriting = vertical, fontSize = fontSize)
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
                    "});</script></head><body>" + (if (rubyBoundary)
                        "<p id='before'></p><p><ruby>月<rt>つき</rt></ruby>" +
                        "<span id='ruby-body'>は大学を１年目でやめてしまったので大学時代の知り合いは少なく、大学で知り合った</span>" +
                        "<span id='tail'>人</span>です。</p>"
                    else if (crossParagraph)
                        "<p id='before'></p><p id='reverse-start'>あいう学で知り合った人間で現在も付き合いがあるのは一人だけだった。</p>" +
                        "<p id='reverse-end'>彼女の名前は<ruby>白川京<rt>しらかわみやこ</rt></ruby><span id='tail'>。</span></p>"
                    else if (plain)
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

    private fun gesture(x: Float, y: Float, endX: Float = x, endY: Float = y, duration: Long, holdMillis: Long = 0) {
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, px: Float, py: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
                arrayOf(MotionEvent.PointerCoords().apply {
                    this.x = px; this.y = py
                    pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0f else 1f
                }),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, x, y)
        if (x == endX && y == endY) SystemClock.sleep(duration)
        else for (step in 1..40) {
            SystemClock.sleep(duration / 40)
            send(MotionEvent.ACTION_MOVE, x + (endX - x) * step / 40, y + (endY - y) * step / 40)
        }
        if (holdMillis > 0) SystemClock.sleep(holdMillis)
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
