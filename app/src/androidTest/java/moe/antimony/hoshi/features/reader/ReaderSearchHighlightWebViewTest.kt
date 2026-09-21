package moe.antimony.hoshi.features.reader

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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

/** Uses generated in-memory content only; never opens a book or changes preferences. */
@RunWith(AndroidJUnit4::class)
class ReaderSearchHighlightWebViewTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun searchHighlightPaintsAndClearsInEveryModeAndWritingDirection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = ReaderWebAssets.load(instrumentation.targetContext)
        lateinit var webView: WebView
        composeRule.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).also {
                    webView = it
                    it.settings.javaScriptEnabled = true
                }
            })
        }
        composeRule.waitForIdle()
        try {
            for (mode in ReaderViewMode.entries) for (vertical in listOf(false, true)) {
                val description = "$mode vertical=$vertical"
                val settings = ReaderSettings(viewMode = mode, verticalWriting = vertical, visualNovelRevealSpeed = 0)
                val loaded = CountDownLatch(1)
                val styles = ReaderContentStyles.styleTag(settings = settings, readerCssTemplate = assets.readerCss)
                val script = ReaderPaginationScripts.shellScript(settings = settings, assets = assets)
                instrumentation.runOnMainSync {
                    val density = webView.resources.displayMetrics.density
                    val viewport = readerViewportCssLayout(settings,
                        (webView.width / density).toInt(), (webView.height / density).toInt()).cssVariables()
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                    }
                    webView.loadDataWithBaseURL(null,
                        "<!doctype html><html><head><script>window.HoshiReaderRestore={postMessage:()=>window.testRestored=true};</script>$styles<style>$viewport</style>$script</head>" +
                            "<body><p>前「𠮟 、<ruby>猫<rt>ねこ</rt></ruby>。」後</p></body></html>",
                        "text/html", "utf-8", null)
                }
                assertTrue(description, loaded.await(15, TimeUnit.SECONDS))
                composeRule.waitUntil(15_000) {
                    evaluate(webView, "Boolean(window.testRestored)") == "true"
                }
                // Wait for production restore (including the VN clone and font layout).
                evaluate(webView, "window.hoshiReader.buildNodeOffsets(); true")
                val baseline = bluePixelCount()
                val result = JSONObject(evaluate(webView, """
                    (() => {
                        window.hoshiHighlights.showSearchHighlight(1, 2);
                        const ranges = Array.from(CSS.highlights.get('hoshi-search') || []);
                        return { text: ranges.map(r => r.toString()).join(''),
                            saved: window.hoshiHighlights.highlights.size };
                    })()
                """.trimIndent()))
                assertEquals(description, "𠮟 、猫", result.getString("text"))
                assertEquals(description, 0, result.getInt("saved"))
                val afterRemoval = JSONObject(evaluate(webView, """
                    (() => {
                        const h = window.hoshiHighlights;
                        h.applyHighlights([{id:'saved', color:'yellow', offset:2, text:'𠮟 、猫'}]);
                        h.removeHighlight('saved');
                        return {text: Array.from(CSS.highlights.get('hoshi-search') || [], r => r.toString()).join('')};
                    })()
                """.trimIndent()))
                assertEquals("$description after persistent highlight removal", "𠮟 、猫", afterRemoval.getString("text"))
                composeRule.waitUntil("$description blue paint", 10_000) { bluePixelCount() > baseline + 100 }
                val afterWrapping = JSONObject(evaluate(webView, """
                    (() => {
                        const h = window.hoshiHighlights;
                        const segments = h.collectSegments(2, 4);
                        const range = document.createRange();
                        range.setStart(segments[0].node, segments[0].start);
                        const last = segments[segments.length - 1];
                        range.setEnd(last.node, last.end);
                        const selection = getSelection();
                        selection.removeAllRanges(); selection.addRange(range);
                        h.createHighlight('yellow', 'created');
                        const created = Array.from(CSS.highlights.get('hoshi-search') || [], r => r.toString()).join('');
                        h.removeHighlight('created');
                        window.hoshiReader.applySasayakiCues([{id:'cue', start:1, length:2}]);
                        return {created, passive: Array.from(CSS.highlights.get('hoshi-search') || [], r => r.toString()).join('')};
                    })()
                """.trimIndent()))
                assertEquals(description, "𠮟 、猫", afterWrapping.getString("created"))
                assertEquals(description, "𠮟 、猫", afterWrapping.getString("passive"))
                evaluate(webView, ReaderPaginationScripts.clearSearchHighlightInvocation())
                composeRule.waitUntil("$description clear paint", 10_000) { bluePixelCount() <= baseline + 20 }
            }
        } finally {
            instrumentation.runOnMainSync { webView.destroy() }
        }
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

    private fun bluePixelCount(): Int {
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.blue > color.red + 0.12f && color.blue > color.green + 0.05f) count++
        }
        return count
    }
}
