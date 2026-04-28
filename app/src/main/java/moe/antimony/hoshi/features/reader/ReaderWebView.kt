package moe.antimony.hoshi.features.reader

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.EpubBook

data class ReaderSelectionData(
    val text: String,
    val sentence: String,
    val rect: ReaderSelectionRect,
    val normalizedOffset: Int?,
)

data class ReaderSelectionRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderWebView(
    book: EpubBook,
    initialChapterIndex: Int = 0,
    initialProgress: Double = 0.0,
    onSaveBookmark: (chapterIndex: Int, progress: Double) -> Unit = { _, _ -> },
    onTextSelected: (ReaderSelectionData) -> Int? = { null },
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clampedInitialIndex = initialChapterIndex.coerceIn(0, book.chapters.lastIndex)
    var chapterPosition by remember(book) {
        mutableStateOf(
            ReaderChapterPosition(
                index = clampedInitialIndex,
                progress = initialProgress.coerceIn(0.0, 1.0),
            ),
        )
    }
    var webView by remember { mutableStateOf<WebView?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = book.title,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Text("‹")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFFF7F3EA)),
        ) {
            ChapterWebView(
                book = book,
                chapterPosition = chapterPosition,
                onWebViewReady = { webView = it },
                onNextChapter = {
                    val next = chapterPosition.nextOrNull(book.chapters.lastIndex)
                    if (next != null) {
                        chapterPosition = next
                        onSaveBookmark(next.index, next.progress)
                        true
                    } else {
                        false
                    }
                },
                onPreviousChapter = {
                    val previous = chapterPosition.previousOrNull()
                    if (previous != null) {
                        chapterPosition = previous
                        onSaveBookmark(previous.index, previous.progress)
                        true
                    } else {
                        false
                    }
                },
                onSaveBookmark = { progress ->
                    onSaveBookmark(chapterPosition.index, progress)
                },
                onTextSelected = onTextSelected,
                modifier = Modifier.fillMaxSize(),
            )
            webView?.let { _ -> Unit }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun ChapterWebView(
    book: EpubBook,
    chapterPosition: ReaderChapterPosition,
    onWebViewReady: (WebView) -> Unit,
    onNextChapter: () -> Boolean,
    onPreviousChapter: () -> Boolean,
    onSaveBookmark: (progress: Double) -> Unit,
    onTextSelected: (ReaderSelectionData) -> Int?,
    modifier: Modifier = Modifier,
) {
    val chapter = book.chapters[chapterPosition.index]
    val html = remember(chapter, chapterPosition.progress) {
        chapter.html.injectReaderShell(initialProgress = chapterPosition.progress)
    }
    val baseUrl = remember(chapter) { "https://hoshi.local/epub/${chapter.href}" }

    AndroidView(
        modifier = modifier.background(Color(0xFFF7F3EA)),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(ReaderSelectionBridge(this, onTextSelected), "HoshiTextSelection")
                webViewClient = EpubWebViewClient(book)
                setOnTouchListener(object : SwipePageTouchListener(context) {
                    override fun onTap(x: Float, y: Float) {
                        val density = resources.displayMetrics.density
                        evaluateJavascript(
                            ReaderSelectionScripts.selectInvocation(
                                x = androidPixelsToCssPixels(x, density),
                                y = androidPixelsToCssPixels(y, density),
                                maxLength = MAX_SELECTION_LENGTH,
                            ),
                            null,
                        )
                    }

                    override fun onLeftSwipe() {
                        navigatePage(ReaderNavigationDirection.Backward, onPreviousChapter, onSaveBookmark)
                    }

                    override fun onRightSwipe() {
                        navigatePage(ReaderNavigationDirection.Forward, onNextChapter, onSaveBookmark)
                    }
                })
                onWebViewReady(this)
            }
        },
        update = { webView ->
            webView.webViewClient = EpubWebViewClient(book)
            webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
        },
    )
}

private class EpubWebViewClient(private val book: EpubBook) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        if (uri.host != "hoshi.local") return null
        val path = uri.path.orEmpty().removePrefix("/epub/")
        val data = book.readResource(path) ?: return null
        return WebResourceResponse(book.mediaType(path), null, data.inputStream())
    }
}

private fun String.injectReaderShell(initialProgress: Double): String {
    val css = ReaderContentStyles.styleTag()
    val script = ReaderPaginationScripts.shellScript(initialProgress)
    val selectionScript = ReaderSelectionScripts.script()
    return replace("</head>", "$css\n$script\n$selectionScript\n</head>", ignoreCase = true)
        .takeIf { it != this }
        ?: "$css\n$script\n$selectionScript\n$this"
}

private fun WebView.navigatePage(
    direction: ReaderNavigationDirection,
    onLimit: () -> Boolean,
    onScrolled: (progress: Double) -> Unit,
) {
    evaluateJavascript(ReaderPaginationScripts.paginateInvocation(direction)) { result ->
        if (ReaderPaginationScripts.didScroll(result)) {
            evaluateJavascript(ReaderPaginationScripts.progressInvocation()) { progressResult ->
                ReaderPaginationScripts.doubleResult(progressResult)?.let(onScrolled)
            }
        } else {
            onLimit()
        }
    }
}

private class ReaderSelectionBridge(
    private val webView: WebView,
    private val onTextSelected: (ReaderSelectionData) -> Int?,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @JavascriptInterface
    fun postMessage(message: String) {
        val payload = runCatching { json.decodeFromString<ReaderSelectionPayload>(message) }.getOrNull() ?: return
        val data = ReaderSelectionData(
            text = payload.text,
            sentence = payload.sentence,
            rect = ReaderSelectionRect(
                x = payload.rect.x,
                y = payload.rect.y,
                width = payload.rect.width,
                height = payload.rect.height,
            ),
            normalizedOffset = payload.normalizedOffset,
        )
        webView.post {
            val highlightCount = onTextSelected(data) ?: return@post
            webView.evaluateJavascript(ReaderSelectionScripts.highlightInvocation(highlightCount), null)
        }
    }
}

@Serializable
private data class ReaderSelectionPayload(
    val text: String,
    val sentence: String,
    val rect: ReaderSelectionPayloadRect,
    val normalizedOffset: Int? = null,
)

@Serializable
private data class ReaderSelectionPayloadRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

private const val MAX_SELECTION_LENGTH = 16

internal fun androidPixelsToCssPixels(value: Float, density: Float): Float =
    value / density.coerceAtLeast(1f)
