package moe.antimony.hoshi.webview

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HoshiPopupWebViewTest {
    @Test
    fun popupWebViewClampsHorizontalScrollToZero() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        instrumentation.runOnMainSync {
            val webView: WebView = HoshiPopupWebView(context)

            webView.scrollTo(50, 12)

            assertEquals(0, webView.scrollX)
            assertEquals(12, webView.scrollY)
            webView.destroy()
        }
    }

    @Test
    fun popupButtonFramesResyncWhenDocumentScrolls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val frameYValues = Collections.synchronizedList(mutableListOf<Double>())
        val firstFrame = CountDownLatch(1)
        val scrolledFrame = CountDownLatch(1)
        val popupJs = context.assets.open("hoshi-popup/popup.js")
            .bufferedReader()
            .use { it.readText() }
        lateinit var webView: WebView
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { margin: 0; height: 1200px; }
                    .button-slot {
                        position: absolute;
                        left: 20px;
                        top: 240px;
                        width: 28px;
                        height: 28px;
                    }
                </style>
                <script>
                    window.webkit = {
                        messageHandlers: {
                            buttonFrames: {
                                postMessage: function(frames) {
                                    window.HoshiButtonFrameTest.postMessage(JSON.stringify(frames));
                                }
                            },
                            popupScrolled: { postMessage: function() {} }
                        }
                    };
                </script>
                <script>$popupJs</script>
            </head>
            <body>
                <span class="button-slot" data-kind="audio" data-entry-index="0" data-enabled="true"></span>
                <script>requestAnimationFrame(syncButtonFrames);</script>
            </body>
            </html>
        """.trimIndent()

        instrumentation.runOnMainSync {
            WebView.setWebContentsDebuggingEnabled(true)
            webView = HoshiPopupWebView(context)
            webView.settings.javaScriptEnabled = true
            webView.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun postMessage(message: String) {
                        val y = JSONArray(message).getJSONObject(0).getDouble("y")
                        frameYValues += y
                        if (frameYValues.size == 1) {
                            firstFrame.countDown()
                        } else if (frameYValues.size >= 2) {
                            scrolledFrame.countDown()
                        }
                    }
                },
                "HoshiButtonFrameTest",
            )
            webView.layout(0, 0, 360, 320)
            webView.loadDataWithBaseURL(
                "https://hoshi.local/popup-scroll-test.html",
                html,
                "text/html",
                "UTF-8",
                null,
            )
        }

        assertTrue(firstFrame.await(2, TimeUnit.SECONDS))

        instrumentation.runOnMainSync {
            webView.evaluateJavascript("window.scrollTo(0, 160);", null)
        }
        assertTrue(scrolledFrame.await(2, TimeUnit.SECONDS))
        assertTrue(frameYValues[1] < frameYValues[0] - 100.0)

        instrumentation.runOnMainSync { webView.destroy() }
    }
}
