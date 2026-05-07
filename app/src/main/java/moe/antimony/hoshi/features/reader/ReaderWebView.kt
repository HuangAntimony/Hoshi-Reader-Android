package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiMatch

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import java.util.WeakHashMap
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupOptions
import moe.antimony.hoshi.features.dictionary.LookupPopupStackView
import moe.antimony.hoshi.features.dictionary.createLookupPopupItem
import moe.antimony.hoshi.features.sasayaki.BookSasayakiPlaybackRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiAudioRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRange
import moe.antimony.hoshi.features.sasayaki.SasayakiPlayer
import moe.antimony.hoshi.features.sasayaki.SasayakiScreenAwake
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.features.sasayaki.SasayakiSheet
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderWebView(
    book: EpubBook,
    bookRoot: File? = null,
    initialChapterIndex: Int = 0,
    initialProgress: Double = 0.0,
    readerSettings: ReaderSettings = ReaderSettings(),
    onReaderSettingsChange: (ReaderSettings) -> Unit = {},
    onReaderKeyEventHandlerChange: (((KeyEvent) -> Boolean)?) -> Unit = {},
    onSaveBookmark: (chapterIndex: Int, progress: Double) -> Unit = { _, _ -> },
    onTextSelected: (ReaderSelectionData) -> Int? = { null },
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current
    val appContainer = LocalHoshiAppContainer.current
    val scope = rememberCoroutineScope()
    val fontManager = appContainer.readerFontManager
    val dictionarySettingsRepository = appContainer.dictionarySettingsRepository
    val audioSettingsRepository = appContainer.audioSettingsRepository
    val sasayakiSettingsRepository = appContainer.sasayakiSettingsRepository
    val bookRepository = appContainer.bookRepository
    var sasayakiSettings by remember { mutableStateOf(SasayakiSettings()) }
    val sasayakiMatchData by produceState<SasayakiMatchData?>(initialValue = null, key1 = bookRoot, key2 = bookRepository) {
        value = bookRoot?.let { bookRepository.loadSasayakiMatch(it) }
    }
    var sasayakiPlaybackData by remember(bookRoot) { mutableStateOf<SasayakiPlaybackData?>(null) }
    var isSasayakiPlaybackLoaded by remember(bookRoot) { mutableStateOf(bookRoot == null) }
    LaunchedEffect(bookRoot, bookRepository) {
        isSasayakiPlaybackLoaded = bookRoot == null
        sasayakiPlaybackData = bookRoot?.let { bookRepository.loadSasayakiPlayback(it) }
        isSasayakiPlaybackLoaded = true
    }
    val sasayakiAudioRepository = remember(bookRoot) { bookRoot?.let(::SasayakiAudioRepository) }
    val sasayakiCoverFile = remember(bookRoot, book.coverHref) {
        resolveBookCoverFile(bookRoot, book.coverHref)
    }
    var sasayakiPlayer by remember { mutableStateOf<SasayakiPlayer?>(null) }
    val view = LocalView.current
    val systemDarkTheme = isSystemInDarkTheme()
    val clampedInitialIndex = initialChapterIndex.coerceIn(0, book.chapters.lastIndex)
    val stateHolder = remember(book) {
        ReaderWebViewStateHolder(
            initialSettings = readerSettings,
            initialPosition = ReaderChapterPosition(
                index = clampedInitialIndex,
                progress = initialProgress.coerceIn(0.0, 1.0),
            ),
        )
    }
    LaunchedEffect(readerSettings) {
        stateHolder.syncSettings(readerSettings)
    }
    var dictionarySettings by remember { mutableStateOf(DictionarySettings()) }
    var audioSettings by remember { mutableStateOf(AudioSettings()) }
    LaunchedEffect(dictionarySettingsRepository) {
        dictionarySettingsRepository.settings.collect { settings ->
            dictionarySettings = settings
        }
    }
    LaunchedEffect(audioSettingsRepository) {
        audioSettingsRepository.settings.collect { settings ->
            audioSettings = settings
        }
    }
    LaunchedEffect(sasayakiSettingsRepository) {
        sasayakiSettingsRepository.settings.collect { settings ->
            sasayakiSettings = settings
        }
    }
    val effectiveSettings = stateHolder.effectiveSettings
    val readerPosition = stateHolder.readerPosition
    val lookupPopups = stateHolder.lookupPopups
    val showReaderMenu = stateHolder.showReaderMenu
    val showAppearance = stateHolder.showAppearance
    val showChapters = stateHolder.showChapters
    val showSasayaki = stateHolder.showSasayaki
    val sasayakiWasPausedByLookup = stateHolder.sasayakiWasPausedByLookup
    fun sasayakiCueForSelection(selection: ReaderSelectionData): SasayakiMatch? {
        val player = sasayakiPlayer ?: return null
        val offset = selection.normalizedOffset ?: return null
        if (!sasayakiSettings.enabled || !player.hasAudio) return null
        return player.findCue(chapterIndex = stateHolder.readerPosition.displayedPosition.index, offset = offset)
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
                popupActionBar = effectiveSettings.popupActionBar,
                dictionarySettings = dictionarySettings,
                darkMode = effectiveSettings.usesDarkInterface(systemDarkTheme),
                eInkMode = effectiveSettings.eInkMode,
                audioSettings = audioSettings,
                documentTitle = book.title,
                coverPath = sasayakiCoverFile?.absolutePath,
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
                popupActionBar = effectiveSettings.popupActionBar,
                dictionarySettings = dictionarySettings,
                darkMode = effectiveSettings.usesDarkInterface(systemDarkTheme),
                eInkMode = effectiveSettings.eInkMode,
                audioSettings = audioSettings,
                documentTitle = book.title,
                coverPath = sasayakiCoverFile?.absolutePath,
            ),
        )?.let { (popup, highlightCount) ->
            popup.copy(sasayakiCue = sasayakiCueForSelection(selection)) to highlightCount
        }

    fun closeReader() {
        webView?.flushPendingPageTurnProgress()
        onClose()
    }
    fun clearReaderSelection() {
        webView?.evaluateJavascript(ReaderSelectionCommand.ClearSelection.source, null)
    }
    fun resumeSasayakiAfterLookupIfNeeded() {
        val player = sasayakiPlayer
        if (player != null && !player.isPlaying) {
            player.togglePlayback()
        }
    }
    fun setLookupPopups(nextPopups: List<LookupPopupItem>) {
        stateHolder.setLookupPopups(nextPopups, ::resumeSasayakiAfterLookupIfNeeded)
    }
    fun closeLookupPopupsAndSelection() {
        if (lookupPopups.isNotEmpty()) {
            clearReaderSelection()
        }
        setLookupPopups(emptyList())
    }
    fun updateSasayakiSettings(settings: SasayakiSettings) {
        sasayakiSettings = settings
        scope.launch {
            sasayakiSettingsRepository.update { settings }
        }
        sasayakiPlayer?.autoScroll = settings.autoScroll
    }
    fun goToNextChapter(): Boolean {
        val next = stateHolder.goToNextChapter(book.chapters.lastIndex)
        if (next != null) {
            onSaveBookmark(next.index, next.progress)
            return true
        }
        return false
    }
    fun goToPreviousChapter(): Boolean {
        val previous = stateHolder.goToPreviousChapter()
        if (previous != null) {
            onSaveBookmark(previous.index, previous.progress)
            return true
        }
        return false
    }
    fun saveDisplayedProgress(progress: Double) {
        val savedPosition = stateHolder.recordDisplayedProgress(progress)
        onSaveBookmark(savedPosition.index, savedPosition.progress)
    }
    fun displayPagedTurnProgress(progress: Double) {
        stateHolder.recordDisplayedProgress(progress)
    }
    fun saveContinuousScrollProgress(progress: Double, restoreEpoch: Int) {
        val savedPosition = stateHolder.recordContinuousScrollProgress(progress, restoreEpoch) ?: return
        onSaveBookmark(savedPosition.index, savedPosition.progress)
    }
    fun navigateReaderPage(direction: ReaderNavigationDirection): Boolean {
        val currentWebView = webView ?: return false
        closeLookupPopupsAndSelection()
        val onLimit = when (direction) {
            ReaderNavigationDirection.Forward -> ::goToNextChapter
            ReaderNavigationDirection.Backward -> ::goToPreviousChapter
        }
        currentWebView.navigatePage(direction, onLimit, ::displayPagedTurnProgress, ::saveDisplayedProgress)
        return true
    }
    fun pauseSasayakiForLookupIfNeeded() {
        val player = sasayakiPlayer
        if (stateHolder.shouldPauseSasayakiForLookup(
                enabled = sasayakiSettings.enabled,
                autoPause = sasayakiSettings.autoPause,
                isPlaying = player?.isPlaying == true,
            )
        ) {
            player?.pausePlayback()
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
    LaunchedEffect(bookRoot, sasayakiMatchData, isSasayakiPlaybackLoaded, sasayakiPlaybackData) {
        sasayakiPlayer?.release()
        sasayakiPlayer = if (bookRoot != null && sasayakiMatchData != null && isSasayakiPlaybackLoaded) {
            SasayakiPlayer(
                context = context,
                bookRoot = bookRoot,
                playbackRepository = BookSasayakiPlaybackRepository(bookRoot, bookRepository),
                bookTitle = book.title,
                bookCoverFile = sasayakiCoverFile,
                matchData = sasayakiMatchData,
                initialPlayback = sasayakiPlaybackData,
                persistenceScope = scope,
                getCurrentChapterIndex = { stateHolder.readerPosition.displayedPosition.index },
                onCue = { cue, reveal ->
                    webView?.evaluateJavascript(
                        ReaderPaginationScripts.highlightSasayakiCueInvocation(cue.id, reveal),
                    ) { progressResult ->
                        ReaderPaginationScripts.doubleResult(progressResult)?.let { progress ->
                            val savedPosition = stateHolder.recordDisplayedProgress(progress)
                            onSaveBookmark(savedPosition.index, savedPosition.progress)
                        }
                    }
                },
                onClearCue = {
                    webView?.evaluateJavascript(ReaderPaginationScripts.clearSasayakiCueInvocation(), null)
                },
                onLoadChapter = { chapterIndex ->
                    val target = ReaderChapterPosition(index = chapterIndex, progress = 0.0)
                    val savedPosition = stateHolder.jumpTo(target)
                    onSaveBookmark(savedPosition.index, savedPosition.progress)
                },
            )
        } else {
            null
        }
    }
    DisposableEffect(Unit) {
        onDispose { sasayakiPlayer?.release() }
    }
    sasayakiPlayer?.autoScroll = sasayakiSettings.autoScroll
    val currentReaderKeyHandler = rememberUpdatedState<(KeyEvent) -> Boolean> { event ->
        val direction = readerNavigationDirectionForKeyEvent(
            keyCode = event.keyCode,
            action = event.action,
            repeatCount = event.repeatCount,
            settings = effectiveSettings,
        ) ?: return@rememberUpdatedState false
        navigateReaderPage(direction)
    }
    DisposableEffect(onReaderKeyEventHandlerChange) {
        onReaderKeyEventHandlerChange { event -> currentReaderKeyHandler.value(event) }
        onDispose { onReaderKeyEventHandlerChange(null) }
    }
    val keepScreenOnForSasayaki = SasayakiScreenAwake.shouldKeepScreenOn(
        isPlaying = sasayakiPlayer?.isPlaying == true,
        autoScroll = sasayakiSettings.autoScroll,
    )
    DisposableEffect(context, keepScreenOnForSasayaki) {
        val window = context.findActivity()?.window
        if (keepScreenOnForSasayaki) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(view) {
        val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                webView?.flushPendingPageTurnProgress()
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            webView?.flushPendingPageTurnProgress()
            lifecycle?.removeObserver(observer)
        }
    }

    BackHandler(onBack = ::closeReader)
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
                chapterFragment = readerPosition.loadFragment,
                webViewViewportSize = stateHolder.webViewViewportSize,
                onReaderViewportSizeChanged = stateHolder::updateViewportSize,
                onWebViewReady = { webView = it },
                isWebViewRestoring = stateHolder.isWebViewRestoring,
                webViewRestoreEpoch = stateHolder.webViewRestoreEpoch,
                onRestoreStarted = stateHolder::markWebViewRestoring,
                onRestoreCompleted = stateHolder::markWebViewRestored,
                onNextChapter = {
                    goToNextChapter()
                },
                onPreviousChapter = {
                    goToPreviousChapter()
                },
                onSaveBookmark = { progress ->
                    saveDisplayedProgress(progress)
                },
                onDisplayProgress = { progress ->
                    displayPagedTurnProgress(progress)
                },
                onContinuousScrollProgress = { progress, restoreEpoch ->
                    saveContinuousScrollProgress(progress, restoreEpoch)
                },
                onInternalLink = { target ->
                    closeLookupPopupsAndSelection()
                    val savedPosition = stateHolder.jumpTo(target.position, target.fragment)
                    onSaveBookmark(savedPosition.index, savedPosition.progress)
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
                onSasayakiPauseStateCleared = stateHolder::clearSasayakiPauseState,
                onSasayakiPlayForward = { cue ->
                    sasayakiPlayer?.playCue(cue, stop = false)
                    setLookupPopups(emptyList())
                },
                onPrepareSasayakiAudio = { cue, sentence ->
                    sasayakiPlayer?.exportCueAudio(cue, sentence)?.absolutePath
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
            onClose = ::closeReader,
            onMenu = stateHolder::showReaderMenu,
            menuExpanded = showReaderMenu,
            onDismissMenu = stateHolder::dismissReaderMenu,
            onChapters = stateHolder::openChaptersFromMenu,
            onAppearance = stateHolder::openAppearanceFromMenu,
            onSasayaki = if (sasayakiSettings.enabled && sasayakiMatchData != null) {
                stateHolder::openSasayakiFromMenu
            } else {
                null
            },
            onSasayakiToggle = sasayakiPlayer
                ?.takeIf { sasayakiSettings.enabled && sasayakiSettings.showReaderToggle && it.hasAudio }
                ?.let { player ->
                    ({
                        if (sasayakiWasPausedByLookup) {
                            stateHolder.clearSasayakiPauseState()
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
                stateHolder.applySettings(it)
                onReaderSettingsChange(it)
            },
            fontManager = fontManager,
            onDismiss = stateHolder::dismissAppearance,
        )
    }
    if (showChapters) {
        ReaderChapterSheet(
            book = book,
            currentPosition = readerPosition.displayedPosition,
            onJump = { target ->
                closeLookupPopupsAndSelection()
                val savedPosition = stateHolder.jumpTo(target)
                onSaveBookmark(savedPosition.index, savedPosition.progress)
                stateHolder.dismissChapters()
            },
            onDismiss = stateHolder::dismissChapters,
        )
    }
    if (showSasayaki && sasayakiPlayer != null && sasayakiAudioRepository != null) {
        SasayakiSheet(
            player = requireNotNull(sasayakiPlayer),
            audioRepository = sasayakiAudioRepository,
            settings = sasayakiSettings,
            onSettingsChange = ::updateSasayakiSettings,
            onDismiss = stateHolder::dismissSasayaki,
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
    chapterFragment: String?,
    webViewViewportSize: IntSize,
    onReaderViewportSizeChanged: (IntSize) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    isWebViewRestoring: Boolean,
    webViewRestoreEpoch: Int,
    onRestoreStarted: () -> Unit,
    onRestoreCompleted: () -> Unit,
    onNextChapter: () -> Boolean,
    onPreviousChapter: () -> Boolean,
    onSaveBookmark: (progress: Double) -> Unit,
    onDisplayProgress: (progress: Double) -> Unit,
    onContinuousScrollProgress: (progress: Double, restoreEpoch: Int) -> Unit,
    onInternalLink: (ReaderInternalLinkTarget) -> Unit,
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
    val currentOnSaveBookmark = rememberUpdatedState(onSaveBookmark)
    val currentOnDisplayProgress = rememberUpdatedState(onDisplayProgress)
    val currentOnContinuousScrollProgress = rememberUpdatedState(onContinuousScrollProgress)
    val currentOnClearLookupPopup = rememberUpdatedState(onClearLookupPopup)
    val currentOnNextChapter = rememberUpdatedState(onNextChapter)
    val currentOnPreviousChapter = rememberUpdatedState(onPreviousChapter)
    val currentIsWebViewRestoring = rememberUpdatedState(isWebViewRestoring)
    val currentWebViewRestoreEpoch = rememberUpdatedState(webViewRestoreEpoch)
    val currentOnRestoreStarted = rememberUpdatedState(onRestoreStarted)
    val currentOnRestoreCompleted = rememberUpdatedState(onRestoreCompleted)
    var lastContinuousProgressUpdate by remember { mutableStateOf(0L) }
    val currentOnFragmentRestored = rememberUpdatedState<(WebView) -> Unit> { restoredWebView ->
        if (chapterFragment != null) {
            restoredWebView.evaluateJavascript(ReaderPaginationScripts.progressInvocation()) { progressResult ->
                ReaderPaginationScripts.doubleResult(progressResult)?.let(currentOnSaveBookmark.value)
                currentOnRestoreCompleted.value()
            }
        } else {
            currentOnRestoreCompleted.value()
        }
    }
    val chapter = book.chapters[chapterPosition.index]
    val fontFaceUrl = remember(readerSettings.selectedFont) {
        fontManager.webViewFontUrl(readerSettings.selectedFont)
    }
    val baseUrl = remember(chapter) { "https://hoshi.local/epub/${chapter.href}" }
    val readerSetupScript = remember(
        chapter,
        chapterPosition.progress,
        chapterFragment,
        readerSettings,
        fontFaceUrl,
        systemDark,
        sasayakiCuesJson,
        sasayakiTextColor,
        sasayakiBackgroundColor,
    ) {
        readerSetupScript(
            initialProgress = chapterPosition.progress,
            initialFragment = chapterFragment,
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
                applyHoshiWebViewSecurityDefaults()
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                hideForReaderRestore()
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(
                    ReaderSelectionBridge(this) { selection ->
                        currentOnTextSelected.value(selection)
                    },
                    "HoshiTextSelection",
                )
                addJavascriptInterface(
                    ReaderRestoreBridge(this) { restoredWebView ->
                        currentOnFragmentRestored.value(restoredWebView)
                    },
                    "HoshiReaderRestore",
                )
                webViewClient = EpubWebViewClient(book, fontManager, onInternalLink) { view ->
                    view.evaluateJavascript(readerSetupScript, null)
                }
                onWebViewReady(this)
            }
        },
        update = { webView ->
            fun selectAt(x: Float, y: Float) {
                val density = webView.resources.displayMetrics.density
                webView.evaluateJavascript(
                    ReaderSelectionCommand.SelectText(
                        x = androidPixelsToCssPixels(x, density),
                        y = androidPixelsToCssPixels(y, density),
                        maxLength = MAX_SELECTION_LENGTH,
                    ).source,
                ) { result ->
                    if (ReaderSelectionResult.fromWebViewResult(result).selectedNothing) {
                        currentOnClearLookupPopup.value()
                    }
                }
            }
            if (readerSettings.continuousMode) {
                webView.setOnTouchListener(
                    ContinuousScrollTouchListener(
                        settings = readerSettings,
                        onTap = ::selectAt,
                        onNextChapter = {
                            currentOnClearLookupPopup.value()
                            val changed = currentOnNextChapter.value()
                            if (changed) webView.hideForReaderRestore()
                            changed
                        },
                        onPreviousChapter = {
                            currentOnClearLookupPopup.value()
                            val changed = currentOnPreviousChapter.value()
                            if (changed) webView.hideForReaderRestore()
                            changed
                        },
                    ),
                )
                webView.setOnScrollChangeListener { _, _, _, _, _ ->
                    val now = SystemClock.uptimeMillis()
                    if (now - lastContinuousProgressUpdate < CONTINUOUS_PROGRESS_THROTTLE_MS) return@setOnScrollChangeListener
                    lastContinuousProgressUpdate = now
                    if (currentIsWebViewRestoring.value) return@setOnScrollChangeListener
                    val restoreEpoch = currentWebViewRestoreEpoch.value
                    currentOnClearLookupPopup.value()
                    webView.evaluateJavascript(ReaderPaginationScripts.progressInvocation()) { progressResult ->
                        ReaderPaginationScripts.doubleResult(progressResult)?.let { progress ->
                            currentOnContinuousScrollProgress.value(progress, restoreEpoch)
                        }
                    }
                }
            } else {
                webView.setOnScrollChangeListener(null)
                webView.setOnTouchListener(object : SwipePageTouchListener() {
                    override fun onTap(x: Float, y: Float) {
                        selectAt(x, y)
                    }

                    override fun onLeftSwipe() {
                        currentOnClearLookupPopup.value()
                        val direction = readerNavigationDirectionForSwipe(
                            isVerticalWriting = readerSettings.verticalWriting,
                            swipeDirection = ReaderSwipeDirection.Left,
                        )
                        webView.navigatePageForDirection(
                            direction = direction,
                            onNextChapter = currentOnNextChapter.value,
                            onPreviousChapter = currentOnPreviousChapter.value,
                            onDisplayedProgress = currentOnDisplayProgress.value,
                            onSaveProgress = currentOnSaveBookmark.value,
                        )
                    }

                    override fun onRightSwipe() {
                        currentOnClearLookupPopup.value()
                        val direction = readerNavigationDirectionForSwipe(
                            isVerticalWriting = readerSettings.verticalWriting,
                            swipeDirection = ReaderSwipeDirection.Right,
                        )
                        webView.navigatePageForDirection(
                            direction = direction,
                            onNextChapter = currentOnNextChapter.value,
                            onPreviousChapter = currentOnPreviousChapter.value,
                            onDisplayedProgress = currentOnDisplayProgress.value,
                            onSaveProgress = currentOnSaveBookmark.value,
                        )
                    }
                })
            }
            val loadKey = "$baseUrl#${readerSetupScript.hashCode()}#$webViewViewportSize"
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.hideForReaderRestore()
                currentOnRestoreStarted.value()
                webView.webViewClient = EpubWebViewClient(book, fontManager, onInternalLink) { view ->
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
    private val onInternalLink: (ReaderInternalLinkTarget) -> Unit,
    private val onReaderPageFinished: (WebView) -> Unit,
) : WebViewClient() {
    private val resourceBridge = ReaderWebResourceBridge(book, fontManager)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val target = book.resolveInternalReaderLink(request.url?.toString().orEmpty()) ?: return false
        onInternalLink(target)
        return true
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        if (Uri.parse(url ?: return).host == "hoshi.local") {
            onReaderPageFinished(view)
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        return resourceBridge.resourceForUrl(url)?.toWebResourceResponse()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        view.destroy()
        return true
    }
}

private fun readerSetupScript(
    initialProgress: Double,
    initialFragment: String?,
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
        initialFragment = initialFragment,
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

internal fun File.mediaType(): String = when (extension.lowercase()) {
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    else -> "application/octet-stream"
}

private fun WebView.navigatePage(
    direction: ReaderNavigationDirection,
    onLimit: () -> Boolean,
    onDisplayedProgress: (progress: Double) -> Unit,
    onSaveProgress: (progress: Double) -> Unit,
) {
    evaluateJavascript(ReaderPaginationScripts.paginateInvocation(direction)) { result ->
        if (ReaderPaginationScripts.didScroll(result)) {
            readerPageTurnProgressCallbacks.remove(this)?.let(::removeCallbacks)
            val webView = this
            val requestId = nextReaderPageTurnProgressRequestId()
            readerPageTurnProgressRequestIds[webView] = requestId
            webView.evaluateJavascript(ReaderPaginationScripts.progressInvocation()) { progressResult ->
                if (readerPageTurnProgressRequestIds[webView] != requestId) return@evaluateJavascript
                val progress = ReaderPaginationScripts.doubleResult(progressResult) ?: return@evaluateJavascript
                onDisplayedProgress(progress)
                lateinit var progressCallback: Runnable
                progressCallback = Runnable {
                    if (readerPageTurnProgressRequestIds[webView] != requestId) return@Runnable
                    readerPageTurnProgressRequestIds.remove(webView)
                    if (readerPageTurnProgressCallbacks[webView] == progressCallback) {
                        readerPageTurnProgressCallbacks.remove(webView)
                    }
                    onSaveProgress(progress)
                }
                readerPageTurnProgressCallbacks[webView] = progressCallback
                postDelayed(progressCallback, PAGE_TURN_PROGRESS_SAVE_DELAY_MS)
            }
        } else {
            readerPageTurnProgressCallbacks.remove(this)?.let(::removeCallbacks)
            readerPageTurnProgressRequestIds.remove(this)
            onLimit()
        }
    }
}

private fun nextReaderPageTurnProgressRequestId(): Long {
    readerPageTurnProgressRequestId += 1
    return readerPageTurnProgressRequestId
}

private fun WebView.navigatePageForDirection(
    direction: ReaderNavigationDirection,
    onNextChapter: () -> Boolean,
    onPreviousChapter: () -> Boolean,
    onDisplayedProgress: (progress: Double) -> Unit,
    onSaveProgress: (progress: Double) -> Unit,
) {
    val onLimit = when (direction) {
        ReaderNavigationDirection.Forward -> onNextChapter
        ReaderNavigationDirection.Backward -> onPreviousChapter
    }
    navigatePage(direction, onLimit, onDisplayedProgress, onSaveProgress)
}

private fun WebView.flushPendingPageTurnProgress() {
    val progressCallback = readerPageTurnProgressCallbacks.remove(this) ?: return
    removeCallbacks(progressCallback)
    progressCallback.run()
}

private class ContinuousScrollTouchListener(
    private val settings: ReaderSettings,
    private val onTap: (Float, Float) -> Unit,
    private val onNextChapter: () -> Boolean,
    private val onPreviousChapter: () -> Boolean,
) : View.OnTouchListener {
    private var downX = 0f
    private var downY = 0f

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val webView = view as? WebView ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) < TAP_SLOP && abs(dy) < TAP_SLOP) {
                    onTap(event.x, event.y)
                    return false
                }
                handleBoundarySwipe(webView, dx, dy)
            }
        }
        return false
    }

    private fun handleBoundarySwipe(webView: WebView, dx: Float, dy: Float) {
        val threshold = settings.chapterSwipeDistance * webView.resources.displayMetrics.density
        if (settings.verticalWriting) {
            if (abs(dx) < threshold || abs(dx) < abs(dy)) return
            when {
                dx > 0 && !webView.canScrollHorizontally(-1) -> onNextChapter()
                dx < 0 && !webView.canScrollHorizontally(1) -> onPreviousChapter()
            }
        } else {
            if (abs(dy) < threshold || abs(dy) < abs(dx)) return
            when {
                dy < 0 && !webView.canScrollVertically(1) -> onNextChapter()
                dy > 0 && !webView.canScrollVertically(-1) -> onPreviousChapter()
            }
        }
    }

    private companion object {
        const val TAP_SLOP = 12f
    }
}

private class ReaderRestoreBridge(
    private val webView: WebView,
    private val onRestoreCompleted: (WebView) -> Unit,
) {
    @JavascriptInterface
    fun postMessage(@Suppress("UNUSED_PARAMETER") message: String) {
        webView.post {
            onRestoreCompleted(webView)
            webView.showAfterReaderRestore()
        }
    }
}

private fun WebView.hideForReaderRestore() {
    animate().cancel()
    readerRestoreGenerations[this] = (readerRestoreGenerations[this] ?: 0L) + 1L
    alpha = 0f
}

private fun WebView.showAfterReaderRestore() {
    animate().cancel()
    val generation = readerRestoreGenerations[this] ?: 0L
    postVisualStateCallback(
        generation,
        object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                post {
                    if (readerRestoreGenerations[this@showAfterReaderRestore] == generation) {
                        animate().cancel()
                        alpha = 1f
                    }
                }
            }
        },
    )
}

private val readerRestoreGenerations = WeakHashMap<WebView, Long>()
private val readerPageTurnProgressCallbacks = WeakHashMap<WebView, Runnable>()
private val readerPageTurnProgressRequestIds = WeakHashMap<WebView, Long>()
private var readerPageTurnProgressRequestId = 0L
private const val MAX_SELECTION_LENGTH = 16
private const val CONTINUOUS_PROGRESS_THROTTLE_MS = 250L
private const val PAGE_TURN_PROGRESS_SAVE_DELAY_MS = 1_000L
private val ReaderWebViewTopPadding = 44.dp
private val ReaderWebViewBottomPadding = 56.dp

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun resolveBookCoverFile(bookRoot: File?, coverHref: String?): File? {
    val root = bookRoot?.canonicalFile ?: return null
    val cover = coverHref?.takeIf { it.isNotBlank() } ?: return null
    val file = root.resolve(cover).canonicalFile
    if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) return null
    return file.takeIf { it.isFile }
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
