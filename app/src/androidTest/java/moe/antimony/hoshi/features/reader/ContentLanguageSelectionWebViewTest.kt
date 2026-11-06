package moe.antimony.hoshi.features.reader

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.dictionary.LookupPopupAssets
import moe.antimony.hoshi.features.dictionary.LookupPopupHtml
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Generated reader/popup documents only. Does not access books, dictionaries or saved settings. */
class ContentLanguageSelectionWebViewTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Paired measurements of actual DOM hit-testing/scanning; baseline files are supplied explicitly. */
    @Test fun compareJapaneseSelectionWithBaseline() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("lookupPerformance") == "true")
        val context = instrumentation.targetContext
        val baseline = File(context.cacheDir, "lookup-performance-baseline")
        val before = listOf("language-ja.js", "selection-ja.js", "selection.js").joinToString("\n") {
            baseline.resolve(it).readText()
        }
        val assets = ReaderWebAssets.load(context)
        val after = assets.selectionSupportJs(ContentLanguageProfile.Japanese) + "\n" + assets.selectionJs
        fun frame(id: String, script: String): String {
            val html = """<!DOCTYPE html><html lang="ja"><head><style>p{font:20px sans-serif;margin:4px}</style></head>
                <body><script>$script
                window.hoshiSelection.postTextSelected = value => window.fixturePayload = value;
                </script><p>猫が本を読んでいる。</p><p>食べました。</p><p>𠮟られた。</p><p>彼はNew Yorkで働く。</p></body></html>"""
            val srcdoc = html.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
            return """<iframe id="$id" style="border:0;width:100%;height:180px" srcdoc="$srcdoc"></iframe>"""
        }
        lateinit var web: WebView
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { viewContext ->
                WebView(viewContext).apply { settings.javaScriptEnabled = true; web = this }
            })
        }
        compose.waitForIdle()
        try {
            load(web, "<!DOCTYPE html><html><body>" + frame("before", before) + frame("after", after) + "</body></html>",
                LookupPopupAssets.load(context), readyFrameId = "before")
            val result = evaluate(web, """
                (() => {
                    try {
                    const frames = ['before', 'after'].map(id => {
                        const target = document.getElementById(id).contentWindow;
                        const points = [...target.document.querySelectorAll('p')].map(p => {
                            const range = target.document.createRange();
                            range.setStart(p.firstChild, 0);
                            range.setEnd(p.firstChild, p.textContent.codePointAt(0) > 0xffff ? 2 : 1);
                            const rect = range.getBoundingClientRect();
                            return [rect.x + rect.width / 2, rect.y + rect.height / 2];
                        });
                        return {id, target, points};
                    });
                    const samples = [];
                    function block(frame, scanOther, rounds) {
                        const {target, points} = frame;
                        target.scanNonJapaneseText = scanOther;
                        const expected = ['猫が本を読んでいる', '食べました', '𠮟られた', scanOther ? '彼はNew' : '彼は'];
                        const start = performance.now();
                        for (let i = 0; i < rounds; i++) for (let j = 0; j < points.length; j++) {
                            const selected = target.hoshiSelection.selectText(...points[j], 16);
                            if (selected !== expected[j]) throw new Error(frame.id + ': ' + selected + ' expected ' + expected[j] + ' at ' + points[j]);
                        }
                        return (performance.now() - start) * 1000 / (rounds * points.length);
                    }
                    for (const scanOther of [true, false]) {
                        for (const frame of frames) block(frame, scanOther, 100);
                        for (let pair = 0; pair < 10; pair++) {
                            for (const frame of pair % 2 ? [...frames].reverse() : frames) {
                                samples.push({variant: frame.id, scanOther, pair, usPerSelection: block(frame, scanOther, 200)});
                            }
                        }
                    }
                    return {samples};
                    } catch (error) { return {error: String(error), stack: error.stack}; }
                })()
            """)
            assertFalse(result.toString(), result.has("error"))
            assertEquals(40, result.getJSONArray("samples").length())
            File(context.cacheDir, "lookup-performance-web.json").writeText(result.toString())
            android.util.Log.i("HoshiLookupPerformance", "web=$result")
        } finally {
            compose.runOnIdle { (web.parent as? android.view.ViewGroup)?.removeView(web); web.destroy() }
        }
    }

    @Test fun readerAndRecursivePopupUseTheSelectedLanguagePolicy() {
        val context = instrumentation.targetContext
        val reader = ReaderWebAssets.load(context)
        val popup = LookupPopupAssets.load(context)
        lateinit var web: WebView
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { viewContext ->
                WebView(viewContext).apply { settings.javaScriptEnabled = true; web = this }
            })
        }
        compose.waitForIdle()
        try {
            for (profile in ContentLanguageProfile.Supported) {
                for (inPopup in listOf(false, true)) {
                    val html = if (inPopup) {
                        val popupHtml = LookupPopupHtml.renderIframeDocument(
                            contentLanguageProfile = profile,
                            settings = DictionarySettings(scanNonJapaneseText = false),
                        )
                        val srcdoc = popupHtml.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
                        """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head><body style="margin:0"><iframe id="fixture-popup" style="border:0;width:100%;height:400px" srcdoc="$srcdoc"></iframe></body></html>"""
                    } else {
                        """<html lang="${profile.htmlLang}"><body><script>
                            window.scanNonJapaneseText = false;
                            ${reader.selectionSupportJs(profile)}
                            ${reader.selectionJs}
                            window.hoshiSelection.configure({language: '${profile.id}'});
                        </script></body></html>"""
                    }
                    load(web, html, popup)
                    val (text, expected) = when (profile) {
                        ContentLanguageProfile.Chinese -> "学习汉语。" to "汉语"
                        ContentLanguageProfile.Korean -> "읽었어요 다음" to "어요"
                        ContentLanguageProfile.English -> "reading." to "reading"
                        else -> "食べました。" to "ました"
                    }
                    val result = evaluate(web, """
                        (() => {
                            const target = document.getElementById('fixture-popup')?.contentWindow || window;
                            const doc = target.document;
                            const p = doc.createElement('p');
                            p.id = 'fixture'; p.style.cssText = 'font:32px sans-serif;margin:20px';
                            p.textContent = ${JSONObject.quote(text)};
                            doc.body.prepend(p);
                            // Capture the production selection payload without calling any native repository.
                            target.hoshiSelection.postTextSelected = value => target.fixtureSelection = value;
                            const range = doc.createRange();
                            range.setStart(p.firstChild, 2); range.setEnd(p.firstChild, 3);
                            const rect = range.getBoundingClientRect();
                            const x = rect.x + rect.width / 2, y = rect.y + rect.height / 2;
                            const selected = target.hoshiSelection.selectText(x, y, 32);
                            return {selected, language: doc.documentElement.lang, sentence: target.fixtureSelection?.sentence};
                        })()
                    """)
                    val label = "${profile.id} popup=$inPopup $result"
                    assertEquals(label, expected, result.getString("selected"))
                    assertEquals(label, profile.htmlLang, result.getString("language"))
                    assertTrue(label, result.getString("sentence").contains(expected))
                }
            }
        } finally {
            compose.runOnIdle { (web.parent as? android.view.ViewGroup)?.removeView(web); web.destroy() }
        }
    }

    private fun load(web: WebView, html: String, popup: LookupPopupAssets, readyFrameId: String = "fixture-popup") {
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    lookupPopupAssetResponse(request.url.lastPathSegment.orEmpty(), popup)?.let { asset ->
                        WebResourceResponse(asset.mimeType, "UTF-8", asset.content.byteInputStream())
                    }

                override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
            }
            web.loadDataWithBaseURL("https://appassets.androidplatform.net/fixture/", html, "text/html", "UTF-8", null)
        }
        assertTrue("Page loaded", loaded.await(10, TimeUnit.SECONDS))
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            evaluate(web, """
                (() => {
                    const target = document.getElementById(${JSONObject.quote(readyFrameId)})?.contentWindow || window;
                    return {ready: target.innerHeight > 0 && !!target.hoshiSelection};
                })()
            """).getBoolean("ready")
        }
    }

    private fun evaluate(web: WebView, script: String): JSONObject {
        val completed = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; completed.countDown() } }
        assertTrue("JavaScript completed", completed.await(10, TimeUnit.SECONDS))
        return JSONObject(result)
    }
}
