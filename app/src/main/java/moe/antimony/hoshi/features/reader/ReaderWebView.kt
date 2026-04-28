package moe.antimony.hoshi.features.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupOptions
import moe.antimony.hoshi.features.dictionary.LookupPopupStackView
import moe.antimony.hoshi.features.dictionary.createLookupPopupItem
import java.io.File

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
    var showReaderMenu by remember { mutableStateOf(false) }
    var lookupPopups by remember { mutableStateOf<List<LookupPopupItem>>(emptyList()) }
    val context = LocalContext.current
    val fontManager = remember { ReaderFontManager(context.filesDir) }
    val view = LocalView.current
    val systemDarkTheme = isSystemInDarkTheme()
    fun lookupRootPopup(selection: ReaderSelectionData): Pair<LookupPopupItem, Int>? =
        createLookupPopupItem(
            selection = selection,
            options = LookupPopupOptions(isVertical = effectiveSettings.verticalWriting),
        )
    fun lookupChildPopup(selection: ReaderSelectionData): Pair<LookupPopupItem, Int>? =
        createLookupPopupItem(
            selection = selection,
            options = LookupPopupOptions(isVertical = false),
        )
    val handleTextSelected: (ReaderSelectionData) -> Int? = { selection ->
        lookupPopups = emptyList()
        val lookup = lookupRootPopup(selection)
        if (lookup != null) {
            val (popup, highlightCount) = lookup
            lookupPopups = listOf(popup)
            onTextSelected(selection) ?: highlightCount
        } else {
            onTextSelected(selection)
        }
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
    var displayedChapterPosition by remember(book) { mutableStateOf(chapterPosition) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val chromeState = remember(book, displayedChapterPosition) {
        ReaderChromeState(
            title = book.title,
            currentCharacter = book.characterCountAt(displayedChapterPosition.index, displayedChapterPosition.progress),
            totalCharacters = book.bookInfo.characterCount,
        )
    }

    BackHandler(onBack = onClose)
    val useLightSystemBars = when (effectiveSettings.theme) {
        ReaderTheme.Dark -> false
        ReaderTheme.System -> !systemDarkTheme
        ReaderTheme.Light, ReaderTheme.Sepia -> true
    }
    DisposableEffect(context, view, useLightSystemBars, systemDarkTheme) {
        val activity = context.findActivity()
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        }
        controller?.isAppearanceLightStatusBars = useLightSystemBars
        controller?.isAppearanceLightNavigationBars = useLightSystemBars
        onDispose {
            controller?.isAppearanceLightStatusBars = !systemDarkTheme
            controller?.isAppearanceLightNavigationBars = !systemDarkTheme
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(effectiveSettings.backgroundColor)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(top = ReaderWebViewTopPadding, bottom = ReaderWebViewBottomPadding),
        ) {
            ChapterWebView(
                book = book,
                chapterPosition = chapterPosition,
                onWebViewReady = { webView = it },
                onNextChapter = {
                    val next = chapterPosition.nextOrNull(book.chapters.lastIndex)
                    if (next != null) {
                        chapterPosition = next
                        displayedChapterPosition = next
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
                        displayedChapterPosition = previous
                        onSaveBookmark(previous.index, previous.progress)
                        true
                    } else {
                        false
                    }
                },
                onSaveBookmark = { progress ->
                    displayedChapterPosition = chapterPosition.copy(progress = progress)
                    onSaveBookmark(chapterPosition.index, progress)
                },
                readerSettings = effectiveSettings,
                onTextSelected = handleTextSelected,
                onClearLookupPopup = { lookupPopups = emptyList() },
                fontManager = fontManager,
                modifier = Modifier.fillMaxSize(),
            )
            LookupPopupStackView(
                popups = lookupPopups,
                onPopupsChange = { lookupPopups = it },
                lookupChildPopup = ::lookupChildPopup,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ReaderTopInfo(
            state = chromeState,
            colors = readerChromeColors(effectiveSettings),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 22.dp, start = 96.dp, end = 96.dp),
        )
        ReaderBottomChrome(
            settings = effectiveSettings,
            onClose = onClose,
            onMenu = { showReaderMenu = true },
            menuExpanded = showReaderMenu,
            onDismissMenu = { showReaderMenu = false },
            onAppearance = {
                showReaderMenu = false
                showAppearance = true
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        webView?.let { _ -> Unit }
    }
    if (showAppearance) {
        ReaderAppearanceSheet(
            settings = effectiveSettings,
            onSettingsChange = {
                effectiveSettings = it
                onReaderSettingsChange(it)
            },
            fontManager = fontManager,
            onDismiss = { showAppearance = false },
        )
    }
}

@Composable
private fun ReaderTopInfo(
    state: ReaderChromeState,
    colors: ReaderChromeColors,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = state.title,
            color = Color(colors.infoText),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
        Text(
            text = state.progressText(),
            color = Color(colors.infoText),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun BoxScope.ReaderBottomChrome(
    settings: ReaderSettings,
    onClose: () -> Unit,
    onMenu: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onAppearance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = readerChromeColors(settings)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 26.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReaderGlassButton(colors = colors, onClick = onClose) {
            ChevronLeftGlyph(Color(colors.buttonContent))
        }
        Spacer(Modifier.weight(1f))
        Box {
            ReaderGlassButton(colors = colors, onClick = onMenu) {
                SlidersGlyph(Color(colors.buttonContent))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(
                    text = { Text("Appearance") },
                    onClick = onAppearance,
                )
            }
        }
    }
}

@Composable
private fun ReaderGlassButton(
    colors: ReaderChromeColors,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = Modifier
            .shadow(18.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
            .border(BorderStroke(1.dp, Color(colors.buttonBorder)), CircleShape)
            .size(60.dp),
        shape = CircleShape,
        color = Color(colors.buttonContainer),
        tonalElevation = 6.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

@Composable
private fun ChevronLeftGlyph(color: Color) {
    Canvas(modifier = Modifier.size(30.dp)) {
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        val path = Path().apply {
            moveTo(size.width * 0.64f, size.height * 0.12f)
            lineTo(size.width * 0.28f, size.height * 0.50f)
            lineTo(size.width * 0.64f, size.height * 0.88f)
        }
        drawPath(path, color, style = stroke)
    }
}

@Composable
private fun SlidersGlyph(color: Color) {
    Canvas(modifier = Modifier.size(30.dp)) {
        val stroke = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
        val yValues = listOf(size.height * 0.22f, size.height * 0.50f, size.height * 0.78f)
        val knobXs = listOf(size.width * 0.62f, size.width * 0.38f, size.width * 0.70f)
        yValues.forEachIndexed { index, y ->
            drawLine(color, start = androidx.compose.ui.geometry.Offset(size.width * 0.18f, y), end = androidx.compose.ui.geometry.Offset(size.width * 0.82f, y), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawCircle(color, radius = 4.2.dp.toPx(), center = androidx.compose.ui.geometry.Offset(knobXs[index], y), style = Stroke(width = stroke.width))
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
    readerSettings: ReaderSettings,
    onTextSelected: (ReaderSelectionData) -> Int?,
    onClearLookupPopup: () -> Unit,
    fontManager: ReaderFontManager,
    modifier: Modifier = Modifier,
) {
    val chapter = book.chapters[chapterPosition.index]
    val fontFaceUrl = remember(readerSettings.selectedFont) {
        fontManager.webViewFontUrl(readerSettings.selectedFont)
    }
    val html = remember(chapter, chapterPosition.progress, readerSettings, fontFaceUrl) {
        chapter.html.injectReaderShell(
            initialProgress = chapterPosition.progress,
            settings = readerSettings,
            fontFaceUrl = fontFaceUrl,
        )
    }
    val baseUrl = remember(chapter) { "https://hoshi.local/epub/${chapter.href}" }

    AndroidView(
        modifier = modifier.background(Color(readerSettings.backgroundColor)),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                alpha = 0f
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(ReaderSelectionBridge(this, onTextSelected), "HoshiTextSelection")
                addJavascriptInterface(ReaderRestoreBridge(this), "HoshiReaderRestore")
                webViewClient = EpubWebViewClient(book, fontManager)
                setOnTouchListener(object : SwipePageTouchListener(context) {
                    override fun onTap(x: Float, y: Float) {
                        val density = resources.displayMetrics.density
                        evaluateJavascript(
                            ReaderSelectionScripts.selectInvocation(
                                x = androidPixelsToCssPixels(x, density),
                                y = androidPixelsToCssPixels(y, density),
                                maxLength = MAX_SELECTION_LENGTH,
                            ),
                        ) { result ->
                            if (ReaderSelectionScripts.didSelectNothing(result)) {
                                onClearLookupPopup()
                            }
                        }
                    }

                    override fun onLeftSwipe() {
                        onClearLookupPopup()
                        navigatePage(ReaderNavigationDirection.Backward, onPreviousChapter, onSaveBookmark)
                    }

                    override fun onRightSwipe() {
                        onClearLookupPopup()
                        navigatePage(ReaderNavigationDirection.Forward, onNextChapter, onSaveBookmark)
                    }
                })
                onWebViewReady(this)
            }
        },
        update = { webView ->
            val loadKey = "$baseUrl#${html.hashCode()}"
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.animate().cancel()
                webView.alpha = 0f
                webView.webViewClient = EpubWebViewClient(book, fontManager)
                webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderAppearanceSheet(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    fontManager: ReaderFontManager,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importedFonts by remember { mutableStateOf(fontManager.storedFonts()) }
    var fontMenuExpanded by remember { mutableStateOf(false) }
    var fontToDelete by remember { mutableStateOf<String?>(null) }
    var isImportingFont by remember { mutableStateOf(false) }
    val fontImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        scope.launch {
            isImportingFont = true
            runCatching {
                withContext(Dispatchers.IO) {
                    fontManager.importFont(context.contentResolver, uri)
                }
            }
            importedFonts = fontManager.storedFonts()
            isImportingFont = false
        }
    }
    val fontOptions = remember(importedFonts, settings.selectedFont) {
        (ReaderFontManager.defaultFonts + importedFonts.map { it.name } + settings.selectedFont)
            .filter { it.isNotBlank() }
            .distinct()
    }

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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Font", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        TextButton(onClick = { fontMenuExpanded = true }) {
                            Text(settings.selectedFont)
                        }
                        DropdownMenu(
                            expanded = fontMenuExpanded,
                            onDismissRequest = { fontMenuExpanded = false },
                        ) {
                            fontOptions.forEach { fontName ->
                                DropdownMenuItem(
                                    text = { Text(fontName) },
                                    onClick = {
                                        fontMenuExpanded = false
                                        onSettingsChange(settings.copy(selectedFont = fontName))
                                    },
                                )
                            }
                        }
                    }
                    if (!fontManager.isDefaultFont(settings.selectedFont)) {
                        TextButton(onClick = { fontToDelete = settings.selectedFont }) {
                            Text("Delete")
                        }
                    }
                }
                Button(
                    onClick = { fontImporter.launch(fontMimeTypes) },
                    enabled = !isImportingFont,
                ) {
                    Text(if (isImportingFont) "Importing..." else "Import Font")
                }
            }
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
    fontToDelete?.let { fontName ->
        AlertDialog(
            onDismissRequest = { fontToDelete = null },
            title = { Text("Delete \"$fontName\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        fontManager.deleteFont(fontName)
                        importedFonts = fontManager.storedFonts()
                        onSettingsChange(settings.copy(selectedFont = ReaderFontManager.defaultFonts.first()))
                        fontToDelete = null
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { fontToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private val fontMimeTypes = arrayOf(
    "font/ttf",
    "font/otf",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/vnd.ms-opentype",
    "application/octet-stream",
    "*/*",
)

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

private class EpubWebViewClient(
    private val book: EpubBook,
    private val fontManager: ReaderFontManager,
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        if (uri.host != "hoshi.local") return null
        if (uri.path.orEmpty().startsWith("/fonts/")) {
            val fileName = Uri.decode(uri.path.orEmpty().removePrefix("/fonts/"))
            val fontFile = fontManager.fontFileForRequest(fileName) ?: return null
            return WebResourceResponse(fontFile.mediaType(), null, fontFile.inputStream())
        }
        val path = uri.path.orEmpty().removePrefix("/epub/")
        val data = book.readResource(path) ?: return null
        return WebResourceResponse(book.mediaType(path), null, data.inputStream())
    }
}

private fun String.injectReaderShell(
    initialProgress: Double,
    settings: ReaderSettings,
    fontFaceUrl: String?,
): String {
    val css = ReaderContentStyles.styleTag(settings, fontFaceUrl)
    val script = ReaderPaginationScripts.shellScript(initialProgress, settings)
    val selectionScript = ReaderSelectionScripts.script()
    return replace("</head>", "$css\n$script\n$selectionScript\n</head>", ignoreCase = true)
        .takeIf { it != this }
        ?: "$css\n$script\n$selectionScript\n$this"
}

private fun File.mediaType(): String = when (extension.lowercase()) {
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    else -> "application/octet-stream"
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

private class ReaderRestoreBridge(
    private val webView: WebView,
) {
    @JavascriptInterface
    fun postMessage(@Suppress("UNUSED_PARAMETER") message: String) {
        webView.post {
            webView.animate()
                .alpha(1f)
                .setDuration(250L)
                .start()
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
private val ReaderWebViewTopPadding = 72.dp
private val ReaderWebViewBottomPadding = 96.dp

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun androidPixelsToCssPixels(value: Float, density: Float): Float =
    value / density.coerceAtLeast(1f)

private fun String.codePointCount(): Int =
    codePointCount(0, length)
