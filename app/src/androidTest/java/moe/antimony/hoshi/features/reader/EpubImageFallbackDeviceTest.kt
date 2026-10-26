package moe.antimony.hoshi.features.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.features.bookshelf.AndroidBookCoverThumbnailEncoder
import moe.antimony.hoshi.features.bookshelf.BookCoverSource
import moe.antimony.hoshi.features.bookshelf.BookCoverThumbnailStore
import moe.antimony.hoshi.features.sync.SyncFileType
import moe.antimony.hoshi.navigation.DefaultReaderRouteEpubParser
import moe.antimony.hoshi.navigation.ReaderRouteLoadState
import moe.antimony.hoshi.navigation.ReaderRouteStateHolder
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Generated pages and opt-in EPUB copies only. Does not access library stores or preferences. */
@RunWith(AndroidJUnit4::class)
class EpubImageFallbackDeviceTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun openingBooksRestoresLegacyCoversAndLeavesCoverlessBooksAlone() = runBlocking {
        val scratch = File(instrumentation.targetContext.cacheDir, "epub-cover-open-${System.nanoTime()}").apply { mkdirs() }
        try {
            val repository = BookRepository(scratch)
            val parser = EpubBookParser(scratch.resolve("parser"))
            val holder = ReaderRouteStateHolder(repository, DefaultReaderRouteEpubParser(parser))
            val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            for (hasCover in listOf(true, false)) {
                val root = repository.createBookDirectory(if (hasCover) "covered" else "coverless")
                val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 30"><image href="image.png" width="20" height="30"/></svg>"""
                val manifestCover = if (hasCover) """<item id="cover" href="cover.svg" media-type="image/svg+xml" properties="cover-image"/>""" else ""
                val entries = mapOf(
                    "mimetype" to "application/epub+zip",
                    "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                    "package.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">fixture</dc:identifier><dc:title>Fixture</dc:title><dc:language>en</dc:language></metadata><manifest><item id="text" href="text.xhtml" media-type="application/xhtml+xml"/><item id="illustration" href="image.png" media-type="image/png"/>$manifestCover</manifest><spine><itemref idref="text"/></spine></package>""",
                    "text.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Text</title></head><body><p>abcdefghij</p><img src="image.png"/></body></html>""",
                    "cover.svg" to svg,
                )
                ZipOutputStream(root.resolve("book.epub").outputStream()).use { zip ->
                    (entries.mapValues { it.value.toByteArray() } + ("image.png" to png)).forEach { (name, bytes) ->
                        zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                    }
                }
                val metadata = BookMetadata(UUID.randomUUID().toString(), "Fixture", folder = root.name, lastAccess = 0.0,
                    cover = if (hasCover) "Books/${root.name}/cover.svg" else null, epub = "book.epub")
                if (hasCover) root.resolve("cover.svg").writeText(svg)
                repository.saveMetadata(root, metadata)
                val parsed = parser.parse(root)
                repository.saveBookInfo(root, parsed.bookInfo)
                val bookmark = Bookmark(0, 0.4, 4, 123.0)
                repository.saveBookmark(root, bookmark)
                val changes = mutableListOf<SyncFileType?>()
                repository.onBookChange = { _, type -> changes += type }
                val ready = holder.load(metadata.id) as ReaderRouteLoadState.Ready
                assertEquals(bookmark, ready.bookmark)
                if (hasCover) {
                    val cover = requireNotNull(ready.bookCoverFile)
                    assertArrayEquals(png, cover.readBytes())
                    val thumbnails = BookCoverThumbnailStore(scratch, AndroidBookCoverThumbnailEncoder(), Dispatchers.IO)
                    val thumbnail = requireNotNull(thumbnails.thumbnail(BookCoverSource(cover.path, "restored"), 320))
                    val decoded = BitmapFactory.decodeFile(thumbnail.path)
                    assertNotNull(decoded)
                    decoded?.recycle()
                    assertEquals(listOf(SyncFileType.cover), changes)
                } else {
                    assertNull(parsed.coverHref)
                    assertNull(ready.bookCoverFile)
                    assertNull(ready.entry.metadata.cover)
                    assertTrue(changes.isEmpty())
                }
                changes.clear()
                holder.load(metadata.id)
                assertTrue(changes.isEmpty())
            }
        } finally { scratch.deleteRecursively() }
    }

    @Test fun imageParagraphsAreVisibleAndClickableAcrossReadingModes() {
        val bitmap = Bitmap.createBitmap(2048, 1028, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val image = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        withWebView { webView ->
            for (mode in ReaderViewMode.entries) for (vertical in listOf(true, false)) {
                renderAndCheck(webView, "<html><head></head><body><div><p><img src='$image'/></p></div></body></html>",
                    mode, vertical, "generated-${mode.name}-$vertical")
            }
        }
    }

    @Test fun sampleCopiesRestoreChaptersGalleryAndImportableCovers() = runBlocking {
        val directory = InstrumentationRegistry.getArguments().getString("epubSamplesDir")
        assumeTrue("Requires task-owned copies supplied through epubSamplesDir", directory != null)
        val scratch = File(instrumentation.targetContext.cacheDir, "epub-fallback-${System.nanoTime()}").apply { mkdirs() }
        try {
            val parser = EpubBookParser(scratch)
            val repository = BookRepository(scratch.resolve("library"))
            val thumbnails = BookCoverThumbnailStore(scratch, AndroidBookCoverThumbnailEncoder(), Dispatchers.IO)
            val samples = listOf(Triple("saekano", 37, 15), Triple("sakurasou", 24, 21), Triple("collab", 9, 3))
            val books = samples.map { (name, chapters, images) ->
                val book = parser.parsePacked(File(directory, "$name.epub"))
                assertEquals(name, chapters, book.chapters.size)
                assertEquals(name, images, book.bookInfo.images!!.size)
                book.bookInfo.images.forEach { path ->
                    val bytes = requireNotNull(book.readResource(path))
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    assertNotNull("$name $path", bitmap)
                    bitmap?.recycle()
                }
                val root = repository.createBookDirectory(name)
                val cover = requireNotNull(repository.metadataCoverPath(root, book))
                val bitmap = BitmapFactory.decodeFile(root.resolve(File(cover).name).path)
                assertNotNull("$name cover", bitmap)
                bitmap?.recycle()
                val thumbnail = requireNotNull(thumbnails.thumbnail(BookCoverSource(root.resolve(File(cover).name).path, name), 320))
                val thumbnailBitmap = BitmapFactory.decodeFile(thumbnail.path)
                assertNotNull("$name thumbnail", thumbnailBitmap)
                thumbnailBitmap?.recycle()
                val warm = parser.parsePacked(File(directory, "$name.epub"), cachedBookInfo = book.bookInfo)
                assertEquals(book.bookInfo, warm.bookInfo)
                assertTrue(warm.chapters.all { it.html.isEmpty() })
                Log.i("HoshiEpubFallback", "$name chapters=$chapters images=$images cover=$cover")
                name to warm
            }
            withWebView { webView ->
                books.forEach { (name, book) ->
                    for (mode in ReaderViewMode.entries) for (vertical in listOf(true, false)) {
                        val chapters = listOfNotNull(book.chapters.first(), book.chapters.find { it.href.endsWith("p-003002.xhtml") })
                        chapters.forEachIndexed { index, chapter ->
                            renderAndCheck(webView, requireNotNull(book.readResource(chapter.href)).decodeToString(),
                                mode, vertical, "$name-$index-${mode.name}-$vertical", book, chapter.href)
                        }
                    }
                }
            }
        } finally { scratch.deleteRecursively() }
    }

    @Test fun sampleGalleryShowsAndSelectsFirstAndLastImages() {
        val directory = InstrumentationRegistry.getArguments().getString("epubSamplesDir")
        assumeTrue("Requires task-owned copies supplied through epubSamplesDir", directory != null)
        val scratch = File(instrumentation.targetContext.cacheDir, "epub-gallery-${System.nanoTime()}").apply { mkdirs() }
        val currentBook = mutableStateOf<EpubBook?>(null)
        var selected: String? = null
        composeRule.setContent {
            MaterialTheme {
                currentBook.value?.let { book ->
                    ReaderGalleryTab(book, onImageSelected = { selected = it }, modifier = Modifier.fillMaxSize().testTag("gallery"))
                }
            }
        }
        try {
            val parser = EpubBookParser(scratch)
            val description = instrumentation.targetContext.getString(R.string.reader_gallery_image)
            for (name in listOf("saekano", "sakurasou", "collab")) {
                val book = parser.parsePacked(File(directory, "$name.epub"))
                composeRule.runOnIdle { currentBook.value = book }
                val images = book.bookInfo.images!!
                for (index in listOf(0, images.lastIndex)) {
                    composeRule.onNodeWithTag("gallery").performScrollToIndex(index)
                    composeRule.waitUntil(10_000) {
                        val nodes = composeRule.onAllNodesWithContentDescription(description)
                        if (nodes.fetchSemanticsNodes().isEmpty()) false else {
                            (if (index == 0) nodes.onFirst() else nodes.onLast()).performClick()
                            selected == images[index]
                        }
                    }
                    composeRule.runOnIdle { assertEquals("$name gallery $index", images[index], selected) }
                    val screenshot = composeRule.onRoot().captureToImage().asAndroidBitmap()
                    val evidence = File(instrumentation.targetContext.cacheDir, "epub-fallback-evidence").apply { mkdirs() }
                    evidence.resolve("$name-gallery-$index.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        } finally { scratch.deleteRecursively() }
    }

    private fun withWebView(test: (WebView) -> Unit) {
        lateinit var webView: WebView
        composeRule.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).also { webView = it; it.settings.javaScriptEnabled = true }
            })
        }
        composeRule.waitForIdle()
        try { test(webView) } finally {
            instrumentation.runOnMainSync {
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                webView.destroy()
            }
        }
    }

    private fun renderAndCheck(webView: WebView, html: String, mode: ReaderViewMode, vertical: Boolean,
        label: String, book: EpubBook? = null, href: String = "fixture.xhtml") {
        val assets = ReaderWebAssets.load(instrumentation.targetContext)
        val settings = ReaderSettings(viewMode = mode, verticalWriting = vertical, horizontalPadding = 3, verticalPadding = 0, fontSize = 27)
        val loaded = CountDownLatch(1)
        val bridge = book?.let { ReaderWebResourceBridge(it) { null } }
        instrumentation.runOnMainSync {
            val density = webView.resources.displayMetrics.density
            val viewport = readerViewportCssLayout(settings, (webView.width / density).toInt(), (webView.height / density).toInt()).cssVariables()
            val styles = ReaderContentStyles.styleTag(settings = settings, readerCssTemplate = assets.readerCss)
            val script = ReaderPaginationScripts.shellScript(settings = settings, assets = assets)
            val setup = "<script>window.HoshiReaderRestore={postMessage:()=>window.testRestored=true};window.HoshiReaderImage={postMessage:src=>window.testImageTapped=src};</script>$styles<style>$viewport</style>$script"
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    bridge?.resourceForUrl(request.url.toString())?.toWebResourceResponse()
            }
            webView.loadDataWithBaseURL("https://appassets.androidplatform.net/epub/$href", html.replace("</head>", "$setup</head>"), "text/html", "UTF-8", null)
        }
        assertTrue("$label load", loaded.await(15, TimeUnit.SECONDS))
        composeRule.waitUntil(15_000) { evaluate(webView, "Boolean(window.testRestored)") == "true" }
        val state = JSONObject(evaluate(webView, """
            (() => { const image = [...document.images].find(img => img.getBoundingClientRect().width > 0);
                if (!image) return {visible:false}; const r = image.getBoundingClientRect();
                image.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true}));
                return {visible: image.naturalWidth > 0 && r.width > 0 && r.height > 0 &&
                    r.left >= -1 && r.top >= -1 && r.right <= innerWidth + 1 && r.bottom <= innerHeight + 1,
                    tapped: !!window.testImageTapped, x:r.x, y:r.y, width:r.width, height:r.height, viewportHeight:innerHeight}; })()
        """.trimIndent()))
        assertTrue("$label $state", state.getBoolean("visible"))
        assertTrue("$label tap $state", state.getBoolean("tapped"))
        if (label.startsWith("saekano-0-") && vertical) {
            assertEquals("$label centered", state.getDouble("viewportHeight") / 2,
                state.getDouble("y") + state.getDouble("height") / 2, 2.0)
        }
        val painted = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { webView.postOnAnimation { webView.postOnAnimation { painted.countDown() } } }
            })
        }
        assertTrue("$label painted", painted.await(10, TimeUnit.SECONDS))
        val reference = JSONArray(evaluate(webView, """
            (() => { const image = [...document.images].find(img => img.getBoundingClientRect().width > 0);
                const r = image.getBoundingClientRect(), canvas = document.createElement('canvas');
                canvas.width = image.naturalWidth; canvas.height = image.naturalHeight;
                const context = canvas.getContext('2d'); context.drawImage(image, 0, 0);
                const samples = [];
                for (let x = 0; x < 7; x++) for (let y = 0; y < 7; y++) {
                    const fx = (x + .5) / 7, fy = (y + .5) / 7;
                    const pixel = context.getImageData(Math.floor(fx * canvas.width), Math.floor(fy * canvas.height), 1, 1).data;
                    samples.push([r.left + fx * r.width, r.top + fy * r.height, pixel[0], pixel[1], pixel[2]]);
                } return samples; })()
        """.trimIndent()))
        val pixelScale = webView.width / evaluate(webView, "innerWidth").toDouble()
        val location = IntArray(2)
        instrumentation.runOnMainSync { webView.getLocationOnScreen(location) }
        composeRule.waitUntil(10_000) {
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            val viewport = Bitmap.createBitmap(screenshot, location[0], location[1], webView.width, webView.height)
            var difference = 0
            for (index in 0 until reference.length()) {
                val sample = reference.getJSONArray(index)
                val pixel = viewport.getPixel((sample.getDouble(0) * pixelScale).toInt(), (sample.getDouble(1) * pixelScale).toInt())
                difference += abs(Color.red(pixel) - sample.getInt(2)) + abs(Color.green(pixel) - sample.getInt(3)) + abs(Color.blue(pixel) - sample.getInt(4))
            }
            // Reject both blank frames and a still-painted image from the previous chapter.
            val hasContent = difference.toDouble() / (reference.length() * 3) < 20
            if (hasContent) {
                val dir = File(instrumentation.targetContext.cacheDir, "epub-fallback-evidence").apply { mkdirs() }
                dir.resolve("$label.png").outputStream().use { viewport.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            viewport.recycle()
            screenshot.recycle()
            hasContent
        }
        Log.i("HoshiEpubFallback", "$label $state")
    }

    private fun evaluate(webView: WebView, script: String): String {
        val complete = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { webView.evaluateJavascript(script) { result = it; complete.countDown() } }
        assertTrue("JavaScript callback", complete.await(10, TimeUnit.SECONDS))
        return result
    }
}
