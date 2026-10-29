package moe.antimony.hoshi.features.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
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
    fun publisherRootLimitsDoNotShrinkReaderViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = ReaderWebAssets.load(instrumentation.targetContext)
        lateinit var webView: WebView
        composeRule.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).also { webView = it; it.settings.javaScriptEnabled = true }
            })
        }
        composeRule.waitForIdle()
        val publisherCss = sanitizeReaderCss("""
            html { max-width: 170px; max-height: 190px; }
            body { max-width: 160px; max-height: 180px; }
            .bounded { max-width: 73px; max-height: 51px; }
        """.trimIndent())
        var fixture = 0
        try {
            for (mode in ReaderViewMode.entries) for (vertical in listOf(true, false))
                for (padding in listOf(0, 18)) for (picture in listOf(false, true)) {
                val label = "$mode-vertical-$vertical-padding-$padding-picture-$picture"
                val settings = ReaderSettings(viewMode = mode, verticalWriting = vertical, fontSize = 24,
                    horizontalPadding = padding, verticalPadding = padding, visualNovelRevealSpeed = 0)
                val color = Color.rgb(40 + fixture++ * 7, 50, 210)
                val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                bitmap.recycle()
                val image = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                val body = if (picture) "<p><img src='$image'/></p>" else
                    "<p><span class='bounded'>「<ruby>読書<rt>どくしょ</rt></ruby>の時間です。」</span></p>"
                val loaded = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    val density = webView.resources.displayMetrics.density
                    val viewport = readerViewportCssLayout(settings,
                        (webView.width / density).toInt(), (webView.height / density).toInt()).cssVariables()
                    val styles = ReaderContentStyles.styleTag(settings = settings, readerCssTemplate = assets.readerCss)
                    val script = ReaderPaginationScripts.shellScript(settings = settings, assets = assets)
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                    }
                    webView.loadDataWithBaseURL(null,
                        "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'/>" +
                            "<style>$publisherCss</style><script>window.HoshiReaderRestore={postMessage:()=>window.testRestored=true};</script>" +
                            "$styles<style>$viewport</style>$script</head><body>$body</body></html>",
                        "text/html", "utf-8", null)
                }
                assertTrue("$label loaded", loaded.await(15, TimeUnit.SECONDS))
                composeRule.waitUntil(15_000) { evaluate(webView, "Boolean(window.testRestored)") == "true" }
                val geometry = JSONObject(evaluate(webView, """
                    (() => { const root = getComputedStyle(document.documentElement), body = getComputedStyle(document.body);
                        const bounded = document.querySelector('.bounded');
                        return {width: innerWidth, height: innerHeight,
                            rootWidth: parseFloat(root.width), rootHeight: parseFloat(root.height),
                            bodyWidth: parseFloat(body.width), bodyHeight: parseFloat(body.height),
                            boundedWidth: bounded && getComputedStyle(bounded).maxWidth,
                            boundedHeight: bounded && getComputedStyle(bounded).maxHeight}; })()
                """.trimIndent()))
                Log.i("HoshiViewportLimits", "$label $geometry")
                val width = geometry.getDouble("width")
                val height = geometry.getDouble("height")
                assertEquals("$label root width", width, geometry.getDouble("rootWidth"), 1.0)
                assertEquals("$label root height", height, geometry.getDouble("rootHeight"), 1.0)
                val widthRatio = if (mode == ReaderViewMode.Continuous && vertical) 1 - padding / 100.0 else 1.0
                val heightRatio = if (mode == ReaderViewMode.Continuous && !vertical) 1 - padding / 100.0 else 1.0
                assertEquals("$label body width", width * widthRatio,
                    geometry.getDouble("bodyWidth"), 1.0)
                assertEquals("$label body height", height * heightRatio,
                    geometry.getDouble("bodyHeight"), 1.0)
                if (picture) {
                    assertImagePainted(webView, label, color)
                } else {
                    assertEquals("$label descendant max-width", "73px", geometry.getString("boundedWidth"))
                    assertEquals("$label descendant max-height", "51px", geometry.getString("boundedHeight"))
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
        }
    }

    private fun assertImagePainted(webView: WebView, label: String, color: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val rect = JSONObject(evaluate(webView, """
            (() => { const image = [...document.images].find(img => img.getBoundingClientRect().width > 0);
                const r = image.getBoundingClientRect(); return {left:r.left, top:r.top, width:r.width, height:r.height,
                    visible:image.naturalWidth > 0 && r.width > 0 && r.height > 0 && r.left >= -1 && r.top >= -1 &&
                        r.right <= innerWidth + 1 && r.bottom <= innerHeight + 1}; })()
        """.trimIndent()))
        assertTrue("$label image bounds $rect", rect.getBoolean("visible"))
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { ready.countDown() }
            })
        }
        assertTrue("$label visual state", ready.await(10, TimeUnit.SECONDS))
        val scale = webView.width / evaluate(webView, "innerWidth").toDouble()
        val location = IntArray(2)
        instrumentation.runOnMainSync { webView.getLocationOnScreen(location) }
        composeRule.waitUntil(10_000) {
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            val viewport = Bitmap.createBitmap(screenshot, location[0], location[1], webView.width, webView.height)
            val painted = listOf(0.2, 0.5, 0.8).all { x -> listOf(0.2, 0.5, 0.8).all { y ->
                val pixel = viewport.getPixel(((rect.getDouble("left") + x * rect.getDouble("width")) * scale).toInt(),
                    ((rect.getDouble("top") + y * rect.getDouble("height")) * scale).toInt())
                abs(Color.red(pixel) - Color.red(color)) < 4 && abs(Color.green(pixel) - Color.green(color)) < 4 &&
                    abs(Color.blue(pixel) - Color.blue(color)) < 4
            } }
            if (painted) {
                val directory = File(instrumentation.targetContext.cacheDir, "viewport-limits-evidence").apply { mkdirs() }
                directory.resolve("$label.png").outputStream().use { viewport.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            viewport.recycle()
            screenshot.recycle()
            painted
        }
    }

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

    @Test
    fun continuousRestoreKeepsTextAlignedWithoutHalfLineDrift() {
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
            for (vertical in listOf(true, false)) for (font in listOf(22, 36)) for (lineHeight in listOf(1.0, 1.75)) for (padding in listOf(0, 30)) {
                val label = "vertical=$vertical font=$font lineHeight=$lineHeight padding=$padding"
                val settings = ReaderSettings(
                    viewMode = ReaderViewMode.Continuous,
                    verticalWriting = vertical,
                    fontSize = font,
                    horizontalPadding = padding,
                    verticalPadding = padding,
                    layoutAdvanced = true,
                    lineHeight = lineHeight,
                )
                val loaded = CountDownLatch(1)
                val styles = ReaderContentStyles.styleTag(settings = settings, readerCssTemplate = assets.readerCss)
                val script = ReaderPaginationScripts.shellScript(settings = settings, assets = assets)
                val sentence = "<span>「</span><ruby>読書<rt>どくしょ</rt></ruby>" +
                    listOf("の", "時間", "です。", "𠮟", "가", "ㄱㆎ", "漢字", "を", "読み", "ます。", "」")
                        .joinToString("") { "<span>$it</span>" }
                val body = "<p>${sentence.repeat(80)}<span>文</span><ruby id='anchor'>目印<rt>めじるし</rt></ruby>${sentence.repeat(80)}</p>"
                instrumentation.runOnMainSync {
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                    }
                    webView.loadDataWithBaseURL(null,
                        "<!doctype html><html><head><script>window.HoshiReaderRestore={postMessage:()=>window.testRestored=true};</script>$styles$script</head><body>$body</body></html>",
                        "text/html", "utf-8", null)
                }
                assertTrue(label, loaded.await(15, TimeUnit.SECONDS))
                composeRule.waitUntil(15_000) { evaluate(webView, "Boolean(window.testRestored)") == "true" }
                val before = JSONObject(evaluate(webView, """
                    (() => {
                        const r = window.hoshiReader, range = document.createRange();
                        range.selectNodeContents(document.getElementById('anchor'));
                        const rects = Array.from(range.getClientRects());
                        const right = Math.max(...rects.map(rect => rect.right));
                        const top = Math.min(...rects.map(rect => rect.top));
                        const viewport = r.contentViewport();
                        r.scrollBy({left: r.isVertical() ? right - viewport.right : 0,
                            top: r.isVertical() ? 0 : top - viewport.top, behavior: 'instant'});
                        window.testProgress = r.calculateProgress();
                        const style = getComputedStyle(document.body);
                        return {position: r.isVertical() ? document.body.scrollLeft : document.body.scrollTop, progress: window.testProgress,
                            fontSize: parseFloat(style.fontSize), lineHeight: parseFloat(style.lineHeight)};
                    })()
                """.trimIndent()))
                assertTrue("$label middle of chapter", before.getDouble("progress") in 0.2..0.8)
                assertEquals("$label font size", font.toDouble(), before.getDouble("fontSize"), 0.01)
                assertEquals("$label line height", font * lineHeight, before.getDouble("lineHeight"), 0.01)
                repeat(3) {
                    evaluate(webView, "window.testRestored=false; window.hoshiReader.restoreProgress(0); true")
                    composeRule.waitUntil(15_000) { evaluate(webView, "Boolean(window.testRestored)") == "true" }
                    evaluate(webView, "window.testRestored=false; window.hoshiReader.restoreProgress(window.testProgress); true")
                    composeRule.waitUntil(15_000) { evaluate(webView, "Boolean(window.testRestored)") == "true" }
                    val after = JSONObject(evaluate(webView, """
                        (() => { const r = window.hoshiReader;
                            const rt = document.querySelector('#anchor rt').getBoundingClientRect();
                            window.testProgress = r.calculateProgress();
                            return {
                            position: r.isVertical() ? document.body.scrollLeft : document.body.scrollTop, progress: window.testProgress,
                            annotationVisible: r.isVertical() ? rt.right <= r.contentViewport().right + 1 : rt.top >= r.contentViewport().top - 1
                        }; })()
                    """.trimIndent()))
                    assertEquals("$label restore $it", before.getDouble("position"), after.getDouble("position"), 1.0)
                    assertEquals("$label progress $it", before.getDouble("progress"), after.getDouble("progress"), 0.000001)
                    assertTrue("$label furigana $it", after.getBoolean("annotationVisible"))
                }
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
