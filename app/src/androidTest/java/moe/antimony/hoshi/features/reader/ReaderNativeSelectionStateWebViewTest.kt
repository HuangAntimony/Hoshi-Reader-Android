package moe.antimony.hoshi.features.reader

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real DOM selection and ActionMode lifecycle, with generated text and no saved app data. */
@RunWith(AndroidJUnit4::class)
class ReaderNativeSelectionStateWebViewTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun selectionKeepsPageAndNativeGesturesLockedWhenMenuCloses() {
        val assets = ReaderWebAssets.load(instrumentation.targetContext)
        lateinit var webView: HoshiReaderWebView
        composeRule.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                HoshiReaderWebView(context).also {
                    webView = it
                    it.settings.javaScriptEnabled = true
                    it.tag = "selection-fixture"
                }
            })
        }
        composeRule.waitForIdle()
        try {
            val loaded = CountDownLatch(1)
            instrumentation.runOnMainSync {
                val settings = ReaderSettings(verticalWriting = true, fontSize = 27)
                val density = webView.resources.displayMetrics.density
                val viewport = readerViewportCssLayout(settings,
                    (webView.width / density).toInt(), (webView.height / density).toInt()).cssVariables()
                val styles = ReaderContentStyles.styleTag(settings = settings, readerCssTemplate = assets.readerCss)
                val script = ReaderPaginationScripts.shellScript(settings = settings, assets = assets)
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                }
                webView.loadDataWithBaseURL(null,
                    "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                        "<script>window.HoshiReaderRestore={postMessage:()=>window.testRestored=true};</script>" +
                        "$styles<style>$viewport</style>$script" +
                        "<script>${assets.selectionJs};hoshiSelection.configure({bridge:'android-reader',nativeSelectionToken:'selection-fixture'});</script>" +
                        "</head><body><p>前のページ。</p>" +
                        "<p style='break-before:column'><ruby><span>読</span><rt>どく</rt></ruby><span id='selected'>書の時間。</span></p>" +
                        "<p style='break-before:column'>次のページ。</p></body></html>",
                    "text/html", "utf-8", null)
            }
            assertTrue(loaded.await(15, TimeUnit.SECONDS))
            composeRule.waitUntil(15_000) { evaluate(webView, "Boolean(window.testRestored)") == "true" }
            evaluate(webView, """
                (() => { const r=document.createRange();r.selectNodeContents(document.getElementById('selected'));
                    hoshiReader.scrollToRange(r);return true; })()
            """.trimIndent())
            val position = evaluate(webView, "document.body.scrollTop").toDouble()
            assertTrue("Fixture is past the first page", position > 0)
            evaluate(webView, """
                (() => { const r=document.createRange();r.selectNodeContents(document.getElementById('selected'));
                    getSelection().removeAllRanges();getSelection().addRange(r);return true; })()
            """.trimIndent())
            composeRule.waitUntil(3000) { webView.nativeSelectionState.value }
            lateinit var mode: ActionMode
            instrumentation.runOnMainSync {
                val created = webView.startActionMode(object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = true
                    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
                    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
                    override fun onDestroyActionMode(mode: ActionMode) = Unit
                }, ActionMode.TYPE_FLOATING)
                assertNotNull(created)
                mode = created!!
            }
            instrumentation.runOnMainSync { mode.finish() }
            assertEquals("Selected text survives menu destruction", "\"書の時間。\"", evaluate(webView, "getSelection().toString()"))
            assertTrue("Native gestures and narration stay blocked", webView.nativeSelectionState.value)
            var chapterChanges = 0
            instrumentation.runOnMainSync {
                webView.navigatePage(ReaderNavigationDirection.Backward,
                    onLimit = { chapterChanges++; false },
                    onDisplayedProgress = {}, onSaveProgress = {})
            }
            evaluate(webView, "true")
            assertEquals("Selection blocking is not a chapter boundary", 0, chapterChanges)
            assertEquals("Logical page position survives selection", position,
                evaluate(webView, "hoshiReader.getPagePosition(hoshiReader.getScrollContext())").toDouble(), 1.0)
            evaluate(webView, "getSelection().removeAllRanges();true")
            composeRule.waitUntil(3000) { !webView.nativeSelectionState.value }
            assertFalse(evaluate(webView, "hoshiReader.nativeSelectionActive").toBoolean())
            assertEquals("Paging resumes after deselection", "\"scrolled\"", evaluate(webView, "hoshiReader.paginate('backward')"))
            instrumentation.runOnMainSync { webView.prepareForDocument("next-chapter") }
            evaluate(webView, "HoshiNativeSelection.postMessage(true,'selection-fixture');true")
            instrumentation.waitForIdleSync()
            assertFalse("Old document notifications are ignored", webView.nativeSelectionState.value)
            evaluate(webView, "HoshiNativeSelection.postMessage(true,'next-chapter');true")
            composeRule.waitUntil(3000) { webView.nativeSelectionState.value }
            evaluate(webView, "HoshiNativeSelection.postMessage(false,'selection-fixture');true")
            instrumentation.waitForIdleSync()
            assertTrue("Old deselection cannot unlock a new chapter", webView.nativeSelectionState.value)
            instrumentation.runOnMainSync { webView.prepareForDocument("third-chapter") }
            assertFalse("Changing chapters clears the old selection", webView.nativeSelectionState.value)

        } finally {
            instrumentation.runOnMainSync { webView.releaseForDestroy(); webView.destroy() }
        }
    }

    private fun evaluate(webView: WebView, script: String): String {
        val done = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { webView.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return result
    }
}
