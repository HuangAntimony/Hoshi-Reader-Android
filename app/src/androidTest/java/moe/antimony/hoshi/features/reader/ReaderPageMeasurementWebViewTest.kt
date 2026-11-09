package moe.antimony.hoshi.features.reader

import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.onSizeChanged
import java.io.File
import moe.antimony.hoshi.epub.BookRepository
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.EpubResource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic books only; no saved profiles, library or playback services. */
class ReaderPageMeasurementWebViewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oldPageCallbacksAreRejectedWhileTheNextChapterWaitsToLoad() = runBlocking {
        var expectedToken = "old-chapter"
        val pages = mutableListOf<Int>()
        lateinit var view: HoshiReaderWebView
        val loaded = CompletableDeferred<Unit>()
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                HoshiReaderWebView(context).also {
                    view = it
                    it.applyHoshiWebViewSecurityDefaults()
                    it.tag = "old-chapter"
                    it.addJavascriptInterface(ReaderPageBridge(it, { expectedToken }, { true }, pages::add), "HoshiReaderPage")
                    it.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) { loaded.complete(Unit) }
                    }
                    it.loadData("<html><body>fixture</body></html>", "text/html", "UTF-8")
                }
            })
        }
        try {
            withTimeout(10_000) { loaded.await() }
            withContext(Dispatchers.Main) { evaluate(view, "HoshiReaderPage.postMessage('old-chapter', 4)") }
            compose.waitUntil(5_000) { pages.size == 1 }
            compose.runOnIdle {
                expectedToken = "next-chapter"
                pages.clear()
                // Cues are not ready: the loaded tag still belongs to the old chapter.
                assertEquals("old-chapter", view.tag)
            }
            withContext(Dispatchers.Main) { evaluate(view, "HoshiReaderPage.postMessage('old-chapter', 4)") }
            compose.waitForIdle()
            compose.runOnIdle { assertTrue("old document must not report a page for the next chapter", pages.isEmpty()) }
            compose.runOnIdle { view.tag = "next-chapter" }
            withContext(Dispatchers.Main) { evaluate(view, "HoshiReaderPage.postMessage('old-chapter', 4); HoshiReaderPage.postMessage('next-chapter', 0)") }
            compose.waitUntil(5_000) { pages.isNotEmpty() }
            compose.runOnIdle { assertEquals(listOf(0), pages) }
        } finally {
            withContext(Dispatchers.Main) {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
        }
    }

    /** Run capture and verify in separate instrumentation processes with the same test-owned run ID. */
    @Test fun diskCacheSurvivesProcessRestartWithoutStartingMeasurement() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("pageCacheRestart")
        org.junit.Assume.assumeTrue(phase == "capture" || phase == "verify")
        val runId = requireNotNull(args.getString("pageCacheRun")).also { require(it.matches(Regex("[a-zA-Z0-9-]+"))) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scratch = File(context.cacheDir, "page-cache-restart-$runId").apply { mkdirs() }
        val html = "<html><head></head><body><p>" + "日本語の本を読む時間です。".repeat(60) + "</p></body></html>"
        val book = EpubBook("Restart fixture", chapters = listOf(EpubChapter("a", "a.html", "text/html", html)),
            resources = mapOf("a.html" to EpubResource("text/html", html.toByteArray())))
        val fonts = ReaderFontManager(scratch)
        val repository = ReaderPageCountRepository(scratch, Dispatchers.IO)
        val books = BookRepository(scratch)
        val scheduler = ReaderPageMeasurementScheduler(60_000)
        if (phase == "verify") scheduler.onForegroundActivity() // A cache miss cannot finish this test.
        var viewport by mutableStateOf(IntSize.Zero)
        var counts by mutableStateOf(ReaderPageCounts())
        var open by mutableStateOf(true)
        lateinit var root: android.view.View
        var passed = false
        try {
            compose.setContent {
                root = androidx.compose.ui.platform.LocalView.current
                root.keepScreenOn = true
                Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }) {
                    if (open) ReaderPageCounter(book, null, "fixture", ReaderSettings(
                        selectedFont = ReaderFontManager.publisherFont, progressCount = ReaderProgressCount.Pages),
                        viewport, phase == "capture", ContentLanguageProfile.Japanese, fonts, repository, books,
                        { counts = it }, Modifier.fillMaxSize(), scheduler)
                }
            }
            compose.waitUntil(if (phase == "capture") 20_000 else 5_000) { counts.index != null || counts.failed }
            assertFalse(counts.failed)
            assertNotNull(counts.index)
            val cache = File(scratch, "reader-pages").listFiles()!!.single()
            val metadata = File(scratch, "capture.txt")
            if (phase == "capture") {
                metadata.writeText("${android.os.Process.myPid()}\n${cache.lastModified()}\n${counts.index!!.totalPages}")
            } else {
                val saved = metadata.readLines()
                assertNotEquals("must use a new process", saved[0].toInt(), android.os.Process.myPid())
                assertEquals("cache hit must not rewrite cache", saved[1].toLong(), cache.lastModified())
                assertEquals(saved[2].toInt(), counts.index!!.totalPages)
                compose.runOnIdle { assertNull("cache hit must not start measurement", measuringView(root)) }
            }
            Log.i("HoshiPageCounts", "cacheRestart=$phase pid=${android.os.Process.myPid()} pages=${counts.index!!.totalPages}")
            passed = true
        } finally {
            compose.runOnIdle { open = false }
            compose.waitForIdle()
            if (phase == "verify" || !passed) scratch.deleteRecursively()
        }
    }

    @Test fun leadingImageTextLabelsAndChapterNavigationUsePhysicalPages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = ReaderWebAssets.load(context)
        val html = "<html><head></head><body><div style='break-after:column'><img src='cover.svg'/></div><p>日本語</p></body></html>"
        val image = "<svg xmlns='http://www.w3.org/2000/svg' width='300' height='600'><rect width='300' height='600' fill='red'/></svg>"
        val book = EpubBook("Leading image", chapters = (0..1).map { EpubChapter("$it", "$it.html", "text/html", html) },
            resources = (0..1).associate { "$it.html" to EpubResource("text/html", html.toByteArray()) } +
                ("cover.svg" to EpubResource("image/svg+xml", image.toByteArray())))
        lateinit var host: FrameLayout
        compose.setContent { AndroidView(modifier = Modifier.fillMaxSize(), factory = { FrameLayout(it).also { host = it; it.keepScreenOn = true } }) }
        compose.waitForIdle()
        for (vertical in listOf(true, false)) for (paragraph in listOf(false, true)) {
            val settings = ReaderSettings(verticalWriting = vertical, paragraphMode = paragraph, progressCount = ReaderProgressCount.Pages)
            withContext(Dispatchers.Main) {
                val density = host.resources.displayMetrics.density
                val size = IntSize((host.width / density).toInt(), (host.height / density).toInt())
                val measurer = ReaderPageMeasurer(host, book, assets, { null }) { token, _ ->
                    readerSetupScript(0.0, null, settings, null, null, false, false,
                        ContentLanguageProfile.Japanese, size, 0L, 0L, null, null, token, assets, measurementOnly = true)
                }
                try {
                    val starts = listOf(measurer.measure(0), measurer.measure(1))
                    val index = requireNotNull(ReaderPageIndex.create(book, starts))
                    assertTrue("leading image vertical=$vertical paragraph=$paragraph starts=${starts[0]}",
                        starts[0].takeWhile { it == 0 }.size > 1)
                    assertEquals(1, index.pageAt(0, 0, preferSpineStart = true))
                    assertTrue("first text must be after the image", index.pageAt(0, 0) > 1)
                    compareVisible(host, book, assets, settings, size, starts[0])
                    val holder = ReaderWebViewStateHolder(settings, ReaderChapterPosition(0, 1.0))
                    holder.markWebViewRestored()
                    holder.goToNextChapter(1)
                    assertTrue(holder.isWebViewRestoring)
                    assertEquals(starts[0].size + 1, holder.pageProgress(index, null)?.current)
                    assertEquals(index.totalPages, holder.pageProgress(index, null)?.total)
                    holder.markWebViewRestored()
                    holder.goToPreviousChapter()
                    assertEquals(starts[0].size, holder.pageProgress(index, null)?.current)
                } finally { measurer.close() }
            }
        }
    }

    @Test fun measurementReusesLoadedFontsButANewSnapshotReadsReplacedFont() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scratch = File(context.cacheDir, "page-font-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val file = File(scratch, "fixture.ttf")
        val html = "<html><head></head><body><p>" + "MMMM wide words in a paragraph. ".repeat(150) + "</p></body></html>"
        val book = EpubBook("Fonts", chapters = (0..1).map { EpubChapter("$it", "$it.html", "text/html", html) },
            resources = (0..1).associate { "$it.html" to EpubResource("text/html", html.toByteArray()) })
        val font = ReaderFontRenderSpec("fixture", "regular", "Fixture", "Fixture", 400, false,
            faces = listOf(ReaderFontFace("https://appassets.androidplatform.net/fonts/fixture.ttf", 400, false)))
        val requests = java.util.concurrent.atomic.AtomicInteger()
        lateinit var host: FrameLayout
        compose.setContent { AndroidView(modifier = Modifier.fillMaxSize(), factory = { FrameLayout(it).also { host = it; it.keepScreenOn = true } }) }
        compose.waitForIdle()
        try {
            withContext(Dispatchers.Main) {
                val assets = ReaderWebAssets.load(context)
                val density = host.resources.displayMetrics.density
                val size = IntSize((host.width / density).toInt(), (host.height / density).toInt())
                suspend fun measureWidth(): Double {
                    val before = requests.get()
                    val measurer = ReaderPageMeasurer(host, book, assets, { name ->
                        if (name == "fixture.ttf") { requests.incrementAndGet(); file } else null
                    }) { token, resources ->
                        readerSetupScript(0.0, null, ReaderSettings(selectedFont = "Fixture", verticalWriting = false),
                            null, resources.font(font), false, false, ContentLanguageProfile.English, size,
                            0L, 0L, null, null, token, assets, measurementOnly = true)
                    }
                    try {
                        assertEquals(measurer.measure(0), measurer.measure(1))
                        assertEquals("font must be reused by the second chapter", before + 1, requests.get())
                        val view = host.getChildAt(0) as WebView
                        assertEquals("1", evaluate(view, "Array.from(document.fonts).filter(f=>f.status==='loaded').length"))
                        return evaluate(view, "(()=>{const c=document.createElement('canvas').getContext('2d');c.font='22px Fixture';return c.measureText('MMMMMMMM').width;})()").toDouble()
                    } finally { measurer.close() }
                }
                withContext(Dispatchers.IO) { File("/system/fonts/Roboto-Regular.ttf").copyTo(file, overwrite = true) }
                val originalWidth = measureWidth()
                withContext(Dispatchers.IO) { File("/system/fonts/DroidSansMono.ttf").copyTo(file, overwrite = true) }
                assertNotEquals("replacement must not use the previous decoded font", originalWidth, measureWidth(), 0.01)
            }
        } finally { withContext(Dispatchers.IO) { scratch.deleteRecursively() } }
    }

    @Test fun hiddenMeasurementMatchesVisiblePagesAcrossLayoutsAndImages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = ReaderWebAssets.load(context)
        val html = "<!doctype html><html><head></head><body>" +
            (1..45).joinToString("") { "<p>「<ruby>読書<rt>どくしょ</rt></ruby>𠮟る。日本語の文章です。今日も本を読みます。」</p>" } + "</body></html>"
        val imageHtml = "<!doctype html><html><head></head><body><svg xmlns='http://www.w3.org/2000/svg' width='300' height='600'><rect width='300' height='600' fill='red'/></svg></body></html>"
        val book = EpubBook("Fixture", chapters = listOf(
            EpubChapter("a", "Text/第一 章.html", "text/html", html),
            EpubChapter("b", "b.html", "text/html", imageHtml),
        ), resources = mapOf("Text/第一 章.html" to EpubResource("text/html", html.toByteArray()), "b.html" to EpubResource("text/html", imageHtml.toByteArray())))
        lateinit var host: FrameLayout
        compose.setContent { AndroidView(modifier = Modifier.fillMaxSize(), factory = { FrameLayout(it).also { host = it; it.keepScreenOn = true } }) }
        compose.waitForIdle()
        for (vertical in listOf(true, false)) for (paragraph in listOf(false, true)) {
            val settings = ReaderSettings(verticalWriting = vertical, paragraphMode = paragraph, fontSize = if (paragraph) 28 else 22,
                horizontalPadding = if (vertical) 12 else 5, progressCount = ReaderProgressCount.Pages)
            withContext(Dispatchers.Main) {
                val density = host.resources.displayMetrics.density
                val size = IntSize((host.width / density).toInt(), (host.height / density).toInt())
                val measurer = ReaderPageMeasurer(host, book, assets, { null }) { token, _ ->
                    readerSetupScript(0.0, null, settings, null, null, false, false,
                        ContentLanguageProfile.Japanese, size, 0L, 0L, null, null, token, assets, measurementOnly = true)
                }
                try {
                    val started = SystemClock.elapsedRealtimeNanos()
                    val starts = measurer.measure(0)
                    val measurementMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                    assertTrue("text spans pages", starts.size > 1)
                    assertTrue(starts.zipWithNext().all { (a, b) -> a <= b })
                    assertEquals(0, starts.first())
                    val pageTurnMs = compareVisible(host, book, assets, settings, size, starts)
                    Log.i("HoshiPageCounts", "vertical=$vertical paragraph=$paragraph pages=${starts.size} measureMs=$measurementMs pageTurnMs=$pageTurnMs")
                    val imageStarts = measurer.measure(1)
                    assertTrue(imageStarts.isNotEmpty())
                    assertTrue(imageStarts.all { it == 0 })
                } finally { measurer.close() }
                assertEquals(0, host.childCount)
            }
        }
    }

    @Test fun counterReusesDiskCacheAndInvalidatesOnLayoutAndContentChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scratch = File(context.cacheDir, "page-counter-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val fonts = ReaderFontManager(scratch)
        val repository = ReaderPageCountRepository(scratch, Dispatchers.IO)
        val books = BookRepository(scratch)
        fun fixture(repeats: Int): EpubBook {
            val html = "<html><head></head><body>" + "<p>日本語の本を読む時間です。明日もたくさん読みます。</p>".repeat(repeats) + "</body></html>"
            return EpubBook("Fixture", chapters = listOf(EpubChapter("a", "a.html", "text/html", html)),
                resources = mapOf("a.html" to EpubResource("text/html", html.toByteArray())))
        }
        var book by mutableStateOf(fixture(50))
        var settings by mutableStateOf(ReaderSettings(selectedFont = ReaderFontManager.publisherFont))
        var open by mutableStateOf(true)
        var readerReady by mutableStateOf(true)
        val scheduler = ReaderPageMeasurementScheduler()
        lateinit var rootView: android.view.View
        var viewport by mutableStateOf(IntSize.Zero)
        var counts by mutableStateOf(ReaderPageCounts())
        var heightFraction by mutableFloatStateOf(1f)
        try {
            compose.setContent {
                rootView = androidx.compose.ui.platform.LocalView.current
                Box(Modifier.fillMaxWidth().fillMaxHeight(heightFraction).onSizeChanged { viewport = it }) {
                    if (open) ReaderPageCounter(book, null, "fixture", settings, viewport, readerReady,
                        ContentLanguageProfile.Japanese, fonts, repository, books, { counts = it }, Modifier.fillMaxSize(), scheduler)
                }
            }
            compose.waitForIdle()
            assertFalse(File(scratch, "reader-pages").exists())
            compose.runOnIdle { settings = settings.copy(progressCount = ReaderProgressCount.Pages) }
            compose.waitUntil(10_000) {
                var attached = false
                compose.runOnIdle { attached = measuringView(rootView) != null }
                attached
            }
            compose.runOnIdle {
                val measuring = requireNotNull(measuringView(rootView))
                scheduler.setReaderReady(false)
                assertNull("restore must synchronously detach the hidden renderer", measuring.parent)
                scheduler.setReaderReady(true)
                // readerReady stays true: only the generation can restart this pass.
            }
            compose.waitUntil(20_000) { counts.index != null || counts.failed }
            assertFalse("initial measurement failed", counts.failed)
            val original = requireNotNull(counts.index)
            compose.runOnIdle { readerReady = false }
            compose.waitForIdle()
            assertSame("known counts survive foreground restore", original, counts.index)
            compose.runOnIdle { readerReady = true }
            compose.waitForIdle()
            val cache = File(scratch, "reader-pages").listFiles()!!.single()
            val savedAt = cache.lastModified()
            compose.runOnIdle { open = false; readerReady = false; counts = ReaderPageCounts() }
            compose.waitForIdle()
            compose.runOnIdle { open = true }
            compose.waitUntil(20_000) { counts.index != null || counts.failed }
            assertEquals(original.totalPages, counts.index?.totalPages)
            assertEquals("cache hit must not rewrite cache", savedAt, cache.lastModified())
            compose.runOnIdle {
                assertFalse("cached counts must be available before visible restore", readerReady)
                assertNull("reading cache must not attach a measuring WebView", measuringView(rootView))
                readerReady = true
            }
            val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
            val oldViewport = viewport
            val sameCssWidth = listOf(oldViewport.width - 1, oldViewport.width + 1).firstOrNull {
                (it / density).roundToInt() == (oldViewport.width / density).roundToInt()
            }
            if (sameCssWidth != null) {
                compose.runOnIdle {
                    // Reader resets its counts for every physical viewport change.
                    readerReady = false
                    counts = ReaderPageCounts()
                    viewport = IntSize(sameCssWidth, oldViewport.height)
                }
                compose.waitUntil(5_000) { counts.index != null || counts.failed }
                assertFalse(counts.failed)
                assertEquals(original.totalPages, counts.index?.totalPages)
                assertEquals("same CSS layout must reuse the cache", savedAt, cache.lastModified())
                compose.runOnIdle {
                    assertNull(measuringView(rootView))
                    readerReady = true
                }
            }
            compose.runOnIdle { settings = settings.copy(fontSize = 42) }
            compose.waitUntil(20_000) { counts.index != null && counts.index !== original && counts.index!!.totalPages > original.totalPages }
            val enlarged = requireNotNull(counts.index)
            compose.runOnIdle { heightFraction = 0.6f }
            compose.waitUntil(20_000) { counts.index != null && counts.index!!.totalPages > enlarged.totalPages }
            val resized = requireNotNull(counts.index)
            compose.runOnIdle { book = fixture(100) }
            compose.waitUntil(20_000) { counts.index != null && counts.index!!.totalPages > resized.totalPages }
            compose.runOnIdle { settings = settings.copy(viewMode = ReaderViewMode.Continuous) }
            compose.waitForIdle()
            assertNull(counts.index)
            assertFalse(counts.failed)
        } finally {
            compose.runOnIdle { open = false }
            compose.waitForIdle()
            scratch.deleteRecursively()
        }
    }

    @Test fun cancellingAnInFlightMeasurementDetachesItsWebView() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val html = "<html><head></head><body>読書</body></html>"
        val book = EpubBook("Cancel fixture", chapters = listOf(EpubChapter("a", "a.html", "text/html", html)),
            resources = mapOf("a.html" to EpubResource("text/html", html.toByteArray())))
        lateinit var host: FrameLayout
        compose.setContent { AndroidView(modifier = Modifier.fillMaxSize(), factory = { FrameLayout(it).also { host = it } }) }
        compose.waitForIdle()
        withContext(Dispatchers.Main) {
            // Hold setup indefinitely so destruction is tested with a pending native result.
            val measurer = ReaderPageMeasurer(host, book, ReaderWebAssets.load(context), { null }) { _, _ -> "void 0" }
            val pending = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { measurer.measure(0) }
            assertEquals(1, host.childCount)
            measurer.close()
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals(0, host.childCount)
            measurer.close()
        }
    }

    @Test fun foregroundActivityPausesAnAttachedMeasurementAndResumesIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val html = "<html><head></head><body>" + "<p>日本語の本を読む時間です。</p>".repeat(100) + "</body></html>"
        val book = EpubBook("Pause fixture", chapters = listOf(EpubChapter("a", "a.html", "text/html", html)),
            resources = mapOf("a.html" to EpubResource("text/html", html.toByteArray())))
        lateinit var host: FrameLayout
        compose.setContent { AndroidView(modifier = Modifier.fillMaxSize(), factory = { FrameLayout(it).also { host = it; it.keepScreenOn = true } }) }
        compose.waitForIdle()
        withContext(Dispatchers.Main) {
            val scheduler = ReaderPageMeasurementScheduler(50)
            scheduler.setReaderReady(true)
            val setup = CompletableDeferred<Unit>()
            val assets = ReaderWebAssets.load(context)
            val density = host.resources.displayMetrics.density
            val size = IntSize((host.width / density).toInt(), (host.height / density).toInt())
            val measurer = ReaderPageMeasurer(host, book, assets, { null }, scheduler) { token, _ ->
                scheduler.setReaderReady(false)
                setup.complete(Unit)
                readerSetupScript(0.0, null, ReaderSettings(progressCount = ReaderProgressCount.Pages), null, null, false, false,
                    ContentLanguageProfile.Japanese, size, 0L, 0L, null, null, token, assets, measurementOnly = true)
            }
            try {
                val pending = async { measurer.measure(0) }
                withTimeout(10_000) { setup.await() }
                val attached = host.getChildAt(0)
                kotlinx.coroutines.delay(300)
                assertFalse("foreground load must pause hidden work", pending.isCompleted)
                scheduler.setReaderReady(true)
                val pages = withTimeout(20_000) { pending.await() }
                assertTrue(pages.size > 1)
                assertSame("resume must retain the current document", attached, host.getChildAt(0))
            } finally { measurer.close() }
            assertEquals(0, host.childCount)
        }
    }

    private fun measuringView(root: android.view.View): moe.antimony.hoshi.ui.theme.HoshiWebView? {
        if (root is moe.antimony.hoshi.ui.theme.HoshiWebView) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            measuringView(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private suspend fun compareVisible(host: FrameLayout, book: EpubBook, assets: ReaderWebAssets,
        settings: ReaderSettings, size: IntSize, starts: List<Int>): Double {
        val restored = CompletableDeferred<Unit>()
        val bridge = ReaderWebResourceBridge(book) { null }
        val visible = WebView(host.context).apply {
            applyHoshiWebViewSecurityDefaults()
            addJavascriptInterface(object {
                @JavascriptInterface fun postMessage(token: String) { post { restored.complete(Unit) } }
            }, "HoshiReaderRestore")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    bridge.resourceForUrl(request.url.toString())?.toWebResourceResponse()
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("window.HoshiReaderPage={postMessage:(token,page)=>window.fixturePage=page};" +
                        readerSetupScript(0.0, null, settings, null, null, false, false,
                            ContentLanguageProfile.Japanese, size, 0L, 0L, null, null, "visible", assets), null)
                }
            }
        }
        host.addView(visible, ViewGroup.LayoutParams(-1, -1))
        visible.measure(android.view.View.MeasureSpec.makeMeasureSpec(host.width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(host.height, android.view.View.MeasureSpec.EXACTLY))
        visible.layout(0, 0, host.width, host.height)
        try {
            visible.loadUrl(java.net.URI("https", "appassets.androidplatform.net", "/epub/${book.chapters.first().href}", null, null).toASCIIString())
            withTimeout(15_000) { restored.await() }
            // Use the real layout engine as an independent geometry oracle for
            // the adjacent-text batching optimization, before any page turns.
            assertEquals("batched text geometry must equal individual DOM ranges", "true", evaluate(visible, """
                (() => {
                  const reader = hoshiReader;
                  const snapshot = () => {
                    reader.paginationMetrics = null;
                    const metrics = reader.buildPaginationMetrics();
                    return JSON.stringify({ranges: metrics.textRanges.map(({count,first,start,end}) =>
                        [count,first,start,end]), starts: reader.calculatePageStarts()});
                  };
                  const batched = snapshot();
                  const original = reader.adjacentCharacterRects;
                  try {
                    reader.adjacentCharacterRects = () => null;
                    return snapshot() === batched;
                  } finally { reader.adjacentCharacterRects = original; }
                })()
            """.trimIndent()))
            val startTime = SystemClock.elapsedRealtimeNanos()
            for (page in starts.indices) {
                assertEquals("visible page event", page, evaluate(visible, "window.fixturePage").toInt())
                assertEquals("character position at page $page", starts[page], evaluate(visible,
                    "Math.round(window.hoshiReader.calculateProgress()*window.hoshiReader.paginationMetrics.totalChars)").toInt())
                val result = evaluate(visible, "window.hoshiReader.paginate('forward')")
                assertEquals(if (page == starts.lastIndex) "\"limit\"" else "\"scrolled\"", result)
            }
            return (SystemClock.elapsedRealtimeNanos() - startTime) / 1_000_000.0 / starts.size
        } finally { host.removeView(visible); visible.destroy() }
    }

    private suspend fun evaluate(view: WebView, script: String): String = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result) }
    }

}
