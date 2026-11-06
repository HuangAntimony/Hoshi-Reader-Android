package moe.antimony.hoshi.features.reader

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import moe.antimony.hoshi.features.dictionary.LookupPopupAssets
import moe.antimony.hoshi.features.dictionary.LookupPopupHtml
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Generated documents and a disposable WebView; never opens books or saved settings. */
@RunWith(AndroidJUnit4::class)
class LookupPopupGestureWebViewTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun nativeSwipesPageAndDismissWithoutTouchEmulation() = withWebView { web ->
        load(web, reducedMotion = true)
        swipe(web, horizontal = false, parentTouch = false)
        assertPageStep(web)
        swipe(web, horizontal = true, parentTouch = false)
        compose.waitUntil(3_000) { evaluate(web, "fixtureDismissals").toInt() == 1 }

        load(web, reducedMotion = false)
        swipe(web, horizontal = false, parentTouch = false)
        compose.waitUntil(3_000) { state(web).getDouble("top") > 0 }
        val ordinary = state(web)
        assertTrue("Ordinary scrolling stays continuous: $ordinary",
            ordinary.getDouble("top") < ordinary.getDouble("height") * 0.8)
        swipe(web, horizontal = true, parentTouch = false)
        compose.waitUntil(3_000) { evaluate(web, "fixtureDismissals").toInt() == 1 }
    }

    @Test fun retainedParentTouchDoesNotBreakRootOrChildPopup() = withWebView { web ->
        for (childPopup in listOf(false, true)) {
            load(web, reducedMotion = true, childPopup = childPopup)
            swipe(web, horizontal = false, parentTouch = true)
            assertPageStep(web)
            val events = state(web)
            assertEquals("Touch Events stay in the parent: $events", 0, events.getInt("touchStarts"))
            assertTrue("The popup still receives a non-primary pointer: $events",
                events.getBoolean("nonPrimaryPointer"))
            swipe(web, horizontal = true, parentTouch = true)
            compose.waitUntil(3_000) { evaluate(web, "fixtureDismissals").toInt() == 1 }
        }
    }

    private fun withWebView(block: (WebView) -> Unit) {
        lateinit var web: WebView
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).apply { settings.javaScriptEnabled = true; web = this }
            })
        }
        compose.waitForIdle()
        try { block(web) } finally {
            instrumentation.runOnMainSync {
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            }
        }
    }

    private fun load(web: WebView, reducedMotion: Boolean, childPopup: Boolean = false) {
        val popup = LookupPopupAssets.load(instrumentation.targetContext)
        val html = LookupPopupHtml.renderIframeDocument(
            swipeToDismiss = true, swipeThreshold = 30,
            reducedMotionScrolling = reducedMotion, reducedMotionScrollPercent = 90,
        ).replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        // Child popups are sibling iframes above the root popup in the shared host.
        val root = if (childPopup) """<iframe style="position:fixed;left:15%;top:24px;width:70%;height:65%;border:0" srcdoc="$html"></iframe>""" else ""
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    lookupPopupAssetResponse(request.url.lastPathSegment.orEmpty(), popup)?.let {
                        WebResourceResponse(it.mimeType, "UTF-8", it.content.byteInputStream())
                    }
                override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
            }
            web.loadDataWithBaseURL("https://appassets.androidplatform.net/fixture/", """
                <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
                <style>html,body{margin:0;overflow:hidden}</style></head><body>$root
                <iframe id="fixture" style="position:fixed;left:25%;top:48px;width:70%;height:65%;border:0" srcdoc="$html"></iframe>
                <script>window.fixtureDismissals=0;document.addEventListener('touchstart',()=>{}, {passive:true});</script>
                </body></html>
            """.trimIndent(), "text/html", "UTF-8", null)
        }
        assertTrue("Fixture loaded", loaded.await(10, TimeUnit.SECONDS))
        compose.waitUntil(10_000) {
            evaluate(web, "Boolean(document.getElementById('fixture').contentWindow.hoshiPopupGeometry)") == "true"
        }
        evaluate(web, """
            (()=>{const w=document.getElementById('fixture').contentWindow,d=w.document;
                const content=d.createElement('div');content.style.height='4000px';d.body.appendChild(content);
                w.fixtureTouchStarts=0;w.fixtureNonPrimaryPointer=false;
                d.addEventListener('touchstart',()=>w.fixtureTouchStarts++,{passive:true});
                d.addEventListener('pointerdown',e=>{if(!e.isPrimary)w.fixtureNonPrimaryPointer=true;},{passive:true});
                w.webkit.messageHandlers.swipeDismiss.postMessage=()=>{fixtureDismissals++;document.getElementById('fixture').remove();};
                return true;})()
        """.trimIndent())
        val visual = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { visual.countDown() }
            })
        }
        assertTrue("Fixture visible", visual.await(10, TimeUnit.SECONDS))
        compose.waitForIdle()
    }

    private fun state(web: WebView) = JSONObject(evaluate(web, """
        (()=>{const w=document.getElementById('fixture').contentWindow;return {
            top:w.hoshiPopupGeometry.scrollTop(),height:w.hoshiPopupGeometry.viewportHeight(),
            touchStarts:w.fixtureTouchStarts,nonPrimaryPointer:w.fixtureNonPrimaryPointer};})()
    """.trimIndent()))

    private fun assertPageStep(web: WebView) {
        compose.waitUntil(3_000) { state(web).getDouble("top") > 0 }
        val state = state(web)
        assertEquals("Scrolls by 90% of the iframe viewport: $state",
            state.getDouble("height") * 0.9, state.getDouble("top"), 1.0)
    }

    private data class Point(val id: Int, val x: Float, val y: Float)

    private fun swipe(web: WebView, horizontal: Boolean, parentTouch: Boolean) {
        val rect = JSONObject(evaluate(web, "document.getElementById('fixture').getBoundingClientRect().toJSON()"))
        val scale = web.width / evaluate(web, "innerWidth").toFloat()
        val x = ((rect.getDouble("left") + rect.getDouble("width") * 0.65) * scale).toFloat()
        val y = ((rect.getDouble("top") + rect.getDouble("height") * 0.65) * scale).toFloat()
        val downTime = SystemClock.uptimeMillis()
        val parent = Point(0, 2f, 2f)
        val id = if (parentTouch) 1 else 0
        var finger = Point(id, x, y)
        var active = emptyList<Point>()
        val location = IntArray(2)
        instrumentation.runOnMainSync { web.getLocationOnScreen(location) }
        fun send(action: Int, points: List<Point>) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, points.size,
                points.map { MotionEvent.PointerProperties().apply {
                    this.id = it.id; toolType = MotionEvent.TOOL_TYPE_FINGER
                } }.toTypedArray(),
                points.map { MotionEvent.PointerCoords().apply { this.x = it.x; this.y = it.y; pressure = 1f; size = 1f } }.toTypedArray(),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            if (parentTouch) {
                instrumentation.runOnMainSync { try { web.dispatchTouchEvent(event) } finally { event.recycle() } }
            } else {
                event.offsetLocation(location[0].toFloat(), location[1].toFloat())
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
        }
        try {
            active = if (parentTouch) listOf(parent) else listOf(finger)
            send(MotionEvent.ACTION_DOWN, active)
            if (parentTouch) {
                active = listOf(parent, finger)
                send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), active)
            }
            for (step in 1..8) {
                finger = Point(id, x - if (horizontal) 100 * scale * step / 8 else 0f,
                    y - if (horizontal) 0f else 100 * scale * step / 8)
                active = if (parentTouch) listOf(parent, finger) else listOf(finger)
                send(MotionEvent.ACTION_MOVE, active)
                SystemClock.sleep(20)
            }
            if (parentTouch) {
                send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), active)
                active = listOf(parent)
            } else {
                send(MotionEvent.ACTION_UP, active)
                active = emptyList()
            }
        } finally {
            if (active.isNotEmpty()) send(MotionEvent.ACTION_CANCEL, active)
        }
    }

    private fun evaluate(web: WebView, script: String): String {
        val done = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue("JavaScript completed", done.await(10, TimeUnit.SECONDS))
        return result
    }
}
