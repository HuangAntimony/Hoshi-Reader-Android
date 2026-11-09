package moe.antimony.hoshi.features.reader

import android.os.SystemClock
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.epub.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in idle throughput comparison. Reads only a test-owned fixture copy, never the library. */
class ReaderPageThroughputTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wholeBookCountsMatchWhileMeasuringIdleThroughput() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("pageThroughput") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureRoot = File(context.cacheDir, "reader-page-throughput")
        val scratch = File(context.cacheDir, "page-throughput-${UUID.randomUUID()}").apply { mkdirs() }
        val samples = JSONArray()
        val report = JSONObject().put("webView", WebViewCompat.getCurrentWebViewPackage(context)?.versionName).put("samples", samples)
        try {
            val assets = ReaderWebAssets.load(context)
            val baseline = assets.copy(readerPaginatedJs = File(fixtureRoot, "baseline.js").readText())
            val book = withContext(Dispatchers.IO) {
                if (arguments.getString("bookFixture") == "true") {
                    EpubBookParser(scratch).parsePacked(File(fixtureRoot, "book.epub"), cacheRoot = scratch)
                } else {
                    val html = "<html><head></head><body>" +
                        "<p>「<ruby>読書<rt>どくしょ</rt></ruby>𠮟る。日本語の文章です。今日も本を読みます。朝から図書館で物語を読み、知らない世界へ旅に出かける。」</p>".repeat(800) + "</body></html>"
                    EpubBook("Throughput fixture", chapters = (0..5).map { EpubChapter("$it", "$it.html", "text/html", html) },
                        resources = (0..5).associate { "$it.html" to EpubResource("text/html", html.toByteArray()) })
                }.also { it.bookInfo; it.imageReferenceCounts }
            }
            report.put("spines", book.chapters.size).put("characters", book.bookInfo.characterCount)
            lateinit var host: FrameLayout
            val viewportHeight = arguments.getString("viewportHeight")?.toInt()
            compose.setContent { AndroidView(modifier = if (viewportHeight == null) Modifier.fillMaxSize() else Modifier.height(viewportHeight.dp).fillMaxSize(),
                factory = { FrameLayout(it).also { host = it; it.keepScreenOn = true } }) }
            compose.waitForIdle()
            val settings = ReaderSettings(verticalWriting = arguments.getString("vertical")?.toBooleanStrict() ?: true,
                paragraphMode = arguments.getString("paragraph")?.toBooleanStrict() ?: true,
                fontSize = arguments.getString("fontSize")?.toInt() ?: 43,
                horizontalPadding = arguments.getString("horizontalPadding")?.toInt() ?: 5,
                selectedFont = if (arguments.getString("fontFixture") == "true") "Klee One" else ReaderFontManager.publisherFont,
                progressCount = ReaderProgressCount.Pages, textAnimation = false)
            val font = if (arguments.getString("fontFixture") == "true") ReaderFontRenderSpec(
                familyId = "fixture:kleeone", variantId = "wght-600-normal", cssFamily = "HoshiFixtureKleeOne",
                displayName = "Klee One", weight = 600, italic = false,
                faces = listOf(ReaderFontFace("https://appassets.androidplatform.net/fonts/KleeOne-Regular.ttf", 400, false),
                    ReaderFontFace("https://appassets.androidplatform.net/fonts/KleeOne-SemiBold.ttf", 600, false)),
            ) else null
            report.put("font", font?.displayName ?: "publisher").put("fontSize", settings.fontSize).put("horizontalPadding", settings.horizontalPadding)
            val referenceFile = File(fixtureRoot, "reference-pages.json")
            var expected: List<List<Int>>? = if (arguments.getString("compareReference") == "true")
                withContext(Dispatchers.IO) { Json.decodeFromString(referenceFile.readText()) } else null
            withContext(Dispatchers.Main) {
                val density = host.resources.displayMetrics.density
                val size = IntSize((host.width / density).toInt(), (host.height / density).toInt())
                report.put("viewport", "$size").put("paragraph", settings.paragraphMode).put("vertical", settings.verticalWriting)
                val requested = arguments.getString("variants")?.split(',') ?: listOf("baseline", "current")
                require(requested.isNotEmpty() && requested.all { it in listOf("baseline", "current", "fallback", "unpaced") })
                repeat(arguments.getString("rounds")?.toInt() ?: 2) { round ->
                    val variants = requested.drop(round % requested.size) + requested.take(round % requested.size)
                    for (variant in variants) {
                        val source = if (variant == "baseline") baseline else assets
                        val scheduler = ReaderPageMeasurementScheduler(quietPeriodMillis = if (variant == "unpaced") 0 else 1_000)
                        scheduler.setReaderReady(true)
                        val started = SystemClock.elapsedRealtimeNanos()
                        val fontRequests = AtomicInteger()
                        val sampleFont = font?.copy(faces = font.faces.map { it.copy(url = it.url + "?sample=" + UUID.randomUUID()) })
                        val measurer = ReaderPageMeasurer(host, book, source, { name ->
                            if (font?.faces?.any { it.url.endsWith("/" + name) } == true) {
                                fontRequests.incrementAndGet()
                                File(fixtureRoot, name)
                            } else null
                        }, scheduler) { token, resources ->
                            (if (variant == "fallback") "Object.defineProperty(window,'scheduler',{value:undefined,configurable:true});" else "") +
                            readerSetupScript(0.0, null, settings, null, resources.font(sampleFont), false, false,
                                ContentLanguageProfile.Japanese, size, 0L, 0L, null, null, token, source, measurementOnly = true) +
                                (if (variant == "unpaced") "hoshiReader.measurementYield=()=>Promise.resolve();" else "") + TRACE
                        }
                        val offsets = mutableListOf<List<Int>>()
                        val spines = JSONArray()
                        try {
                            for (spine in book.chapters.indices) {
                                val spineStarted = SystemClock.elapsedRealtimeNanos()
                                offsets += measurer.measure(spine)
                                val elapsed = (SystemClock.elapsedRealtimeNanos() - spineStarted) / 1e6
                                val trace = JSONObject(evaluate(host.getChildAt(0) as WebView, "window.fixtureThroughput||{}"))
                                spines.put(trace.put("index", spine).put("elapsedMs", elapsed).put("pages", offsets.last().size))
                            }
                            if (expected == null) expected = offsets else assertEquals("all page starts must match", expected, offsets)
                            if (arguments.getString("captureReference") == "true") withContext(Dispatchers.IO) {
                                referenceFile.writeText(Json.encodeToString(offsets))
                            }
                            val sample = JSONObject().put("variant", variant).put("round", round).put("spines", spines).put("fontRequests", fontRequests.get())
                                .put("totalMs", (SystemClock.elapsedRealtimeNanos() - started) / 1e6)
                            samples.put(sample)
                            android.util.Log.i("HoshiPageThroughput", "$variant round=$round totalMs=${sample.getDouble("totalMs")}")
                        } finally { measurer.close() }
                        assertEquals(0, host.childCount)
                    }
                }
            }
        } finally {
            withContext(Dispatchers.IO) {
                File(context.cacheDir, "reader-page-throughput-results.json").writeText(report.toString(2))
                scratch.deleteRecursively()
            }
        }
    }

    private suspend fun evaluate(view: WebView, source: String): String = withTimeout(10_000) {
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(source) { if (continuation.isActive) continuation.resume(it) }
        }
    }

    companion object {
        private val TRACE = """
            window.fixtureThroughput={yieldCalls:0,yieldWaitMs:0,metricMs:0,setupStarted:performance.now(),
                loadMs:performance.getEntriesByType('navigation')[0]?.loadEventEnd};
            const trace=(target,name)=>{const original=target[name];target[name]=function(...args){
                const t=performance.now();try{return original.apply(this,args);}finally{
                    fixtureThroughput[name]=(fixtureThroughput[name]||0)+performance.now()-t;}};};
            ['normalizeRubyTextNodes','stabilizeRubyAdjacentTextNodes','buildNodeOffsets'].forEach(name=>trace(hoshiReader,name));
            ['layoutParagraphs','fragmentBlocks'].forEach(name=>trace(hoshiParagraph,name));
            const originalYield=hoshiReader.measurementYield;
            hoshiReader.measurementYield=async function(){const t=performance.now();fixtureThroughput.yieldCalls++;
                await originalYield.call(this);fixtureThroughput.yieldWaitMs+=performance.now()-t;};
            const originalSteps=hoshiReader.paginationMetricSteps;
            hoshiReader.paginationMetricSteps=function*(){const steps=originalSteps.call(this);let step;
                do {const t=performance.now();step=steps.next();fixtureThroughput.metricMs+=performance.now()-t;
                    if(!step.done)yield;} while(!step.done);return step.value;};
        """.trimIndent()
    }
}
