package moe.antimony.hoshi.features.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
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
import androidx.webkit.WebViewFeature
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Generated documents only; never opens a book or changes reader preferences. */
@RunWith(AndroidJUnit4::class)
class ReaderDocumentPreparationWebViewTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun deferredDocumentsRestoreTextAndPaintImagesAcrossModes() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
        verifyDocuments(deferred = true)
    }

    @Test fun documentsStillRestoreWithoutDocumentStartSupport() {
        verifyDocuments(deferred = false)
    }

    private fun verifyDocuments(deferred: Boolean) {
        val assets = ReaderWebAssets.load(instrumentation.targetContext)
        lateinit var webView: HoshiReaderWebView
        composeRule.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                HoshiReaderWebView(context).also {
                    webView = it
                    it.settings.javaScriptEnabled = true
                    if (deferred) it.installDocumentPreparation(assets.readerDocumentPreparationJs)
                }
            })
        }
        composeRule.waitForIdle()
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val imageBytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val image = "data:image/png;base64," + Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        try {
            for (mode in ReaderViewMode.entries) for (vertical in listOf(true, false)) for (picture in listOf(false, true)) {
                val label = "deferred=$deferred $mode vertical=$vertical picture=$picture"
                val settings = ReaderSettings(viewMode = mode, verticalWriting = vertical, fontSize = 24,
                    horizontalPadding = 3, verticalPadding = 3, visualNovelRevealSpeed = 0)
                val body = if (picture) "<p><img src='$image'/></p>" else
                    "<p>「<ruby>読書<rt>どくしょ</rt></ruby>の時間です。本文を読みます。」</p>".repeat(160)
                val html = """<html xmlns="http://www.w3.org/1999/xhtml" style="display: block !important"><head><meta name="viewport" content="width=1600"/><title>Fixture</title></head><body>$body</body></html>"""
                val loaded = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                            WebResourceResponse(if (vertical) "application/xhtml+xml" else "text/html", "UTF-8", html.byteInputStream())
                        override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                    }
                    webView.loadUrl("https://appassets.androidplatform.net/epub/$mode-$vertical-$picture.xhtml")
                }
                assertTrue("$label load", loaded.await(15, TimeUnit.SECONDS))
                val before = JSONObject(evaluate(webView, """
                    ({display: document.documentElement.style.display, width: document.body.offsetWidth,
                      preparation: !!window.hoshiReaderDocumentPreparation})
                """.trimIndent()))
                assertEquals(label, deferred, before.getBoolean("preparation"))
                assertEquals(label, if (deferred) "none" else "block", before.getString("display"))
                if (deferred) assertEquals(label, 0, before.getInt("width"))
                instrumentation.runOnMainSync {
                    val density = webView.resources.displayMetrics.density
                    val viewport = readerViewportCssLayout(settings,
                        (webView.width / density).toInt(), (webView.height / density).toInt()).cssVariables()
                    val css = viewport + ReaderContentStyles.css(settings, readerCssTemplate = assets.readerCss)
                    val script = ReaderPaginationScripts.shellScript(settings = settings, assets = assets)
                        .substringAfter("<script>").substringBeforeLast("</script>")
                    webView.evaluateJavascript("""
                        window.HoshiReaderRestore = {postMessage: () => window.testRestored = true};
                        const style = document.createElement('style');
                        style.textContent = ${readerJavaScriptStringLiteral(css)};
                        document.head.appendChild(style);
                        $script
                    """.trimIndent(), null)
                }
                composeRule.waitUntil(15_000) { evaluate(webView, "!!window.testRestored") == "true" }
                val after = JSONObject(evaluate(webView, """
                    ({display: document.documentElement.style.display,
                      priority: document.documentElement.style.getPropertyPriority('display'),
                      width: document.body.offsetWidth, viewport: innerWidth,
                      progress: window.hoshiReader.calculateProgress()})
                """.trimIndent()))
                assertEquals(label, "block", after.getString("display"))
                assertEquals(label, "important", after.getString("priority"))
                assertTrue("$label $after", after.getDouble("width") > 0)
                assertTrue("$label viewport", after.getDouble("viewport") < 1600)
                // VN progress includes the displayed block; the fixture has 160 equal paragraphs.
                val initialProgress = if (mode == ReaderViewMode.VisualNovel && !picture) 1.0 / 160 else 0.0
                assertEquals("$label initial position", initialProgress, after.getDouble("progress"), 0.001)
                if (picture) {
                    val painted = CountDownLatch(1)
                    instrumentation.runOnMainSync {
                        webView.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) { painted.countDown() }
                        })
                    }
                    assertTrue("$label visual state", painted.await(10, TimeUnit.SECONDS))
                    composeRule.waitUntil(5_000) {
                        val screenshot = instrumentation.uiAutomation.takeScreenshot()
                        var red = 0
                        for (y in 0 until screenshot.height step 20) for (x in 0 until screenshot.width step 20) {
                            val color = screenshot.getPixel(x, y)
                            if (Color.red(color) > 220 && Color.green(color) < 40 && Color.blue(color) < 40) red++
                        }
                        screenshot.recycle()
                        red > 100
                    }
                } else {
                    evaluate(webView, "window.testRestored=false;window.hoshiReader.restoreProgress(0.5);true")
                    composeRule.waitUntil(15_000) { evaluate(webView, "!!window.testRestored") == "true" }
                    val progress = evaluate(webView, "window.hoshiReader.calculateProgress()").toDouble()
                    assertTrue("$label restored progress=$progress", progress in 0.35..0.65)
                    assertTrue("$label visible text", evaluate(webView, """
                        (() => { const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT); let n;
                          while (n = w.nextNode()) { if (!n.textContent.trim() || n.parentElement.closest('rt, rp')) continue;
                            const range = document.createRange(); range.selectNodeContents(n);
                            if ([...range.getClientRects()].some(r => r.width > 0 && r.height > 0 &&
                              r.right > 0 && r.bottom > 0 && r.left < innerWidth && r.top < innerHeight)) return true;
                          } return false; })()
                    """.trimIndent()) == "true")
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.releaseForDestroy()
                webView.destroy()
            }
        }
    }

    private fun evaluate(webView: WebView, script: String): String {
        val completed = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { webView.evaluateJavascript(script) { result = it; completed.countDown() } }
        assertTrue("JavaScript callback", completed.await(10, TimeUnit.SECONDS))
        return result
    }
}
