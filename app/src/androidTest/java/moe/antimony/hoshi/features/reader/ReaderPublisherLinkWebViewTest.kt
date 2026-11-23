package moe.antimony.hoshi.features.reader

import android.content.ContextWrapper
import android.content.Intent
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReaderPublisherLinkWebViewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tappingPublisherLinkOpensBrowserAndKeepsTheBookOpen() {
        val url = "https://publisher.example/series"
        val book = EpubBook("Book", chapters = listOf(EpubChapter("end", "end.html", "text/html",
            """<a href="$url" style="position:fixed;inset:0">Publisher</a>""")))
        val browser = LinkedBlockingQueue<Intent>()
        val loaded = LinkedBlockingQueue<String>()
        lateinit var web: WebView
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize().testTag("book"), factory = { context ->
                WebView(object : ContextWrapper(context) {
                    override fun startActivity(intent: Intent) { browser.add(intent) }
                }).apply {
                    settings.javaScriptEnabled = true
                    web = this
                    webViewClient = EpubWebViewClient(book, ReaderWebResourceBridge(book) { null },
                        onInternalLink = { error("Unexpected internal navigation") }, popupResourceHandler = { null },
                        onRendererTerminated = { error("Renderer terminated") }, onReaderPageFinished = { loaded.add(it.url.orEmpty()) })
                    loadUrl(READER_BOOK_URL)
                }
            }, onRelease = { it.destroy() })
        }
        assertEquals(READER_BOOK_URL, loaded.poll(10, TimeUnit.SECONDS))
        val painted = CountDownLatch(1)
        val hit = LinkedBlockingQueue<String>()
        compose.runOnIdle {
            web.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { painted.countDown() }
            })
            web.evaluateJavascript("document.elementFromPoint(innerWidth/2, innerHeight/2)?.closest('a')?.href") { hit.add(it) }
        }
        assertTrue("Publisher page is ready to draw", painted.await(5, TimeUnit.SECONDS))
        assertEquals("\"$url\"", hit.poll(5, TimeUnit.SECONDS))
        compose.onNodeWithTag("book").performTouchInput { click() }
        val intent = browser.poll(5, TimeUnit.SECONDS)
        assertNotNull("External navigation intent", intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals(url, intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        compose.runOnIdle { assertEquals(READER_BOOK_URL, web.url) }
    }
}
