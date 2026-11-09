package moe.antimony.hoshi.features.reader

import android.os.Build
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.net.URI
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.EpubResource
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in paired web-runtime benchmark. Generated books and test-owned cache only. */
class ReaderPagePerformanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun measureNativeIndexLookupScaling() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pagePerformance") == "true")
        val samples = JSONArray()
        for (pages in listOf(1_000, 10_000, 100_000)) {
            val perSpine = pages / 10
            val count = perSpine * 100
            val book = EpubBook("Index fixture", chapters = (0..9).map { EpubChapter("$it", "$it", "text/html", "") },
                bookInfo = BookInfo(count * 10, (0..9).associate { "$it" to BookInfo.ChapterInfo(it, it * count, count) }))
            val index = requireNotNull(ReaderPageIndex.create(book, List(10) { List(perSpine) { it * 100 } }))
            val range = ReaderTocRange(count, count * 2)
            repeat(100) { index.progress(ReaderChapterPosition(1, 0.5), perSpine / 2, range) }
            val started = SystemClock.elapsedRealtimeNanos()
            var sum = 0
            repeat(1_000) { sum += index.progress(ReaderChapterPosition(1, 0.5), perSpine / 2, range).current }
            assertTrue(sum > 0)
            samples.put(JSONObject().put("pages", pages).put("msPerProgress", (SystemClock.elapsedRealtimeNanos() - started) / 1e9))
        }
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "reader-page-index-performance.json").writeText(samples.toString(2))
    }

    @Test fun compareBaselineDisabledReadyAndActiveMeasurement() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("pagePerformance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val baseline = File(context.cacheDir, "reader-page-performance-baseline/reader-paginated.js")
        assertTrue("Install the baseline asset as described in VALIDATION", baseline.isFile)
        val fixtureRoot = File(context.cacheDir, "reader-page-throughput")
        val scratch = File(context.cacheDir, "page-performance-${UUID.randomUUID()}")
        try {
            val currentAssets = ReaderWebAssets.load(context)
            val oldAssets = currentAssets.copy(readerPaginatedJs = baseline.readText())
            val paragraphs = arguments.getString("paragraphs")?.toInt() ?: 800
            val rounds = arguments.getString("rounds")?.toInt() ?: 4
            val progress = arguments.getString("progress")?.toDouble() ?: 0.0
            require(progress in 0.0..0.9)
            val variants = arguments.getString("variants")?.split(',') ?: listOf("baseline", "disabled", "ready", "measuring")
            require(variants.isNotEmpty() && variants.all { it in listOf("baseline", "disabled", "ready", "measuring") })
            val directions = arguments.getString("vertical")?.toBooleanStrict()?.let(::listOf) ?: listOf(true, false)
            val paragraphModes = arguments.getString("paragraph")?.toBooleanStrict()?.let(::listOf) ?: listOf(false, true)
            val html = "<!doctype html><html><head></head><body>" +
                (1..paragraphs).joinToString("") { "<p style='margin:0'>「<ruby>読書<rt>どくしょ</rt></ruby>𠮟る。日本語の文章です。今日も本を読みます。朝から図書館で物語を読み、知らない世界へ旅に出かける。」</p>" } + "</body></html>"
            val book = if (arguments.getString("bookFixture") == "true") withContext(Dispatchers.IO) {
                EpubBookParser(scratch).parsePacked(File(fixtureRoot, "book.epub"), cacheRoot = scratch)
            } else EpubBook("Page performance", chapters = (0..5).map { EpubChapter("$it", "$it.html", "text/html", html) },
                resources = (0..5).associate { "$it.html" to EpubResource("text/html", html.toByteArray()) })
            withContext(Dispatchers.IO) { book.bookInfo; book.imageReferenceCounts }
            lateinit var host: FrameLayout
            val viewportHeight = arguments.getString("viewportHeight")?.toInt()
            val openingSpine = arguments.getString("openingSpine")?.toInt() ?: 0
            val navigationSpine = arguments.getString("navigationSpine")?.toInt() ?: 1
            val font = if (arguments.getString("fontFixture") == "true") ReaderFontRenderSpec(
                familyId = "fixture:kleeone", variantId = "wght-600-normal", cssFamily = "HoshiFixtureKleeOne",
                displayName = "Klee One", weight = 600, italic = false,
                faces = listOf(ReaderFontFace("https://appassets.androidplatform.net/fonts/KleeOne-Regular.ttf", 400, false),
                    ReaderFontFace("https://appassets.androidplatform.net/fonts/KleeOne-SemiBold.ttf", 600, false)),
            ) else null
            val fontFile: (String) -> File? = { name ->
                if (font?.faces?.any { it.url.endsWith("/" + name) } == true) File(fixtureRoot, name) else null
            }
            compose.setContent { AndroidView(modifier = if (viewportHeight == null) Modifier.fillMaxSize() else Modifier.height(viewportHeight.dp).fillMaxSize(), factory = { FrameLayout(it).also { host = it; it.keepScreenOn = true } }) }
            compose.waitForIdle()
            val report = JSONObject().put("api", Build.VERSION.SDK_INT).put("device", Build.MODEL)
                .put("webView", WebViewCompat.getCurrentWebViewPackage(context)?.versionName)
                .put("paragraphsPerSpine", paragraphs).put("spines", book.chapters.size)
                .put("openingSpine", openingSpine).put("navigationSpine", navigationSpine).put("font", font?.displayName ?: "publisher").put("rounds", rounds)
                .put("variants", JSONArray(variants)).put("directions", JSONArray(directions)).put("paragraphModes", JSONArray(paragraphModes))
                .put("baseline", baseline.readText().hashCode()).put("charactersPerSpine", book.bookInfo.chapterInfo.getValue(book.chapters[navigationSpine].href).chapterCount)
            val samples = JSONArray()
            report.put("samples", samples)
            val output = File(context.cacheDir, "reader-page-performance.json")
            try {
                withContext(Dispatchers.Main) {
                    for (paragraph in paragraphModes) for (vertical in directions) {
                        for (round in 0 until rounds) {
                            val order = variants.drop(round % variants.size) + variants.take(round % variants.size)
                            for (variant in order) {
                                val settings = ReaderSettings(verticalWriting = vertical, paragraphMode = paragraph,
                                    selectedFont = font?.displayName ?: ReaderFontManager.publisherFont,
                                    fontSize = arguments.getString("fontSize")?.toInt() ?: ReaderSettings().fontSize,
                                    horizontalPadding = arguments.getString("horizontalPadding")?.toInt() ?: ReaderSettings().horizontalPadding,
                                    textAnimation = false,
                                    progressCount = if (variant in listOf("ready", "measuring")) ReaderProgressCount.Pages else ReaderProgressCount.Characters)
                                val assets = if (variant == "baseline") oldAssets else currentAssets
                                val sample = runSample(host, book, assets, currentAssets, settings, variant, arguments.getString("idleCompletion") == "true", font, fontFile, openingSpine, navigationSpine, progress)
                                    .put("round", round).put("warmup", round == 0).put("variant", variant)
                                    .put("vertical", vertical).put("paragraph", paragraph).put("initialProgress", progress)
                                samples.put(sample)
                                withContext(Dispatchers.IO) { output.writeText(report.toString(2)) }
                                android.util.Log.i("HoshiPagePerformance", "$variant vertical=$vertical paragraph=$paragraph round=$round readyMs=${sample.getJSONObject("navigation").getDouble("readyMs")}")
                                delay(100)
                            }
                        }
                    }
                }
            } finally { withContext(Dispatchers.IO) { output.writeText(report.toString(2)) } }
        } finally { withContext(Dispatchers.IO) { scratch.deleteRecursively() } }
    }

    private suspend fun runSample(host: FrameLayout, book: EpubBook, assets: ReaderWebAssets,
        measurementAssets: ReaderWebAssets, settings: ReaderSettings, variant: String, idleCompletion: Boolean,
        font: ReaderFontRenderSpec?, fontFile: (String) -> File?, openingSpine: Int, navigationSpine: Int, progress: Double): JSONObject = coroutineScope {
        val density = host.resources.displayMetrics.density
        val size = IntSize((host.width / density).toInt(), (host.height / density).toInt())
        var pendingReady = CompletableDeferred<Unit>()
        var token = "initial"
        var setupGenerationMs = 0.0
        var pageCallbacks = 0
        val resources = ReaderWebResourceBridge(book, fontFile)
        val visible = WebView(host.context).apply {
            applyHoshiWebViewSecurityDefaults()
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, assets.readerDocumentPreparationJs, setOf("https://appassets.androidplatform.net"))
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun postMessage(value: String) { post { if (value == token) pendingReady.complete(Unit) } }
            }, "HoshiReaderRestore")
            if (variant != "baseline") addJavascriptInterface(object {
                @JavascriptInterface fun postMessage(value: String, page: Int) { post { if (value == token && page >= 0) pageCallbacks++ } }
            }, "HoshiReaderPage")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    resources.resourceForUrl(request.url.toString())?.toWebResourceResponse()
                override fun onPageFinished(view: WebView, url: String) {
                    val start = SystemClock.elapsedRealtimeNanos()
                    val script = readerSetupScript(progress, null, settings, null, font, false, false,
                        ContentLanguageProfile.Japanese, size, 0L, 0L, null, null, token, assets)
                    setupGenerationMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                    view.evaluateReaderSetupScript(script + METRICS, token)
                }
            }
        }
        host.addView(visible, ViewGroup.LayoutParams(-1, -1))
        visible.measure(android.view.View.MeasureSpec.makeMeasureSpec(host.width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(host.height, android.view.View.MeasureSpec.EXACTLY))
        visible.layout(0, 0, host.width, host.height)
        var measurer: ReaderPageMeasurer? = null
        var measurement: Job? = null
        var completedSpines = 0
        val scheduler = ReaderPageMeasurementScheduler()
        fun startMeasurement() {
            measurer = ReaderPageMeasurer(host, book, measurementAssets, fontFile, scheduler) { measureToken, resources ->
                readerSetupScript(0.0, null, settings.copy(textAnimation = false), null, resources.font(font), false, false,
                    ContentLanguageProfile.Japanese, size, 0L, 0L, null, null, measureToken, measurementAssets, measurementOnly = true) + """
                    (()=>{window.fixtureMeasurementTimes={};
                    const trace=(target,name)=>{const original=target[name];target[name]=function(...args){
                    const start=performance.now();try{return original.apply(this,args);}finally{
                    fixtureMeasurementTimes[name]=(fixtureMeasurementTimes[name]||0)+performance.now()-start;}};};
                    ['normalizeRubyTextNodes','stabilizeRubyAdjacentTextNodes','buildNodeOffsets'].forEach(name=>trace(hoshiReader,name));
                    ['layoutParagraphs','fragmentBlocks'].forEach(name=>trace(hoshiParagraph,name));
                    const original=hoshiReader.paginationMetricSteps;
                    hoshiReader.paginationMetricSteps=function*(){const steps=original.call(this);let step;
                    do {const start=performance.now();step=steps.next();const elapsed=performance.now()-start;
                    fixtureMeasurementTimes.metricTotalMs=(fixtureMeasurementTimes.metricTotalMs||0)+elapsed;
                    fixtureMeasurementTimes.metricMaxBatchMs=Math.max(fixtureMeasurementTimes.metricMaxBatchMs||0,elapsed);
                    window.fixtureMeasurementBatches=(window.fixtureMeasurementBatches||0)+1;
                    if(!step.done) yield;} while(!step.done);return step.value;};})();
                """.trimIndent()
            }
            measurement = launch { for (spine in completedSpines until book.chapters.size) { measurer!!.measure(spine); completedSpines++ } }
        }
        suspend fun load(spine: Int, resumeMeasurement: Boolean = false): JSONObject {
            val start = SystemClock.elapsedRealtimeNanos()
            scheduler.setReaderReady(false)
            if (measurement != null) {
                measurement?.cancelAndJoin()
                measurer?.close()
                measurer = null
                measurement = null
            }
            token = "chapter-$spine"
            pendingReady = CompletableDeferred()
            visible.loadUrl(URI("https", "appassets.androidplatform.net", "/epub/${book.chapters[spine].href}", null, null).toASCIIString())
            withTimeout(30_000) { pendingReady.await() }
            val readyMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            visualState(visible)
            val visualMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            // ReaderChapterWebView delivers the ready cue snapshot after visible restore.
            // Include that native path: bypassing it hid a redundant empty-cue index rebuild.
            val cueStarted = SystemClock.elapsedRealtimeNanos()
            val cuesReady = CompletableDeferred<Unit>()
            visible.applyReaderSasayakiCues(token, "[]") { cuesReady.complete(Unit) }
            withTimeout(30_000) { cuesReady.await() }
            val cueInstallMs = (SystemClock.elapsedRealtimeNanos() - cueStarted) / 1e6
            scheduler.setReaderReady(true)
            if (variant == "measuring" && resumeMeasurement) startMeasurement()
            val js = JSONObject(evaluate(visible, "window.fixtureRestoreMetrics"))
            return js.put("readyMs", readyMs).put("visualStateMs", visualMs).put("setupGenerationMs", setupGenerationMs)
                .put("postRestoreCueMs", cueInstallMs)
        }
        try {
            val opening = load(openingSpine)
            if (variant == "measuring") {
                startMeasurement()
                delay(1_100)
            }
            val navigation = load(navigationSpine, resumeMeasurement = true)
            val turns = JSONArray()
            repeat(25) {
                scheduler.onForegroundActivity()
                val active = measurement?.isActive == true
                val start = SystemClock.elapsedRealtimeNanos()
                val js = JSONObject(evaluate(visible, """
                    (()=>{let t=performance.now();let p=hoshiReader.paginateWithProgress('forward');
                    return {jsMs:performance.now()-t,progress:p};})()
                """.trimIndent()))
                val responseMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                assertTrue("fixture must have enough pages", js.get("progress") is Number)
                visualState(visible)
                turns.put(js.put("responseMs", responseMs).put("visualStateMs", (SystemClock.elapsedRealtimeNanos() - start) / 1e6)
                    .put("measurementActive", active))
                delay(16)
            }
            val interruptions = JSONArray()
            var idleCompletionMs: Double? = null
            if (idleCompletion && measurement != null) {
                val hidden = host.getChildAt(1) as WebView
                val started = SystemClock.elapsedRealtimeNanos()
                repeat(3) {
                    val before = evaluate(hidden, "Number(window.fixtureMeasurementBatches||0)").toInt()
                    val spineBefore = completedSpines
                    withTimeout(30_000) {
                        while (true) {
                            val batches = evaluate(hidden, "Number(window.fixtureMeasurementBatches||0)").toInt()
                            if (batches > 0 && (completedSpines != spineBefore || batches > before)) break
                            delay(16)
                        }
                    }
                    // Interrupt an actual geometry scan, not just a queued cold load.
                    scheduler.onForegroundActivity()
                    val turnStarted = SystemClock.elapsedRealtimeNanos()
                    evaluate(visible, "hoshiReader.paginateWithProgress('forward')")
                    val responseMs = (SystemClock.elapsedRealtimeNanos() - turnStarted) / 1e6
                    val pausedAt = evaluate(hidden, "Number(window.fixtureMeasurementBatches||0)").toInt()
                    delay(150)
                    assertEquals("foreground work must stop further measurement batches", pausedAt,
                        evaluate(hidden, "Number(window.fixtureMeasurementBatches||0)").toInt())
                    interruptions.put(JSONObject().put("responseMs", responseMs).put("pausedAtBatch", pausedAt))
                }
                withTimeout(180_000) { requireNotNull(measurement).join() }
                assertEquals(book.chapters.size, completedSpines)
                idleCompletionMs = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
            }
            val measurementTimes = if (measurer != null) JSONObject(evaluate(host.getChildAt(1) as WebView,
                "window.fixtureMeasurementTimes||{}")) else JSONObject()
            val frames = JSONArray(evaluate(visible, "window.fixtureFrames"))
            JSONObject().put("opening", opening).put("navigation", navigation).put("turns", turns)
                .put("viewport", "$size").put("fontSize", settings.fontSize).put("horizontalPadding", settings.horizontalPadding)
                .put("frames", frames).put("measurementTimes", measurementTimes).put("interruptions", interruptions).put("idleCompletionMs", idleCompletionMs).put("completedSpines", completedSpines).put("pageCallbacks", pageCallbacks)
        } finally {
            measurement?.cancelAndJoin()
            measurer?.close()
            host.removeView(visible)
            visible.destroy()
        }
    }

    private suspend fun evaluate(view: WebView, script: String): String = withTimeout(30_000) {
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it) }
        }
    }

    private suspend fun visualState(view: WebView) = withTimeout(30_000) {
        suspendCancellableCoroutine { continuation ->
            view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { if (continuation.isActive) continuation.resume(Unit) }
            })
        }
    }

    companion object {
        private val METRICS = """
            window.fixtureMetricMs=0; window.fixtureMetricBuilds=0;
            const build=hoshiReader.buildPaginationMetrics;
            hoshiReader.buildPaginationMetrics=function(){let t=performance.now();try{return build.call(this);}
              finally{window.fixtureMetricMs+=performance.now()-t;window.fixtureMetricBuilds++;}};
            const restored=hoshiReader.notifyRestoreComplete;
            hoshiReader.notifyRestoreComplete=function(){let t=performance.now();restored.call(this);
              window.fixtureRestoreMetrics={notifyMs:performance.now()-t,metricMs:window.fixtureMetricMs,metricBuilds:window.fixtureMetricBuilds};};
            window.fixtureFrames=[]; let lastFrame;
            function frame(t){if(lastFrame!==undefined)fixtureFrames.push(t-lastFrame);lastFrame=t;if(fixtureFrames.length<2000)requestAnimationFrame(frame);}
            requestAnimationFrame(frame);
        """.trimIndent()
    }
}
