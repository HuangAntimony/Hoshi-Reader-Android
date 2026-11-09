package moe.antimony.hoshi.features.reader

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import moe.antimony.hoshi.epub.filteredReaderText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderPaginationWebViewTest {
    @Test
    fun koreanRubyOffsetsMatchNativeTextInWebView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = ReaderWebAssets.load(context)
        val pageLoaded = CountDownLatch(1)
        val scriptFinished = CountDownLatch(1)
        val body = "<body>𠮟가、<ruby><span id=\"base\">한글</span>" +
            "<rp>fallback</rp><rt>reading</rt><rp>주석</rp></ruby>" +
            "<span id=\"tail\"> ㄱㆎA</span></body>"
        var result = JSONObject()
        lateinit var webView: WebView

        try {
            instrumentation.runOnMainSync {
                webView = WebView(context)
                webView.settings.javaScriptEnabled = true
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        pageLoaded.countDown()
                    }
                }
                webView.loadDataWithBaseURL(
                    null,
                    "<!doctype html><html><head>${ReaderPaginationScripts.shellScript(assets = assets)}</head>$body</html>",
                    "text/html", "utf-8", null,
                )
            }
            assertTrue(pageLoaded.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                webView.evaluateJavascript(
                    """
                    (() => {
                        const reader = window.hoshiReader;
                        reader.buildNodeOffsets();
                        const walker = reader.createWalker();
                        let text = '', node;
                        while (node = walker.nextNode()) text += node.textContent;
                        const base = document.getElementById('base').firstChild;
                        const tail = document.getElementById('tail').firstChild;
                        return {
                            normalized: reader.normalizeText(text),
                            count: reader.countChars(text), rawCount: reader.countRawChars(text),
                            base: reader.nodeStartOffsets.get(base), baseRaw: reader.nodeStartRawOffsets.get(base),
                            tail: reader.nodeStartOffsets.get(tail), tailRaw: reader.nodeStartRawOffsets.get(tail)
                        };
                    })();
                    """.trimIndent(),
                ) { value ->
                    result = JSONObject(value)
                    scriptFinished.countDown()
                }
            }
            assertTrue(scriptFinished.await(10, TimeUnit.SECONDS))
            assertEquals("𠮟가한글ㄱㆎA", result.getString("normalized"))
            assertEquals(body.filteredReaderText(), result.getString("normalized"))
            assertEquals(7, result.getInt("count"))
            assertEquals(9, result.getInt("rawCount"))
            assertEquals(2, result.getInt("base"))
            assertEquals(3, result.getInt("baseRaw"))
            assertEquals(4, result.getInt("tail"))
            assertEquals(5, result.getInt("tailRaw"))
        } finally {
            instrumentation.runOnMainSync { webView.destroy() }
        }
    }

    @Test
    fun progressIncludesTextAtCurrentPageStart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val readerWebAssets = ReaderWebAssets.load(context)
        val pageLoaded = CountDownLatch(1)
        val scriptFinished = CountDownLatch(1)
        var progress = Double.NaN
        lateinit var webView: WebView

        instrumentation.runOnMainSync {
            webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageLoaded.countDown()
                }
            }
            webView.loadDataWithBaseURL(
                null,
                """
                <!doctype html>
                <html lang="ja">
                    <head>${ReaderPaginationScripts.shellScript(initialProgress = 0.0, assets = readerWebAssets)}</head>
                    <body>一二三四五六七八九十</body>
                </html>
                """.trimIndent(),
                "text/html",
                "utf-8",
                null,
            )
        }

        assertTrue(pageLoaded.await(5, TimeUnit.SECONDS))

        instrumentation.runOnMainSync {
            webView.evaluateJavascript(progressAtPageStartScript()) { result ->
                progress = result.toDouble()
                scriptFinished.countDown()
            }
        }

        assertTrue(scriptFinished.await(5, TimeUnit.SECONDS))
        assertEquals(0.5, progress, 0.000001)
    }

    @Test
    fun nativeSelectionLocksPagedScrollPosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val readerWebAssets = ReaderWebAssets.load(context)
        val pageLoaded = CountDownLatch(1)
        val scriptFinished = CountDownLatch(1)
        var result = JSONObject()
        lateinit var webView: WebView

        instrumentation.runOnMainSync {
            webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageLoaded.countDown()
                }
            }
            webView.loadDataWithBaseURL(
                null,
                """
                <!doctype html>
                <html lang="ja">
                    <head>${ReaderPaginationScripts.shellScript(initialProgress = 0.0, assets = readerWebAssets)}</head>
                    <body>一二三四五六七八九十</body>
                </html>
                """.trimIndent(),
                "text/html",
                "utf-8",
                null,
            )
        }

        assertTrue(pageLoaded.await(5, TimeUnit.SECONDS))

        instrumentation.runOnMainSync {
            webView.evaluateJavascript(nativeSelectionScrollLockScript()) { value ->
                result = JSONObject(value)
                scriptFinished.countDown()
            }
        }

        assertTrue(scriptFinished.await(5, TimeUnit.SECONDS))
        assertFalse(result.optBoolean("missingSetNativeSelectionActive"))
        assertFalse(result.optBoolean("missingHandlePagedBodyScroll"))
        assertEquals(100, result.getInt("logicalScroll"))
        assertEquals(100, result.getInt("scrollTopAfterRelease"))
        assertEquals(100, result.getInt("lastPageScroll"))
    }
}

private fun progressAtPageStartScript(): String =
    """
    (() => {
        window.hoshiReader.countCharsBeforeViewport = function(node) {
            return (node.textContent || '').indexOf('一二三四五六七八九十') >= 0 ? 5 : 0;
        };
        window.hoshiReader.getScrollContext = function() {
            return { vertical: true, scrollEl: { scrollTop: 100, scrollLeft: 0 }, pageSize: 100, maxScroll: 200 };
        };
        // The fixture spans the mocked viewport; production now reuses node bounds.
        var metrics = window.hoshiReader.buildPaginationMetrics();
        for (var entry of metrics.textRanges) { entry.start = 0; entry.end = 200; }
        return window.hoshiReader.calculateProgress();
    })();
    """.trimIndent()

private fun nativeSelectionScrollLockScript(): String =
    """
    (() => {
        if (typeof window.hoshiReader.setNativeSelectionActive !== 'function') {
            return { missingSetNativeSelectionActive: true };
        }
        if (typeof window.hoshiReader.handlePagedBodyScroll !== 'function') {
            return { missingHandlePagedBodyScroll: true };
        }
        window.__scrollEl = { scrollTop: 100, scrollLeft: 0 };
        window.hoshiReader.getScrollContext = function() {
            return {
                vertical: true,
                scrollEl: window.__scrollEl,
                pageSize: 100,
                maxScroll: 400
            };
        };
        window.lastPageScroll = 100;
        window.hoshiReader.setNativeSelectionActive(true);
        window.__scrollEl.scrollTop = 200;
        window.hoshiReader.handlePagedBodyScroll();
        var logicalScroll = window.hoshiReader.getPagePosition(window.hoshiReader.getScrollContext());
        window.hoshiReader.setNativeSelectionActive(false);
        return {
            logicalScroll: logicalScroll,
            scrollTopAfterRelease: window.__scrollEl.scrollTop,
            lastPageScroll: window.lastPageScroll
        };
    })();
    """.trimIndent()
