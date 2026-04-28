package moe.antimony.hoshi.features.reader

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.dictionary.LookupEngine
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.features.dictionary.LookupPopupState
import moe.antimony.hoshi.features.dictionary.LookupPopupView

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
    readerSettings: ReaderSettings = ReaderSettings(),
    onReaderSettingsChange: (ReaderSettings) -> Unit = {},
    onSaveBookmark: (chapterIndex: Int, progress: Double) -> Unit = { _, _ -> },
    onTextSelected: (ReaderSelectionData) -> Int? = { null },
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var effectiveSettings by remember(readerSettings) { mutableStateOf(readerSettings) }
    var showAppearance by remember { mutableStateOf(false) }
    var lookupPopup by remember { mutableStateOf<LookupPopupState?>(null) }
    val handleTextSelected: (ReaderSelectionData) -> Int? = { selection ->
        val results = runCatching { LookupEngine.lookup(selection.text) }.getOrDefault(emptyList())
        lookupPopup = if (results.isEmpty()) null else LookupPopupState(selection, results)
        onTextSelected(selection) ?: results.firstOrNull()?.matched?.codePointCount()
    }
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
                actions = {
                    IconButton(onClick = { showAppearance = true }) {
                        Text("Aa")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(effectiveSettings.backgroundColor)),
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
                readerSettings = effectiveSettings,
                onTextSelected = handleTextSelected,
                modifier = Modifier.fillMaxSize(),
            )
            lookupPopup?.let { popup ->
                LookupPopupView(
                    state = popup,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            webView?.let { _ -> Unit }
        }
    }
    if (showAppearance) {
        ReaderAppearanceSheet(
            settings = effectiveSettings,
            onSettingsChange = {
                effectiveSettings = it
                onReaderSettingsChange(it)
            },
            onDismiss = { showAppearance = false },
        )
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
    readerSettings: ReaderSettings,
    onTextSelected: (ReaderSelectionData) -> Int?,
    modifier: Modifier = Modifier,
) {
    val chapter = book.chapters[chapterPosition.index]
    val html = remember(chapter, chapterPosition.progress, readerSettings) {
        chapter.html.injectReaderShell(
            initialProgress = chapterPosition.progress,
            settings = readerSettings,
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderAppearanceSheet(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Appearance", style = MaterialTheme.typography.titleLarge)
            SegmentedRow(
                label = "Theme",
                options = ReaderTheme.entries.map { it.label },
                selected = settings.theme.label,
                onSelected = { label ->
                    ReaderTheme.entries.firstOrNull { it.label == label }?.let {
                        onSettingsChange(settings.copy(theme = it))
                    }
                },
            )
            SegmentedRow(
                label = "Text Orientation",
                options = listOf("縦", "横"),
                selected = if (settings.verticalWriting) "縦" else "横",
                onSelected = { label ->
                    onSettingsChange(settings.copy(verticalWriting = label == "縦"))
                },
            )
            StepperRow(
                label = "Font Size",
                value = settings.fontSize.toString(),
                onDecrease = {
                    onSettingsChange(settings.copy(fontSize = (settings.fontSize - 1).coerceAtLeast(16)))
                },
                onIncrease = {
                    onSettingsChange(settings.copy(fontSize = (settings.fontSize + 1).coerceAtMost(40)))
                },
            )
            StepperRow(
                label = "Horizontal Padding",
                value = "${settings.horizontalPadding}%",
                onDecrease = {
                    onSettingsChange(settings.copy(horizontalPadding = (settings.horizontalPadding - 1).coerceAtLeast(0)))
                },
                onIncrease = {
                    onSettingsChange(settings.copy(horizontalPadding = (settings.horizontalPadding + 1).coerceAtMost(50)))
                },
            )
            StepperRow(
                label = "Vertical Padding",
                value = "${settings.verticalPadding}%",
                onDecrease = {
                    onSettingsChange(settings.copy(verticalPadding = (settings.verticalPadding - 1).coerceAtLeast(0)))
                },
                onIncrease = {
                    onSettingsChange(settings.copy(verticalPadding = (settings.verticalPadding + 1).coerceAtMost(50)))
                },
            )
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Line Height")
                    Text(String.format(java.util.Locale.US, "%.2f", settings.lineHeight))
                }
                Slider(
                    value = settings.lineHeight.toFloat(),
                    onValueChange = { value ->
                        onSettingsChange(settings.copy(lineHeight = (kotlin.math.round(value * 20) / 20.0)))
                    },
                    valueRange = 1.0f..2.5f,
                    steps = 29,
                )
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun SegmentedRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val active = option == selected
                if (active) {
                    Button(onClick = { onSelected(option) }) {
                        Text(option)
                    }
                } else {
                    TextButton(onClick = { onSelected(option) }) {
                        Text(option)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onDecrease) {
                Text("-")
            }
            Text(value)
            TextButton(onClick = onIncrease) {
                Text("+")
            }
        }
    }
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

private fun String.injectReaderShell(initialProgress: Double, settings: ReaderSettings): String {
    val css = ReaderContentStyles.styleTag(settings)
    val script = ReaderPaginationScripts.shellScript(initialProgress, settings)
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

private fun String.codePointCount(): Int =
    codePointCount(0, length)
