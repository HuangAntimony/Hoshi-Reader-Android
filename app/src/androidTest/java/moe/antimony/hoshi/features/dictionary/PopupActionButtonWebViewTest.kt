package moe.antimony.hoshi.features.dictionary

import android.graphics.Rect
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PopupActionButtonWebViewTest {
    @Test
    fun popupWebViewClampsHorizontalScrollToZero() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        instrumentation.runOnMainSync {
            val webView: WebView = PopupActionButtonWebView(context)

            webView.scrollTo(50, 12)
            assertEquals(0, webView.scrollX)
            assertEquals(12, webView.scrollY)

            webView.scrollBy(40, 5)
            assertEquals(0, webView.scrollX)
            assertEquals(17, webView.scrollY)

            webView.destroy()
        }
    }

    @Test
    fun nativeButtonsUseContentCoordinatesAndScrollWithWebView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        instrumentation.runOnMainSync {
            val webView = PopupActionButtonWebView(context)
            webView.layout(0, 0, 360, 320)

            webView.updateActionButtonFrames(
                listOf(
                    PopupButtonFrame(
                        kind = PopupButtonKind.Audio,
                        entryIndex = 0,
                        x = 20.0,
                        y = 240.0,
                        width = 28.0,
                        height = 28.0,
                    ),
                ),
            )

            val button = webView.findViewWithTag<android.view.View>("audio-0")
            assertNotNull(button)
            val density = context.resources.displayMetrics.density
            assertEquals((20.0 * density).toFloat(), button.x, 0.5f)
            assertEquals((240.0 * density).toFloat(), button.y, 0.5f)
            assertEquals(0, webView.scrollX)

            webView.scrollTo(0, (160 * density).toInt())
            assertEquals(0, webView.scrollX)
            assertEquals((160 * density).toInt(), webView.scrollY)
            assertEquals((240.0 * density).toFloat(), button.y, 0.5f)

            webView.destroy()
        }
    }

    @Test
    fun nativeButtonsClipToWebViewViewportWhenScrolledUnderPopupControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        instrumentation.runOnMainSync {
            val webView = PopupActionButtonWebView(context)
            webView.layout(0, 0, 360, 120)

            webView.updateActionButtonFrames(
                listOf(
                    PopupButtonFrame(
                        kind = PopupButtonKind.Audio,
                        entryIndex = 0,
                        x = 20.0,
                        y = 20.0,
                        width = 28.0,
                        height = 28.0,
                    ),
                ),
            )

            val density = context.resources.displayMetrics.density
            val button = webView.findViewWithTag<View>("audio-0")
            assertNotNull(button)

            webView.scrollTo(0, (30 * density).toInt())

            assertEquals(View.VISIBLE, button.visibility)
            assertEquals(
                Rect(
                    0,
                    (10 * density).toInt(),
                    (28 * density).toInt(),
                    (28 * density).toInt(),
                ),
                button.clipBounds,
            )

            webView.scrollTo(0, (60 * density).toInt())

            assertEquals(View.INVISIBLE, button.visibility)
            webView.destroy()
        }
    }

    @Test
    fun nativeButtonClickInvokesMatchingJavascriptEntryPoint() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val loaded = CountDownLatch(1)
        val clicked = CountDownLatch(1)

        lateinit var webView: PopupActionButtonWebView
        instrumentation.runOnMainSync {
            WebView.setWebContentsDebuggingEnabled(true)
            webView = PopupActionButtonWebView(context)
            webView.settings.javaScriptEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loaded.countDown()
                }
            }
            webView.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun playEntryAudio(index: Int) {
                        if (index == 2) clicked.countDown()
                    }
                },
                "ButtonClickTest",
            )
            webView.layout(0, 0, 360, 320)
            webView.loadDataWithBaseURL(
                "https://hoshi.local/popup-button-test.html",
                """
                    <!DOCTYPE html>
                    <html>
                    <body>
                    <script>
                        function playEntryAudio(index) { ButtonClickTest.playEntryAudio(index); }
                    </script>
                    </body>
                    </html>
                """.trimIndent(),
                "text/html",
                "UTF-8",
                null,
            )
        }

        assertTrue(loaded.await(5, TimeUnit.SECONDS))

        instrumentation.runOnMainSync {
            webView.updateActionButtonFrames(
                listOf(
                    PopupButtonFrame(
                        kind = PopupButtonKind.Audio,
                        entryIndex = 2,
                        x = 20.0,
                        y = 20.0,
                        width = 28.0,
                        height = 28.0,
                    ),
                ),
            )
            webView.findViewWithTag<android.view.View>("audio-2").performClick()
        }

        assertTrue(clicked.await(2, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { webView.destroy() }
    }
}
