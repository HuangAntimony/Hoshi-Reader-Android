package moe.antimony.hoshi.features.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.BookStorage
import moe.antimony.hoshi.features.audio.AudioSettingsStore
import moe.antimony.hoshi.features.dictionary.DictionarySettingsStore
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupOptions
import moe.antimony.hoshi.features.dictionary.LookupPopupStackView
import moe.antimony.hoshi.features.dictionary.createLookupPopupItem
import moe.antimony.hoshi.features.sasayaki.SasayakiAudioRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRange
import moe.antimony.hoshi.features.sasayaki.SasayakiMatch
import moe.antimony.hoshi.features.sasayaki.SasayakiMatchData
import moe.antimony.hoshi.features.sasayaki.SasayakiPlayer
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsStore
import moe.antimony.hoshi.features.sasayaki.SasayakiSheet
import moe.antimony.hoshi.webview.disableNativeOverscrollStretch
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
    bookRoot: File? = null,
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
    var showChapters by remember { mutableStateOf(false) }
    var showSasayaki by remember { mutableStateOf(false) }
    var showReaderMenu by remember { mutableStateOf(false) }
    var lookupPopups by remember { mutableStateOf<List<LookupPopupItem>>(emptyList()) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var readerWebViewViewportSize by remember { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current
    val fontManager = remember { ReaderFontManager(context.filesDir) }
    val dictionarySettingsStore = remember { DictionarySettingsStore(context) }
    val audioSettingsStore = remember { AudioSettingsStore(context) }
    val bookStorage = remember { BookStorage(context.filesDir) }
    val sasayakiSettingsStore = remember { SasayakiSettingsStore(context) }
    var sasayakiSettings by remember { mutableStateOf(sasayakiSettingsStore.load()) }
    val sasayakiMatchData = remember(bookRoot) { bookRoot?.let(bookStorage::loadSasayakiMatch) }
    val sasayakiAudioRepository = remember(bookRoot) { bookRoot?.let(::SasayakiAudioRepository) }
    var sasayakiPlayer by remember { mutableStateOf<SasayakiPlayer?>(null) }
    var sasayakiWasPausedByLookup by remember { mutableStateOf(false) }
    val view = LocalView.current
    val systemDarkTheme = isSystemInDarkTheme()
    val clampedInitialIndex = initialChapterIndex.coerceIn(0, book.chapters.lastIndex)
    var readerPosition by remember(book) {
        mutableStateOf(
            ReaderPositionState(
                ReaderChapterPosition(
                    index = clampedInitialIndex,
                    progress = initialProgress.coerceIn(0.0, 1.0),
                ),
            ),
        )
    }
    fun sasayakiCueForSelection(selection: ReaderSelectionData): SasayakiMatch? {
        val player = sasayakiPlayer ?: return null
        val offset = selection.normalizedOffset ?: return null
        if (!sasayakiSettings.enabled || !player.hasAudio) return null
        return player.findCue(chapterIndex = readerPosition.displayedPosition.index, offset = offset)
    }
    fun lookupRootPopup(selection: ReaderSelectionData): Pair<LookupPopupItem, Int>? =
        createLookupPopupItem(
            selection = selection,
            options = LookupPopupOptions(
                isVertical = effectiveSettings.verticalWriting,
                isFullWidth = effectiveSettings.popupFullWidth,
                width = effectiveSettings.popupWidth,
                height = effectiveSettings.popupHeight,
                swipeToDismiss = effectiveSettings.popupSwipeToDismiss,
                swipeThreshold = effectiveSettings.popupSwipeThreshold,
                dictionarySettings = dictionarySettingsStore.load(),
                darkMode = effectiveSettings.usesDarkInterface(systemDarkTheme),
                eInkMode = effectiveSettings.eInkMode,
                audioSettings = audioSettingsStore.load(),
            ),
        )?.let { (popup, highlightCount) ->
            popup.copy(sasayakiCue = sasayakiCueForSelection(selection)) to highlightCount
        }
    fun lookupChildPopup(selection: ReaderSelectionData): Pair<LookupPopupItem, Int>? =
        createLookupPopupItem(
            selection = selection,
            options = LookupPopupOptions(
                isVertical = false,
                isFullWidth = false,
                width = effectiveSettings.popupWidth,
                height = effectiveSettings.popupHeight,
                swipeToDismiss = effectiveSettings.popupSwipeToDismiss,
                swipeThreshold = effectiveSettings.popupSwipeThreshold,
                dictionarySettings = dictionarySettingsStore.load(),
                darkMode = effectiveSettings.usesDarkInterface(systemDarkTheme),
                eInkMode = effectiveSettings.eInkMode,
                audioSettings = audioSettingsStore.load(),
            ),
        )?.let { (popup, highlightCount) ->
            popup.copy(sasayakiCue = sasayakiCueForSelection(selection)) to highlightCount
        }
    fun clearReaderSelection() {
        webView?.evaluateJavascript(ReaderSelectionScripts.clearInvocation(), null)
    }
    fun resumeSasayakiAfterLookupIfNeeded() {
        val player = sasayakiPlayer
        if (sasayakiWasPausedByLookup && player != null && !player.isPlaying) {
            player.togglePlayback()
        }
        sasayakiWasPausedByLookup = false
    }
    fun setLookupPopups(nextPopups: List<LookupPopupItem>) {
        lookupPopups = nextPopups
        if (nextPopups.isEmpty()) {
            resumeSasayakiAfterLookupIfNeeded()
        }
    }
    fun closeLookupPopupsAndSelection() {
        clearReaderSelection()
        setLookupPopups(emptyList())
    }
    fun updateSasayakiSettings(settings: SasayakiSettings) {
        sasayakiSettings = settings
        sasayakiSettingsStore.save(settings)
        sasayakiPlayer?.autoScroll = settings.autoScroll
    }
    fun pauseSasayakiForLookupIfNeeded() {
        val player = sasayakiPlayer
        if (sasayakiSettings.enabled && sasayakiSettings.autoPause && player?.isPlaying == true) {
            player.pausePlayback()
            sasayakiWasPausedByLookup = true
        } else if (!sasayakiSettings.autoPause) {
            sasayakiWasPausedByLookup = false
        }
    }
    val handleTextSelected: (ReaderSelectionData) -> Int? = { selection ->
        setLookupPopups(emptyList())
        val lookup = lookupRootPopup(selection)
        if (lookup != null) {
            val (popup, highlightCount) = lookup
            pauseSasayakiForLookupIfNeeded()
            setLookupPopups(listOf(popup))
            onTextSelected(selection) ?: highlightCount
        } else {
            onTextSelected(selection)
        }
    }
    val chromeState = remember(book, readerPosition.displayedPosition) {
        ReaderChromeState(
            title = book.title,
            currentCharacter = book.characterCountAt(readerPosition.displayedPosition.index, readerPosition.displayedPosition.progress),
            totalCharacters = book.bookInfo.characterCount,
        )
    }
    LaunchedEffect(bookRoot, sasayakiMatchData) {
        sasayakiPlayer?.release()
        sasayakiPlayer = if (bookRoot != null && sasayakiMatchData != null) {
            SasayakiPlayer(
                context = context,
                bookRoot = bookRoot,
                bookStorage = bookStorage,
                matchData = sasayakiMatchData,
                getCurrentChapterIndex = { readerPosition.displayedPosition.index },
                onCue = { cue, reveal ->
                    webView?.evaluateJavascript(
                        ReaderPaginationScripts.highlightSasayakiCueInvocation(cue.id, reveal),
                    ) { progressResult ->
                        ReaderPaginationScripts.doubleResult(progressResult)?.let { progress ->
                            val updatedPosition = readerPosition.recordPageProgress(progress)
                            readerPosition = updatedPosition
                            onSaveBookmark(updatedPosition.displayedPosition.index, updatedPosition.displayedPosition.progress)
                        }
                    }
                },
                onClearCue = {
                    webView?.evaluateJavascript(ReaderPaginationScripts.clearSasayakiCueInvocation(), null)
                },
                onLoadChapter = { chapterIndex ->
                    val target = ReaderChapterPosition(index = chapterIndex, progress = 0.0)
                    readerPosition = readerPosition.jumpTo(target)
                    onSaveBookmark(target.index, target.progress)
                },
            )
        } else {
            null
        }
    }
    DisposableEffect(Unit) {
        onDispose { sasayakiPlayer?.release() }
    }
    LaunchedEffect(showReaderMenu, showSasayaki) {
        sasayakiSettings = sasayakiSettingsStore.load()
    }
    sasayakiPlayer?.autoScroll = sasayakiSettings.autoScroll

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
            .background(Color(effectiveSettings.backgroundColor(systemDarkTheme))),
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
                chapterPosition = readerPosition.loadPosition,
                webViewViewportSize = readerWebViewViewportSize,
                onReaderViewportSizeChanged = { size ->
                    if (size != readerWebViewViewportSize) {
                        if (readerWebViewViewportSize != IntSize.Zero) {
                            readerPosition = readerPosition.prepareReloadAtDisplayedPosition()
                        }
                        readerWebViewViewportSize = size
                    }
                },
                onWebViewReady = { webView = it },
                onNextChapter = {
                    val next = readerPosition.loadPosition.nextOrNull(book.chapters.lastIndex)
                    if (next != null) {
                        readerPosition = readerPosition.jumpTo(next)
                        onSaveBookmark(next.index, next.progress)
                        true
                    } else {
                        false
                    }
                },
                onPreviousChapter = {
                    val previous = readerPosition.loadPosition.previousOrNull()
                    if (previous != null) {
                        readerPosition = readerPosition.jumpTo(previous)
                        onSaveBookmark(previous.index, previous.progress)
                        true
                    } else {
                        false
                    }
                },
                onSaveBookmark = { progress ->
                    val updatedPosition = readerPosition.recordPageProgress(progress)
                    readerPosition = updatedPosition
                    onSaveBookmark(updatedPosition.displayedPosition.index, updatedPosition.displayedPosition.progress)
                },
                readerSettings = effectiveSettings,
                sasayakiCuesJson = sasayakiMatchData?.cuesJsonForChapter(readerPosition.loadPosition.index),
                sasayakiTextColor = sasayakiSettings.textColor(effectiveSettings.usesDarkInterface(systemDarkTheme)),
                sasayakiBackgroundColor = sasayakiSettings.backgroundColor(effectiveSettings.usesDarkInterface(systemDarkTheme)),
                onTextSelected = handleTextSelected,
                onClearLookupPopup = ::closeLookupPopupsAndSelection,
                fontManager = fontManager,
                systemDark = systemDarkTheme,
                modifier = Modifier.fillMaxSize(),
            )
            LookupPopupStackView(
                popups = lookupPopups,
                onPopupsChange = ::setLookupPopups,
                lookupChildPopup = ::lookupChildPopup,
                onRootPopupDismissed = ::clearReaderSelection,
                sasayakiWasPaused = sasayakiWasPausedByLookup,
                sasayakiIsPlaying = sasayakiPlayer?.isPlaying == true,
                onSasayakiReplayCue = { cue -> sasayakiPlayer?.playCue(cue, stop = true) },
                onSasayakiTogglePlayback = { sasayakiPlayer?.togglePlayback() },
                onSasayakiPauseStateCleared = { sasayakiWasPausedByLookup = false },
                onSasayakiPlayForward = { cue ->
                    sasayakiPlayer?.playCue(cue, stop = false)
                    setLookupPopups(emptyList())
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        ReaderTopInfo(
            state = chromeState,
            settings = effectiveSettings,
            colors = readerChromeColors(effectiveSettings, systemDarkTheme),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(start = 96.dp, end = 96.dp),
        )
        if (!effectiveSettings.showProgressTop) {
            ReaderBottomProgress(
                state = chromeState,
                settings = effectiveSettings,
                colors = readerChromeColors(effectiveSettings, systemDarkTheme),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 62.dp),
            )
        }
        ReaderBottomChrome(
            settings = effectiveSettings,
            colors = readerChromeColors(effectiveSettings, systemDarkTheme),
            onClose = onClose,
            onMenu = { showReaderMenu = true },
            menuExpanded = showReaderMenu,
            onDismissMenu = { showReaderMenu = false },
            onChapters = {
                showReaderMenu = false
                showChapters = true
            },
            onAppearance = {
                showReaderMenu = false
                showAppearance = true
            },
            onSasayaki = if (sasayakiSettings.enabled && sasayakiMatchData != null) {
                {
                    showReaderMenu = false
                    showSasayaki = true
                }
            } else {
                null
            },
            onSasayakiToggle = sasayakiPlayer
                ?.takeIf { sasayakiSettings.enabled && sasayakiSettings.showReaderToggle && it.hasAudio }
                ?.let { player ->
                    ({
                        if (sasayakiWasPausedByLookup) {
                            sasayakiWasPausedByLookup = false
                        } else {
                            player.togglePlayback()
                        }
                    })
                },
            sasayakiPlaying = sasayakiPlayer?.isPlaying == true || sasayakiWasPausedByLookup,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        webView?.let { _ -> Unit }
    }
    if (showAppearance) {
        ReaderAppearanceSheet(
            settings = effectiveSettings,
            onSettingsChange = {
                readerPosition = readerPosition.prepareReloadAtDisplayedPosition()
                effectiveSettings = it
                onReaderSettingsChange(it)
            },
            fontManager = fontManager,
            onDismiss = { showAppearance = false },
        )
    }
    if (showChapters) {
        ReaderChapterSheet(
            book = book,
            currentPosition = readerPosition.displayedPosition,
            onJump = { target ->
                closeLookupPopupsAndSelection()
                readerPosition = readerPosition.jumpTo(target)
                onSaveBookmark(target.index, target.progress)
                showChapters = false
            },
            onDismiss = { showChapters = false },
        )
    }
    if (showSasayaki && sasayakiPlayer != null && sasayakiAudioRepository != null) {
        SasayakiSheet(
            player = requireNotNull(sasayakiPlayer),
            audioRepository = sasayakiAudioRepository,
            settings = sasayakiSettings,
            onSettingsChange = ::updateSasayakiSettings,
            onDismiss = { showSasayaki = false },
        )
    }
}

@Composable
private fun ReaderTopInfo(
    state: ReaderChromeState,
    settings: ReaderSettings,
    colors: ReaderChromeColors,
    modifier: Modifier = Modifier,
) {
    val progress = state.progressText(settings)
    if (!settings.showTitle && (progress.isBlank() || !settings.showProgressTop)) return
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (settings.showTitle) {
            Text(
                text = state.title,
                color = Color(colors.infoText),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
        if (settings.showProgressTop && progress.isNotBlank()) {
            Text(
                text = progress,
                color = Color(colors.infoText),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ReaderBottomProgress(
    state: ReaderChromeState,
    settings: ReaderSettings,
    colors: ReaderChromeColors,
    modifier: Modifier = Modifier,
) {
    val progress = state.progressText(settings)
    if (progress.isBlank()) return
    Text(
        text = progress,
        color = Color(colors.infoText),
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier,
    )
}

@Composable
private fun BoxScope.ReaderBottomChrome(
    settings: ReaderSettings,
    colors: ReaderChromeColors,
    onClose: () -> Unit,
    onMenu: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onChapters: () -> Unit,
    onAppearance: () -> Unit,
    onSasayaki: (() -> Unit)?,
    onSasayakiToggle: (() -> Unit)?,
    sasayakiPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    if (menuExpanded) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .clickable(onClick = onDismissMenu),
        )
        ReaderMenuCard(
            colors = colors,
            onChapters = onChapters,
            onAppearance = onAppearance,
            onSasayaki = onSasayaki,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 24.dp, bottom = 58.dp),
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 26.dp, end = 26.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReaderGlassButton(colors = colors, onClick = onClose) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                modifier = Modifier.size(28.dp),
                tint = Color(colors.buttonContent),
            )
        }
        Spacer(Modifier.weight(1f))
        if (onSasayakiToggle != null) {
            ReaderGlassButton(colors = colors, onClick = onSasayakiToggle) {
                Icon(
                    imageVector = if (sasayakiPlaying) Icons.Rounded.Pause else Icons.Rounded.GraphicEq,
                    contentDescription = if (sasayakiPlaying) "Pause Sasayaki" else "Play Sasayaki",
                    modifier = Modifier.size(26.dp),
                    tint = Color(colors.buttonContent),
                )
            }
            Spacer(Modifier.width(14.dp))
        }
        ReaderGlassButton(colors = colors, onClick = onMenu) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = "Reader Menu",
                modifier = Modifier.size(26.dp),
                tint = Color(colors.buttonContent),
            )
        }
    }
}

