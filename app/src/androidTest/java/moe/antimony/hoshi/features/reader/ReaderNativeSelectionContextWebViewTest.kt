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

@RunWith(AndroidJUnit4::class)
class ReaderNativeSelectionContextWebViewTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun rubySelectionUsesSelectedBodyTextWithoutChangingNativeEndpoints() {
        val assets = ReaderWebAssets.load(instrumentation.targetContext)
        lateinit var webView: WebView
        composeRule.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).also { webView = it; it.settings.javaScriptEnabled = true }
            })
        }
        composeRule.waitForIdle()
        try {
            val loaded = CountDownLatch(1)
            instrumentation.runOnMainSync {
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                }
                webView.loadDataWithBaseURL(null, """
                    <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                    <script>${assets.languageAssets.languageJapaneseJs}
                    ${assets.languageAssets.selectionJapaneseJs}
                    ${assets.selectionJs}
                    ${assets.readerTextSemanticsJs}
                    ${assets.readerDomTextJs}</script></head><body>
                    <p id="rendered">前。<span id="edge">瑞</span><ruby id="ruby">瑞<rp>(</rp><rt><span>みず</span></rt><rp>)</rp>々<rt>みず</rt></ruby>しさ😀です。</p>
                    <script>
                    const rendered = document.getElementById('rendered');
                    const offsets = new WeakMap();
                    const walker = document.createTreeWalker(rendered, NodeFilter.SHOW_TEXT);
                    let node, count = 0;
                    while (node = walker.nextNode()) {
                        if (node.parentElement.closest('rt, rp')) continue;
                        offsets.set(node, count); count += hoshiReaderTextSemantics.countChars(node.textContent);
                    }
                    window.hoshiReader = {nodeStartOffsets: offsets,
                        isMatchableChar: hoshiReaderTextSemantics.isMatchableChar};
                    window.checkContext = (kind, backward) => {
                        const ruby = document.getElementById('ruby');
                        const annotation = ruby.querySelector('rt span').firstChild;
                        const body = ruby.childNodes[4];
                        const edge = document.getElementById('edge');
                        const tail = ruby.nextSibling;
                        const range = document.createRange();
                        if (kind === 'annotation') {range.setStart(annotation, 0);range.setEnd(body, 1);}
                        if (kind === 'annotationOnly') {range.selectNodeContents(annotation);}
                        if (kind === 'elementEnd') {range.setStart(edge, 1);range.setEnd(body, 1);}
                        if (kind === 'textEnd') {range.setStart(edge.firstChild, 1);range.setEnd(body, 1);}
                        if (kind === 'wholeRuby') {range.selectNodeContents(ruby);}
                        if (kind === 'plain') {range.setStart(tail, 1);range.setEnd(tail, 4);}
                        const selection = getSelection();
                        selection.setBaseAndExtent(backward ? range.endContainer : range.startContainer,
                            backward ? range.endOffset : range.startOffset,
                            backward ? range.startContainer : range.endContainer,
                            backward ? range.startOffset : range.endOffset);
                        const before = [selection.anchorNode, selection.anchorOffset, selection.focusNode, selection.focusOffset];
                        const result = hoshiSelection.nativeSelectionContext();
                        return {result, unchanged: before.every((value,index) => value ===
                            [selection.anchorNode, selection.anchorOffset, selection.focusNode, selection.focusOffset][index])};
                    };
                    </script></body></html>
                """.trimIndent(), "text/html", "utf-8", null)
            }
            assertTrue(loaded.await(15, TimeUnit.SECONDS))
                for (vertical in listOf(true, false)) {
                    evaluate(webView, "document.body.style.writingMode='${if (vertical) "vertical-rl" else "horizontal-tb"}'")
                    for (backward in listOf(false, true)) {
                        for ((kind, text, offset) in listOf(
                            Triple("annotation", "々", 3), Triple("elementEnd", "瑞々", 2),
                            Triple("textEnd", "瑞々", 2), Triple("wholeRuby", "瑞々", 2),
                            Triple("plain", "さ😀", 5),
                        )) {
                            val label = "$kind vertical=$vertical backward=$backward"
                            val result = JSONObject(evaluate(webView, "checkContext('$kind',$backward)"))
                            assertTrue("$label keeps native endpoints", result.getBoolean("unchanged"))
                            assertTrue("$label returns context", !result.isNull("result"))
                            val context = result.getJSONObject("result")
                            assertEquals(label, text, context.getString("text"))
                            assertEquals(label, offset, context.getInt("normalizedOffset"))
                            assertEquals(label, "瑞瑞々しさ😀です。",
                                context.getString("sentence"))
                        }
                        val annotation = JSONObject(evaluate(webView, "checkContext('annotationOnly',$backward)"))
                        assertTrue("Annotation-only selection has no body context", annotation.isNull("result"))
                        assertTrue(annotation.getBoolean("unchanged"))
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
