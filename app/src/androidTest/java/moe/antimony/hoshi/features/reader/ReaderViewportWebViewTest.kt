package moe.antimony.hoshi.features.reader

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

/** Generated content only; never opens books or writes preferences. */
@RunWith(AndroidJUnit4::class)
class ReaderViewportWebViewTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun verticalPaginationUsesVisibleHeightAndReachesLastText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = ReaderWebAssets.load(instrumentation.targetContext)
        lateinit var webView: WebView
        composeRule.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).also { webView = it; it.settings.javaScriptEnabled = true }
            })
        }
        composeRule.waitForIdle()
        try {
            for (padding in listOf(0, 14)) for (font in listOf(22, 36)) {
                val label = "padding=$padding font=$font"
                val settings = ReaderSettings(verticalWriting = true, verticalPadding = padding, fontSize = font)
                val loaded = CountDownLatch(1)
                val styles = ReaderContentStyles.styleTag(settings = settings, readerCssTemplate = assets.readerCss)
                val script = ReaderPaginationScripts.shellScript(settings = settings, assets = assets)
                val body = "<p>「<ruby>読書<rt>どくしょ</rt></ruby>の時間です。」</p>".repeat(120) + "<p><span id='last'>終</span></p>"
                instrumentation.runOnMainSync {
                    val density = webView.resources.displayMetrics.density
                    val viewport = readerViewportCssLayout(settings,
                        (webView.width / density).toInt(), (webView.height / density).toInt()).cssVariables()
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                    }
                    webView.loadDataWithBaseURL(null,
                        "<!doctype html><html><head><script>window.HoshiReaderRestore={postMessage:()=>window.testRestored=true};</script>$styles<style>$viewport</style>$script</head><body>$body</body></html>",
                        "text/html", "utf-8", null)
                }
                assertTrue(label, loaded.await(15, TimeUnit.SECONDS))
                composeRule.waitUntil(15_000) { evaluate(webView, "Boolean(window.testRestored)") == "true" }
                val geometry = JSONObject(evaluate(webView, """
                    (() => { const s = getComputedStyle(document.body); return {
                        viewport: innerHeight, page: window.hoshiReader.getScrollContext().pageSize,
                        height: parseFloat(s.height), top: parseFloat(s.paddingTop), bottom: parseFloat(s.paddingBottom)
                    }; })()
                """.trimIndent()))
                val height = geometry.getDouble("viewport")
                assertEquals(label, height, geometry.getDouble("page"), 1.0)
                assertEquals(label, height, geometry.getDouble("height"), 1.0)
                assertEquals(label, height * padding / 200.0, geometry.getDouble("top"), 1.0)
                assertEquals(label, height * padding / 200.0, geometry.getDouble("bottom"), 1.0)
                val turns = JSONObject(evaluate(webView, """
                    (() => { const r = window.hoshiReader; let count = 0;
                        while (count < 500 && r.paginate('forward') === 'scrolled') count++;
                        const rect = document.getElementById('last').getBoundingClientRect();
                        return {count, position: r.getPagePosition(r.getScrollContext()),
                            visible: rect.bottom > 0 && rect.top < innerHeight && rect.right > 0 && rect.left < innerWidth};
                    })()
                """.trimIndent()))
                assertTrue(label, turns.getInt("count") in 1..499)
                assertTrue("$label last text", turns.getBoolean("visible"))
                val lastPosition = turns.getDouble("position")
                evaluate(webView, "window.testRestored=false; window.hoshiReader.restoreProgress(0); true")
                composeRule.waitUntil(15_000) { evaluate(webView, "Boolean(window.testRestored)") == "true" }
                evaluate(webView, "window.testRestored=false; window.hoshiReader.restoreProgress(1); true")
                composeRule.waitUntil(15_000) { evaluate(webView, "Boolean(window.testRestored)") == "true" }
                assertEquals("$label restore", lastPosition,
                    evaluate(webView, "window.hoshiReader.getPagePosition(window.hoshiReader.getScrollContext())").toDouble(), 1.0)
            }
        } finally { instrumentation.runOnMainSync { webView.destroy() } }
    }

    private fun evaluate(webView: WebView, script: String): String {
        val complete = CountDownLatch(1)
        var result = "null"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript(script) { result = it; complete.countDown() }
        }
        assertTrue("JavaScript callback", complete.await(10, TimeUnit.SECONDS))
        return result
    }
}