@Composable
private fun ReaderMenuCard(
    colors: ReaderChromeColors,
    onChapters: () -> Unit,
    onAppearance: () -> Unit,
    onSasayaki: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .width(276.dp),
        shape = RoundedCornerShape(28.dp),
        color = Color(colors.menuContainer),
        border = BorderStroke(1.dp, Color(colors.menuBorder)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
        ) {
            ReaderMenuItem(
                text = "Chapters",
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.List,
                        contentDescription = null,
                        tint = Color(colors.menuContent),
                    )
                },
                colors = colors,
                onClick = onChapters,
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 22.dp),
                color = Color(colors.menuBorder),
            )
            ReaderMenuItem(
                text = "Appearance",
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Palette,
                        contentDescription = null,
                        tint = Color(colors.menuContent),
                    )
                },
                colors = colors,
                onClick = onAppearance,
            )
            if (onSasayaki != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 22.dp),
                    color = Color(colors.menuBorder),
                )
                ReaderMenuItem(
                    text = "Sasayaki",
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = Color(colors.menuContent),
                        )
                    },
                    colors = colors,
                    onClick = onSasayaki,
                )
            }
        }
    }
}

@Composable
private fun ReaderMenuItem(
    text: String,
    icon: @Composable () -> Unit,
    colors: ReaderChromeColors,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            modifier = Modifier.size(30.dp),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Text(
            text = text,
            color = Color(colors.menuContent),
            style = MaterialTheme.typography.titleLarge,
        )
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
            .size(54.dp),
        shape = CircleShape,
        color = Color(colors.buttonContainer),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun ChapterWebView(
    book: EpubBook,
    chapterPosition: ReaderChapterPosition,
    webViewViewportSize: IntSize,
    onReaderViewportSizeChanged: (IntSize) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    onNextChapter: () -> Boolean,
    onPreviousChapter: () -> Boolean,
    onSaveBookmark: (progress: Double) -> Unit,
    readerSettings: ReaderSettings,
    sasayakiCuesJson: String?,
    sasayakiTextColor: Long,
    sasayakiBackgroundColor: Long,
    onTextSelected: (ReaderSelectionData) -> Int?,
    onClearLookupPopup: () -> Unit,
    fontManager: ReaderFontManager,
    systemDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val currentOnTextSelected = rememberUpdatedState(onTextSelected)
    val chapter = book.chapters[chapterPosition.index]
    val fontFaceUrl = remember(readerSettings.selectedFont) {
        fontManager.webViewFontUrl(readerSettings.selectedFont)
    }
    val baseUrl = remember(chapter) { "https://hoshi.local/epub/${chapter.href}" }
    val readerSetupScript = remember(
        chapter,
        chapterPosition.progress,
        readerSettings,
        fontFaceUrl,
        systemDark,
        sasayakiCuesJson,
        sasayakiTextColor,
        sasayakiBackgroundColor,
    ) {
        readerSetupScript(
            initialProgress = chapterPosition.progress,
            settings = readerSettings,
            fontFaceUrl = fontFaceUrl,
            systemDark = systemDark,
            sasayakiCuesJson = sasayakiCuesJson,
            sasayakiTextColor = sasayakiTextColor,
            sasayakiBackgroundColor = sasayakiBackgroundColor,
        )
    }

    AndroidView(
        modifier = modifier
            .onSizeChanged(onReaderViewportSizeChanged)
            .background(Color(readerSettings.backgroundColor(systemDark))),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                disableNativeOverscrollStretch()
                alpha = 0f
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(
                    ReaderSelectionBridge(this) { selection ->
                        currentOnTextSelected.value(selection)
                    },
                    "HoshiTextSelection",
                )
                addJavascriptInterface(ReaderRestoreBridge(this), "HoshiReaderRestore")
                webViewClient = EpubWebViewClient(book, fontManager) { view ->
                    view.evaluateJavascript(readerSetupScript, null)
                }
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
            val loadKey = "$baseUrl#${readerSetupScript.hashCode()}#$webViewViewportSize"
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.alpha = 0f
                webView.webViewClient = EpubWebViewClient(book, fontManager) { view ->
                    view.evaluateJavascript(readerSetupScript, null)
                }
                webView.loadUrl(baseUrl)
            }
        },
    )
}

