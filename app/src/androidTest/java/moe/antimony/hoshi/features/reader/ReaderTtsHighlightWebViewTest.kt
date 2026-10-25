package moe.antimony.hoshi.features.reader

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.EpubResource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRange
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRevealSource
import moe.antimony.hoshi.features.tts.TtsPlaybackState
import moe.antimony.hoshi.features.tts.TtsSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses generated in-memory content only; never opens a book or changes preferences. */
@RunWith(AndroidJUnit4::class)
class ReaderTtsHighlightWebViewTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun imageHoldAndPassiveRestorePreserveTheChosenPage() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = ReaderWebAssets.load(instrumentation.targetContext)
        lateinit var webView: WebView
        composeRule.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).also { webView = it; it.settings.javaScriptEnabled = true }
            })
        }
        composeRule.waitForIdle()
        val json = Json { ignoreUnknownKeys = true }
        try {
            for (mode in ReaderViewMode.entries) for (vertical in listOf(false, true)) {
                val description = "$mode vertical=$vertical"
                val settings = ReaderSettings(viewMode = mode, verticalWriting = vertical, visualNovelRevealSpeed = 0)
                val loaded = CountDownLatch(1)
                val styles = ReaderContentStyles.styleTag(settings = settings, sasayakiBackgroundColor = 0xFF0000FF, readerCssTemplate = assets.readerCss)
                val script = ReaderPaginationScripts.shellScript(settings = settings, assets = assets)
                val beforeImage = "前です".repeat(300) + "。"
                val imageStyle = "display:block;break-before:column;break-after:column"
                val body = "<body><p>$beforeImage</p><img width='600' height='900' style='$imageStyle' src='divider.svg'/>" +
                    "<img width='600' height='900' style='$imageStyle' src='art.svg'/><p>後です。</p></body>"
                fun svg(color: String) = "<svg xmlns='http://www.w3.org/2000/svg' width='600' height='900'><rect width='600' height='900' fill='$color'/></svg>".toByteArray()
                val book = EpubBook("Image hold fixture", chapters = listOf(
                    EpubChapter("one", "one.xhtml", "application/xhtml+xml", body),
                    EpubChapter("two", "two.xhtml", "application/xhtml+xml", "<img src='divider.svg'/><img src='divider.svg'/><img src='art.svg'/>"),
                ), resources = mapOf("divider.svg" to EpubResource("image/svg+xml", svg("green")),
                    "art.svg" to EpubResource("image/svg+xml", svg("red"))))
                val bridge = ReaderWebResourceBridge(book, fontFileForRequest = { null })
                val references = bridge.resourceForUrl("https://appassets.androidplatform.net/reader-media/image-references.json")!!.data.decodeToString()
                assertEquals(3, book.imageReferenceCounts["divider.svg"])
                assertEquals(2, book.imageReferenceCounts["art.svg"])
                instrumentation.runOnMainSync {
                    val density = webView.resources.displayMetrics.density
                    val viewport = readerViewportCssLayout(settings,
                        (webView.width / density).toInt(), (webView.height / density).toInt()).cssVariables()
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                            bridge.resourceForUrl(request.url.toString())?.toWebResourceResponse()
                    }
                    webView.loadDataWithBaseURL("https://appassets.androidplatform.net/epub/one.xhtml",
                        "<!doctype html><html><head><script>window.hoshiReaderImageReferenceCounts=$references;window.HoshiReaderRestore={postMessage:()=>window.testRestored=true};</script>$styles<style>$viewport</style>$script</head>$body</html>",
                        "text/html", "utf-8", null)
                }
                assertTrue(description, loaded.await(15, TimeUnit.SECONDS))
                composeRule.waitUntil(15_000) { evaluate(webView, "Boolean(window.testRestored)") == "true" }
                evaluate(webView, "window.hoshiReader.buildNodeOffsets(); true")
                evaluate(webView, ReaderPaginationScripts.highlightSasayakiCueInvocation(SasayakiCueRange("previous", 0, 3), true))
                val sentence = TtsSentence(0, 900, 3, "後です。", imageBefore = true)
                val preparing = TtsPlaybackState(sentences = listOf(sentence), started = true, playing = true, preparing = true)
                val cue = SasayakiCueRange("tts-image-next", 900, 3)
                var shown = 0
                var firstStop: ReaderSasayakiMediaStop? = null
                val actions = object : ReaderPlaybackPresentationActions {
                    override suspend fun stopsBeforeCue() = json.decodeFromString(
                        ListSerializer(ReaderSasayakiMediaStop.serializer()),
                        evaluate(webView, ReaderPaginationScripts.sasayakiMediaStopsBeforeCueInvocation(cue)))
                    override suspend fun showStops(stops: List<ReaderSasayakiMediaStop>, countStatistics: Boolean) {
                        assertTrue("$description image stop discovered: " + evaluate(webView,
                            "JSON.stringify({image:document.querySelector('img').getBoundingClientRect(), current:window.hoshiReader.calculateProgress(), target:window.hoshiReader.sasayakiCueTargetScroll?.({id:'tts-image-next',start:900,length:3}), width:innerWidth,height:innerHeight})"), stops.isNotEmpty())
                        for (stop in stops) {
                            if (firstStop == null) firstStop = stop
                            evaluate(webView, ReaderPaginationScripts.showSasayakiMediaStopInvocation(
                                json.encodeToString(ReaderSasayakiMediaStop.serializer(), stop)))
                            // A restore/foreground callback during the hold must not reveal the next sentence.
                            val restoration = readerTtsPresentation(preparing, sentence, 0, autoScroll = true)
                            if (restoration == ReaderTtsPresentation.Follow) {
                                evaluate(webView, ReaderPaginationScripts.highlightSasayakiCueInvocation(cue, true))
                            }
                            composeRule.waitUntil("$description visible image", 5_000) { redPixelCount() > 1000 }
                            awaitReaderSasayakiImageHold(300, isFullscreenImageVisible = { false })
                            assertTrue("$description image retained for hold", redPixelCount() > 1000)
                            assertEquals("\"previous\"", evaluate(webView, "window.hoshiReader.activeCueId"))
                            shown++
                        }
                    }
                    override suspend fun reveal(reveal: Boolean): Double? = ReaderPaginationScripts.doubleResult(
                        evaluate(webView, ReaderPaginationScripts.highlightSasayakiCueInvocation(cue, reveal)))
                    override suspend fun stopsToChapterEnd(): List<ReaderSasayakiMediaStop> = error("Same chapter")
                    override suspend fun loadChapter(chapterIndex: Int): Boolean = error("Same chapter")
                    override suspend fun restoreTarget(inspectMedia: Boolean, fromChapter: Int): ReaderPlaybackTargetRestoreResult = error("Same chapter")
                    override suspend fun holdVisibleStop(progress: Double?) = error("Same chapter")
                }
                revealReaderPlaybackWithMediaStops(0, true, SasayakiCueRevealSource.NaturalPlayback, 300, { 0 }, actions)
                assertEquals("$description only the ordinary illustration holds", 1, shown)
                assertEquals("\"tts-image-next\"", evaluate(webView, "window.hoshiReader.activeCueId"))
                composeRule.waitUntil("$description sentence after image", 5_000) { bluePixelCount() > 100 }

                // The user browses back to the illustration while this sentence is playing.
                evaluate(webView, ReaderPaginationScripts.showSasayakiMediaStopInvocation(
                    json.encodeToString(ReaderSasayakiMediaStop.serializer(), requireNotNull(firstStop))))
                composeRule.waitUntil("$description manually chosen image", 5_000) { redPixelCount() > 1000 }
                val chosenProgress = ReaderPaginationScripts.doubleResult(evaluate(webView, ReaderPaginationScripts.progressInvocation()))
                val restored = readerTtsPresentation(preparing.copy(preparing = false), sentence, 0,
                    autoScroll = true, passiveRestore = true)
                assertEquals(ReaderTtsPresentation.Highlight, restored)
                evaluate(webView, ReaderPaginationScripts.highlightSasayakiCueInvocation(cue,
                    reveal = restored == ReaderTtsPresentation.Follow, preserveReveal = true))
                assertEquals("$description restore preserves the chosen page", chosenProgress,
                    ReaderPaginationScripts.doubleResult(evaluate(webView, ReaderPaginationScripts.progressInvocation())))
                assertTrue("$description restore does not reveal the narration", redPixelCount() > 1000)
            }
        } finally { instrumentation.runOnMainSync {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        } }
    }

    @Test
    fun synthesizedSentenceRangesPaintAndClearInEveryModeAndWritingDirection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = ReaderWebAssets.load(instrumentation.targetContext)
        lateinit var webView: WebView
        composeRule.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).also {
                    webView = it
                    it.settings.javaScriptEnabled = true
                }
            })
        }
        composeRule.waitForIdle()
        try {
            for (mode in ReaderViewMode.entries) for (vertical in listOf(false, true)) {
                val description = "$mode vertical=$vertical"
                val settings = ReaderSettings(viewMode = mode, verticalWriting = vertical, visualNovelRevealSpeed = 0)
                val loaded = CountDownLatch(1)
                val styles = ReaderContentStyles.styleTag(settings = settings, sasayakiBackgroundColor = 0xFF0000FF, readerCssTemplate = assets.readerCss)
                val script = ReaderPaginationScripts.shellScript(settings = settings, assets = assets)
                instrumentation.runOnMainSync {
                    val density = webView.resources.displayMetrics.density
                    val viewport = readerViewportCssLayout(settings,
                        (webView.width / density).toInt(), (webView.height / density).toInt()).cssVariables()
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                    }
                    webView.loadDataWithBaseURL(null,
                        "<!doctype html><html><head><script>window.HoshiReaderRestore={postMessage:()=>window.testRestored=true};</script>$styles<style>$viewport</style>$script</head>" +
                            "<body><p>前「𠮟 、<ruby>猫<rt>ねこ</rt></ruby>。」後</p></body></html>",
                        "text/html", "utf-8", null)
                }
                assertTrue(description, loaded.await(15, TimeUnit.SECONDS))
                composeRule.waitUntil(15_000) {
                    evaluate(webView, "Boolean(window.testRestored)") == "true"
                }
                // Wait for production restore (including the VN clone and font layout).
                evaluate(webView, "window.hoshiReader.buildNodeOffsets(); true")
                val baseline = bluePixelCount()
                val sentences = moe.antimony.hoshi.features.tts.TtsText.chapter(0,
                    "<p>前「𠮟 、<ruby>猫<rt>ねこ</rt></ruby>。」後</p>")
                assertEquals(listOf("前", "「𠮟 、猫。」", "後"), sentences.map { it.text })
                assertEquals("「𠮟 、ねこ。」", moe.antimony.hoshi.features.tts.TtsText.spoken(sentences[1], null, emptyList()))
                // Revisit the dialogue after the following narration, just as the previous/repeat controls do.
                for (index in listOf(0, 1, 2, 1)) {
                    val sentence = sentences[index]
                    val cue = SasayakiCueRange("tts-fixture-$index", sentence.start, sentence.length)
                    val sourceText = evaluate(webView, "window.hoshiReader.collectSasayakiCueRanges([{id:'probe',start:${sentence.start},length:${sentence.length}}])[0].ranges.map(s=>s.node.textContent.slice(s.start,s.end)).join('')")
                    // Matchable characters must come from this unit, never its preceding/following narration.
                    val expected = listOf("前", "𠮟猫", "後")[index]
                    assertEquals(description, expected, Json.decodeFromString<String>(sourceText).filterNot { it in "「」、。 " })
                    evaluate(webView, ReaderPaginationScripts.highlightSasayakiCueInvocation(cue, true))
                    composeRule.waitUntil("$description unit $index paint", 10_000) { bluePixelCount() > baseline + 100 }
                    assertEquals(description, "\"tts-fixture-$index\"", evaluate(webView, "window.hoshiReader.activeCueId"))
                    evaluate(webView, ReaderPaginationScripts.clearSasayakiCueInvocation())
                    composeRule.waitUntil("$description clear paint", 10_000) { bluePixelCount() <= baseline + 20 }
                }
            }
        } finally {
            instrumentation.runOnMainSync { webView.destroy() }
        }
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

    private fun bluePixelCount(): Int {
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.blue > color.red + 0.12f && color.blue > color.green + 0.05f) count++
        }
        return count
    }

    private fun redPixelCount(): Int {
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.red > 0.8f && color.green < 0.2f && color.blue < 0.2f) count++
        }
        return count
    }
}
