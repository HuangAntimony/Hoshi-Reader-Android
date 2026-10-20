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

/** Exercises saved picker ranges using isolated HTML, without writing book highlights. */
@RunWith(AndroidJUnit4::class)
class ReaderNativeHighlightWebViewTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun rubyBoundariesCreateRecolorAndRemoveHighlightsInEveryReaderMode() {
        val assets = ReaderWebAssets.load(instrumentation.targetContext)
        lateinit var webView: WebView
        composeRule.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).also { webView = it; it.settings.javaScriptEnabled = true }
            })
        }
        composeRule.waitForIdle()
        try {
            for (mode in ReaderViewMode.entries) for (vertical in listOf(false, true)) {
                val settings = ReaderSettings(viewMode = mode, verticalWriting = vertical, visualNovelRevealSpeed = 0)
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
                    webView.loadDataWithBaseURL(null, """
                        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                        <script>window.HoshiReaderRestore={postMessage:()=>window.testRestored=true};</script>
                        $styles<style>$viewport</style>$script
                        <script>${assets.languageJapaneseJs}
                        ${assets.selectionJapaneseJs}
                        ${assets.selectionJs}</script></head><body><p>前。<ruby>瑞<rp>(</rp><rt><span>みず</span></rt><rp>)</rp>々<rt>みず</rt></ruby>しさ😀です。</p></body></html>
                    """.trimIndent(), "text/html", "utf-8", null)
                }
                assertTrue(loaded.await(15, TimeUnit.SECONDS))
                composeRule.waitUntil(15_000) { evaluate(webView, "Boolean(window.testRestored)") == "true" }
                for ((kind, text, rawOffset) in listOf(
                    Triple("annotationStart", "々", 3), Triple("annotationEnd", "瑞", 2),
                    Triple("elementStart", "々", 3), Triple("elementEnd", "瑞々", 2),
                    Triple("annotationOnly", "", 0),
                )) for (backward in listOf(false, true)) {
                    val description = "$mode vertical=$vertical $kind backward=$backward"
                    val response = JSONObject(evaluate(webView, """
                        (() => {
                            const h=hoshiHighlights, ruby=document.querySelector('ruby');
                            const walker=hoshiSelection.createWalker(ruby),base=[];
                            for(let n=walker.nextNode();n;n=walker.nextNode()) if(n.textContent) base.push(n);
                            const annotation=ruby.querySelector('rt span').firstChild;
                            const range=document.createRange();
                            if ('$kind' === 'annotationStart') {range.setStart(annotation,0);range.setEnd(base[1],1);}
                            if ('$kind' === 'annotationEnd') {range.setStart(base[0],0);range.setEnd(annotation,2);}
                            if ('$kind' === 'elementStart') {range.setStart(ruby,1);range.setEnd(base[1],1);}
                            if ('$kind' === 'elementEnd') {range.setStart(base[0],0);range.setEnd(ruby,ruby.childNodes.length);}
                            if ('$kind' === 'annotationOnly') range.selectNodeContents(annotation);
                            const s=getSelection();
                            s.setBaseAndExtent($backward?range.endContainer:range.startContainer,
                                $backward?range.endOffset:range.startOffset,
                                $backward?range.startContainer:range.endContainer,
                                $backward?range.startOffset:range.endOffset);
                            const endpoints=[s.anchorNode,s.anchorOffset,s.focusNode,s.focusOffset];
                            const prepared=h.prepareHighlightSelection();
                            const unchanged=endpoints.every((v,i)=>v===[s.anchorNode,s.anchorOffset,s.focusNode,s.focusOffset][i]);
                            s.removeAllRanges(); // ActionMode closes before choosing a color.
                            const created=h.createHighlight('yellow','fixture');
                            const wrappers=Array.from(document.querySelectorAll('.hoshi-highlight'));
                            const marked=wrappers.map(n=>n.textContent).join('');
                            const annotations=wrappers.some(n=>n.closest('rt,rp'));
                            const selectMarked=()=>{
                                const range=document.createRange();
                                range.setStartBefore(document.querySelector('.hoshi-highlight'));
                                const marks=document.querySelectorAll('.hoshi-highlight');
                                range.setEndAfter(marks[marks.length-1]);
                                s.removeAllRanges();s.addRange(range);
                            };
                            let recolored=null,removed=null;
                            if(created) {
                                selectMarked();recolored=h.createHighlight('pink','unused');
                                selectMarked();removed=h.createHighlight('pink','unused');
                            }
                            return {prepared,unchanged,created,marked,annotations,recolored,removed,
                                remaining:document.querySelectorAll('.hoshi-highlight').length};
                        })()
                    """.trimIndent()))
                    assertTrue(description, response.getBoolean("unchanged"))
                    if (text.isEmpty()) {
                        assertTrue(description, response.isNull("created"))
                    } else {
                        assertTrue(description, response.getBoolean("prepared"))
                        assertTrue("$description creates a highlight", !response.isNull("created"))
                        val created = response.getJSONObject("created")
                        assertEquals(description, "created", created.getString("action"))
                        assertEquals(description, text, created.getString("text"))
                        assertEquals(description, rawOffset, created.getInt("offset"))
                        assertEquals(description, rawOffset - 1, created.getInt("start"))
                        assertEquals(description, text, response.getString("marked"))
                        assertEquals(description, false, response.getBoolean("annotations"))
                        assertEquals(description, "recolored", response.getJSONObject("recolored").getString("action"))
                        assertEquals(description, "fixture", response.getJSONObject("recolored").getString("id"))
                        assertEquals(description, "removed", response.getJSONObject("removed").getString("action"))
                    }
                    assertEquals(description, 0, response.getInt("remaining"))
                }
            }
        } finally {
            instrumentation.runOnMainSync { webView.destroy() }
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
