package moe.antimony.hoshi.features.reader

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderPaginationWebViewTest {
    @Test
    fun progressIncludesTextAtCurrentPageStart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
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
                    <head>${ReaderPaginationScripts.shellScript(initialProgress = 0.0)}</head>
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
                    <head>${ReaderPaginationScripts.shellScript(initialProgress = 0.0)}</head>
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
        assertEquals(100, result.getInt("scrollTop"))
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
        return {
            scrollTop: window.__scrollEl.scrollTop,
            lastPageScroll: window.lastPageScroll
        };
    })();
    """.trimIndent()
