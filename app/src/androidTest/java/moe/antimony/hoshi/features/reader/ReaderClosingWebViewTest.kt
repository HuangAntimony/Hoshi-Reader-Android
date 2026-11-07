package moe.antimony.hoshi.features.reader

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import moe.antimony.hoshi.features.tts.TtsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Generated pages and isolated WebViews only; no books, preferences or playback service. */
class ReaderClosingWebViewTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun closingRejectsScriptsCachedCueReadinessAndLateCallbacks() {
        val fixture = Fixture()
        fixture.show()
        val view = fixture.view
        load(view, """
            <html><body>Reader<script>
            window.hoshiReader = {applySasayakiCues: () => ExitProbe.mutated()};
            </script></body></html>
        """.trimIndent())
        val installed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view.applyReaderSasayakiCues("chapter", "[{\"id\":\"first\"}]") { installed.countDown() }
        }
        assertTrue(installed.await(10, TimeUnit.SECONDS))
        val callbacks = AtomicInteger()
        val version = Any()
        instrumentation.runOnMainSync {
            // These results arrive after close, even though their scripts were already sent.
            view.evaluateJavascript("ExitProbe.executed()") { callbacks.incrementAndGet() }
            view.applyReaderSasayakiCues("chapter", "[{\"id\":\"next\"}]", version) { callbacks.incrementAndGet() }
            view.beginClose()
            view.beginClose()
            view.applyReaderSasayakiCues("chapter", "[{\"id\":\"next\"}]", version) { callbacks.incrementAndGet() }
            view.applyReaderSasayakiCues("chapter", "[]") { callbacks.incrementAndGet() }
            view.evaluateJavascript("ExitProbe.executed()") { callbacks.incrementAndGet() }
        }
        drain(view)
        assertEquals("Late and cached callbacks cannot revive a closing reader", 0, callbacks.get())
        assertEquals("Only the script submitted before close executes", 1, fixture.probe.executions.get())
        assertEquals("Stopping after close must not replace cues", 2, fixture.probe.mutations.get())

        fixture.replace()
        load(fixture.view, "<html><body>Next book<script>window.hoshiReader={applySasayakiCues:()=>ExitProbe.mutated()};</script></body></html>")
        val nextReady = CountDownLatch(1)
        instrumentation.runOnMainSync {
            fixture.view.applyReaderSasayakiCues("next-book", "[]") { nextReady.countDown() }
        }
        assertTrue("A new reader can install cues", nextReady.await(10, TimeUnit.SECONDS))
    }

    @Test fun closingALongNarratedChapterDoesNotUnwrapOrNormalizeBeforeOpeningNextBook() {
        val fixture = Fixture()
        fixture.show()
        val view = fixture.view
        val assets = ReaderWebAssets.load(instrumentation.targetContext)
        val settings = ReaderSettings(paragraphMode = true, verticalWriting = true, textAnimation = false)
        val body = (0 until 1007).joinToString("") { "<p>長い章の読み上げを確認します。<ruby>東京<rt>とうきょう</rt></ruby>の空を眺めています。</p>" }
        val cues = ReaderSasayakiCues.ttsChapterCuesJson(TtsText.chapter(0, body))
        val styles = ReaderContentStyles.styleTag(settings = settings, readerCssTemplate = assets.readerCss)
        val script = ReaderPaginationScripts.shellScript(settings = settings, assets = assets, sasayakiCuesJson = cues)
        load(view, "<!doctype html><html><head><script>window.HoshiReaderRestore={postMessage:()=>window.testRestored=true};</script>$styles$script</head><body>$body</body></html>")
        composeRule.waitUntil(30_000) { evaluate(view, "Boolean(window.testRestored)") == "true" }
        assertTrue("The fixture really installs many inline cues", evaluate(view,
            "document.querySelectorAll('.hoshi-sasayaki-cue').length").toInt() > 1000)
        evaluate(view, """
            (() => {
                const unwrap = window.hoshiReader.unwrap;
                window.hoshiReader.unwrap = function(...args) { ExitProbe.mutated(); return unwrap.apply(this, args); };
                const normalize = Node.prototype.normalize;
                Node.prototype.normalize = function() { ExitProbe.mutated(); return normalize.call(this); };
                return true;
            })()
        """.trimIndent())
        instrumentation.runOnMainSync {
            view.beginClose()
            // This is the cue replacement emitted when stopping TTS resets its state.
            view.applyReaderSasayakiCues("long-chapter", "[]") {}
        }
        drain(view)
        assertEquals("Exit must discard the document without bulk DOM cleanup", 0, fixture.probe.mutations.get())
        fixture.replace()
        load(fixture.view, "<html><body>次の本</body></html>")
        assertEquals("\"次の本\"", evaluate(fixture.view, "document.body.textContent"))
    }

    private inner class Fixture {
        var generation by mutableIntStateOf(0)
        lateinit var view: HoshiReaderWebView
        val probe = ExitProbe()

        fun show() {
            composeRule.setContent {
                key(generation) {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                        HoshiReaderWebView(context).also {
                            view = it
                            it.settings.javaScriptEnabled = true
                            it.addJavascriptInterface(probe, "ExitProbe")
                        }
                    }, onRelease = {
                        it.releaseForDestroy()
                        (it.parent as? android.view.ViewGroup)?.removeView(it)
                        it.destroy()
                    })
                }
            }
            composeRule.waitForIdle()
        }

        fun replace() {
            instrumentation.runOnMainSync { generation++ }
            composeRule.waitForIdle()
        }
    }

    class ExitProbe {
        val mutations = AtomicInteger()
        val executions = AtomicInteger()
        @JavascriptInterface fun mutated() { mutations.incrementAndGet() }
        @JavascriptInterface fun executed() { executions.incrementAndGet() }
    }

    private fun load(view: WebView, html: String) {
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
            }
            view.loadDataWithBaseURL("https://appassets.androidplatform.net/fixture.xhtml", html, "text/html", "utf-8", null)
        }
        assertTrue("Fixture loaded", loaded.await(20, TimeUnit.SECONDS))
    }

    private fun evaluate(view: WebView, script: String): String {
        val completed = CountDownLatch(1)
        var value = "null"
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { value = it; completed.countDown() } }
        assertTrue("JavaScript completed", completed.await(10, TimeUnit.SECONDS))
        return value
    }

    private fun drain(view: WebView) {
        val completed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { completed.countDown() }
            })
        }
        assertTrue("Previously submitted scripts completed", completed.await(20, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }
}