private class EpubWebViewClient(
    private val book: EpubBook,
    private val fontManager: ReaderFontManager,
    private val onReaderPageFinished: (WebView) -> Unit,
) : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        if (Uri.parse(url ?: return).host == "hoshi.local") {
            onReaderPageFinished(view)
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        if (uri.host != "hoshi.local") return null
        if (uri.path.orEmpty().startsWith("/fonts/")) {
            val fileName = Uri.decode(uri.path.orEmpty().removePrefix("/fonts/"))
            val fontFile = fontManager.fontFileForRequest(fileName) ?: return null
            return WebResourceResponse(fontFile.mediaType(), null, fontFile.inputStream())
        }
        val path = uri.path.orEmpty().removePrefix("/epub/")
        val mediaType = book.mediaType(path)
        val data = book.readResource(path)?.let { sanitizeReaderResource(mediaType, it) } ?: return null
        val encoding = if (mediaType.substringBefore(';').trim().equals("text/css", ignoreCase = true)) "UTF-8" else null
        return WebResourceResponse(mediaType, encoding, data.inputStream())
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        view.destroy()
        return true
    }
}

private fun readerSetupScript(
    initialProgress: Double,
    settings: ReaderSettings,
    fontFaceUrl: String?,
    systemDark: Boolean,
    sasayakiCuesJson: String?,
    sasayakiTextColor: Long,
    sasayakiBackgroundColor: Long,
): String {
    val css = ReaderContentStyles.css(
        settings = settings,
        fontFaceUrl = fontFaceUrl,
        systemDark = systemDark,
        sasayakiTextColor = sasayakiTextColor,
        sasayakiBackgroundColor = sasayakiBackgroundColor,
    ).javaScriptStringLiteral()
    val selectionScript = ReaderSelectionScripts.source()
    val paginationScript = ReaderPaginationScripts.shellScript(
        initialProgress = initialProgress,
        settings = settings,
        sasayakiCuesJson = sasayakiCuesJson,
    ).scriptTagBody()
    return """
        (function() {
          var style = document.createElement('style');
          style.textContent = $css;
          document.head.appendChild(style);
          $selectionScript
          $paginationScript
        })();
    """.trimIndent()
}

private fun String.scriptTagBody(): String =
    substringAfter("<script>").substringBeforeLast("</script>").trim()

private fun String.javaScriptStringLiteral(): String =
    buildString(length + 2) {
        append('"')
        this@javaScriptStringLiteral.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
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
            webView.alpha = 1f
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
private val ReaderWebViewTopPadding = 44.dp
private val ReaderWebViewBottomPadding = 56.dp

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun SasayakiMatchData.cuesJsonForChapter(chapterIndex: Int): String {
    val cues = matches
        .filter { it.chapterIndex == chapterIndex }
        .map { SasayakiCueRange(id = it.id, start = it.start, length = it.length) }
    return Json.encodeToString(ListSerializer(SasayakiCueRange.serializer()), cues)
}

internal fun androidPixelsToCssPixels(value: Float, density: Float): Float =
    value / density.coerceAtLeast(1f)

private fun String.codePointCount(): Int =
    codePointCount(0, length)
