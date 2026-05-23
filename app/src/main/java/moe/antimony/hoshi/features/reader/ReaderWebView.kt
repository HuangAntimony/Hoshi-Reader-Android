package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.HighlightColor
import moe.antimony.hoshi.epub.ReaderHighlight

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.WindowManager
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Timer
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import java.util.WeakHashMap
import java.util.UUID
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.dictionary.LookupPopupAndroidOverlay
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupOptions
import moe.antimony.hoshi.features.dictionary.LookupPopupState
import moe.antimony.hoshi.features.dictionary.clearPopupSelectionHighlights
import moe.antimony.hoshi.features.dictionary.createLookupPopupItem
import moe.antimony.hoshi.features.dictionary.withLookupPopupVisualOptions
import moe.antimony.hoshi.features.sasayaki.BookSasayakiPlaybackRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiAudioRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRange
import moe.antimony.hoshi.features.sasayaki.SasayakiPlayer
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.features.sasayaki.SasayakiSheet
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults
import kotlin.math.abs

private data class PendingRootSelectionHighlight(
    val popupId: String,
    val rects: List<ReaderSelectionRect>? = null,
    val contentReady: Boolean = false,
)

private data class ReaderFullscreenImage(
    val sourceUrl: String,
    val resource: ReaderWebResource,
)

private fun popupAnchorRect(rects: List<ReaderSelectionRect>): ReaderSelectionRect? =
    rects.firstOrNull()

private fun warmRootLookupPopupItem(
    settings: ReaderSettings,
    dictionarySettings: DictionarySettings,
    dictionaryStyles: Map<String, String>,
    darkMode: Boolean,
    audioSettings: AudioSettings,
): LookupPopupItem = LookupPopupItem(
    id = "warm-root-popup",
    state = LookupPopupState(
        selection = ReaderSelectionData(
            text = "",
            sentence = "",
            rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0),
            normalizedOffset = null,
        ),
        results = emptyList(),
        dictionaryStyles = dictionaryStyles,
        dictionarySettings = dictionarySettings,
        isVertical = settings.verticalWriting,
        isFullWidth = settings.popupFullWidth,
        width = settings.popupWidth,
        height = settings.popupHeight,
        swipeToDismiss = settings.popupSwipeToDismiss,
        swipeThreshold = settings.popupSwipeThreshold,
        reducedMotionScrolling = settings.popupReducedMotionScrolling,
        reducedMotionScrollPercent = settings.popupReducedMotionScrollPercent,
        reducedMotionSwipeThreshold = settings.popupReducedMotionSwipeThreshold,
        popupScale = settings.popupScale,
        darkMode = darkMode,
        eInkMode = settings.eInkMode,
        audioSettings = audioSettings,
        popupActionBar = false,
    ),
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
    onReaderKeyEventHandlerChange: (((KeyEvent) -> Boolean)?) -> Unit = {},
    onSaveBookmark: (chapterIndex: Int, progress: Double, statistics: List<ReadingStatistics>?) -> Unit = { _, _, _ -> },
    onFlushAutoSyncExport: () -> Unit = {},
    onForegroundAutoSyncImport: () -> Unit = {},
    onTextSelected: (ReaderSelectionData) -> Int? = { null },
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current
    val appContainer = LocalHoshiAppContainer.current
    val scope = rememberCoroutineScope()
    val fontManager = appContainer.readerFontManager
    val readerImageResourceBridge = remember(book, fontManager) {
        ReaderWebResourceBridge(book, fontManager)
    }
    val dictionaryRepository = appContainer.dictionaryRepository
    val dictionarySettingsRepository = appContainer.dictionarySettingsRepository
    val audioSettingsRepository = appContainer.audioSettingsRepository
    val sasayakiSettingsRepository = appContainer.sasayakiSettingsRepository
    val bookRepository = appContainer.bookRepository
    var sasayakiSettings by remember { mutableStateOf(SasayakiSettings()) }
    var sasayakiMatchData by remember(bookRoot) { mutableStateOf<SasayakiMatchData?>(null) }
    LaunchedEffect(bookRoot, bookRepository) {
        sasayakiMatchData = bookRoot?.let { bookRepository.loadSasayakiMatch(it) }
    }
    var highlights by remember(bookRoot) {
        mutableStateOf<List<ReaderHighlight>?>(if (bookRoot == null) emptyList() else null)
    }
    LaunchedEffect(bookRoot, bookRepository) {
        highlights = if (bookRoot != null) {
            bookRepository.loadHighlights(bookRoot)
        } else {
            emptyList()
        }
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
    var dictionaryStyles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(dictionarySettingsRepository) {
        dictionarySettingsRepository.settings.collect { settings ->
            dictionarySettings = settings
        }
    }
    LaunchedEffect(dictionaryRepository) {
        dictionaryStyles = withContext(Dispatchers.IO) {
            runCatching {
                dictionaryRepository.ensureLookupQueryReady()
                dictionaryRepository.dictionaryStyles()
            }.getOrDefault(emptyMap())
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
    var pendingRootSelectionHighlight by remember { mutableStateOf<PendingRootSelectionHighlight?>(null) }
    var visibleLookupPopupIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var rootSelectionHighlightRects by remember { mutableStateOf<List<ReaderSelectionRect>>(emptyList()) }
    var warmRootLookupPopup by remember { mutableStateOf<LookupPopupItem?>(null) }
    var fullscreenImage by remember { mutableStateOf<ReaderFullscreenImage?>(null) }
    val popupDarkMode = effectiveSettings.usesDarkInterface(systemDarkTheme)
    val themedLookupPopups = remember(
        lookupPopups,
        popupDarkMode,
        effectiveSettings.eInkMode,
        audioSettings,
        effectiveSettings.popupScale,
    ) {
        lookupPopups.withLookupPopupVisualOptions(
            darkMode = popupDarkMode,
            eInkMode = effectiveSettings.eInkMode,
            audioSettings = audioSettings,
            popupScale = effectiveSettings.popupScale,
        )
    }
    val warmRootSeedPopup = remember(effectiveSettings, dictionarySettings, dictionaryStyles, popupDarkMode, audioSettings) {
        warmRootLookupPopupItem(
            settings = effectiveSettings,
            dictionarySettings = dictionarySettings,
            dictionaryStyles = dictionaryStyles,
            darkMode = popupDarkMode,
            audioSettings = audioSettings,
        )
    }
    val warmRootSourcePopup = warmRootLookupPopup ?: warmRootSeedPopup
    val warmRootPopup = listOf(
        warmRootSourcePopup.copy(
            state = warmRootSourcePopup.state.copy(
                results = emptyList(),
                popupActionBar = false,
            ),
            sasayakiCue = null,
        ),
    ).withLookupPopupVisualOptions(
        darkMode = popupDarkMode,
        eInkMode = effectiveSettings.eInkMode,
        audioSettings = audioSettings,
        popupScale = effectiveSettings.popupScale,
    ).first()
    val showReaderMenu = stateHolder.showReaderMenu
    val showAppearance = stateHolder.showAppearance
    val showChapters = stateHolder.showChapters
    val showHighlights = stateHolder.showHighlights
    val showSasayaki = stateHolder.showSasayaki
    val showStatistics = stateHolder.showStatistics
    val focusMode = stateHolder.focusMode
    val sasayakiWasPausedByLookup = stateHolder.sasayakiWasPausedByLookup
    var persistedStatistics by remember(bookRoot) {
        mutableStateOf<List<ReadingStatistics>?>(if (bookRoot == null) emptyList() else null)
    }
    LaunchedEffect(bookRoot, bookRepository, effectiveSettings.enableStatistics) {
        persistedStatistics = if (bookRoot != null && effectiveSettings.enableStatistics) {
            bookRepository.loadStatistics(bookRoot)
        } else {
            emptyList()
        }
    }
    val statisticsTracker = remember(bookRoot, book.title, effectiveSettings.enableStatistics, persistedStatistics) {
        persistedStatistics?.let { statistics ->
            ReaderStatisticsTracker(
                title = book.title,
                initialStatistics = statistics,
                enabled = effectiveSettings.enableStatistics,
            )
        }
    }
    var statisticsState by remember(statisticsTracker) { mutableStateOf(statisticsTracker?.state) }
    var resumeStatisticsTrackingOnStart by remember(statisticsTracker) { mutableStateOf(false) }
    fun currentDisplayedCharacter(): Int =
        book.characterCountAt(
            stateHolder.readerPosition.displayedPosition.index,
            stateHolder.readerPosition.displayedPosition.progress,
        )
    fun currentChapterEndCharacter(): Int {
        val index = stateHolder.readerPosition.displayedPosition.index
        return if (index < book.chapters.lastIndex) {
            book.characterCountAt(index + 1, 0.0)
        } else {
            book.bookInfo.characterCount
        }
    }
    fun syncStatisticsState() {
        statisticsState = statisticsTracker?.state
    }
    fun startStatisticsForProgressChangeIfNeeded() {
        if (effectiveSettings.statisticsAutostartMode == StatisticsAutostartMode.PageTurn) {
            statisticsTracker?.startForPageTurnIfNeeded(currentDisplayedCharacter())
            syncStatisticsState()
        }
    }
    fun recordStatisticsAtDisplayedPosition() {
        statisticsTracker?.update(currentDisplayedCharacter())
        syncStatisticsState()
    }
    fun resetStatisticsBaseline() {
        statisticsTracker?.resetBaseline(currentDisplayedCharacter())
        syncStatisticsState()
    }
    fun statisticsForSave(): List<ReadingStatistics>? {
        recordStatisticsAtDisplayedPosition()
        return statisticsTracker?.statisticsForPersistenceOrNull()
    }
    fun saveReaderPosition(position: ReaderChapterPosition, statistics: List<ReadingStatistics>? = statisticsForSave()) {
        onSaveBookmark(position.index, position.progress, statistics)
    }
    fun saveCurrentDisplayedPosition() {
        saveReaderPosition(stateHolder.readerPosition.displayedPosition)
    }
    fun jumpToPositionWithHistory(position: ReaderChapterPosition, fragment: String? = null) {
        val statistics = statisticsForSave()
        val savedPosition = stateHolder.jumpToWithHistory(position, fragment)
        resetStatisticsBaseline()
        saveReaderPosition(savedPosition, statistics)
    }
    fun navigateJumpBack() {
        val statistics = statisticsForSave()
        val savedPosition = stateHolder.navigateBackInJumpHistory() ?: return
        resetStatisticsBaseline()
        saveReaderPosition(savedPosition, statistics)
    }
    fun navigateJumpForward() {
        val statistics = statisticsForSave()
        val savedPosition = stateHolder.navigateForwardInJumpHistory() ?: return
        resetStatisticsBaseline()
        saveReaderPosition(savedPosition, statistics)
    }
    fun currentLoadChapter(): moe.antimony.hoshi.epub.EpubChapter =
        book.chapters[stateHolder.readerPosition.loadPosition.index.coerceIn(0, book.chapters.lastIndex)]
    fun persistHighlights(nextHighlights: List<ReaderHighlight>) {
        highlights = nextHighlights
        val root = bookRoot ?: return
        scope.launch {
            bookRepository.saveHighlights(root, nextHighlights)
        }
    }
    fun addHighlight(color: HighlightColor, id: String, creation: ReaderHighlightCreationResult) {
        val chapter = currentLoadChapter()
        val info = book.bookInfo.chapterInfo[chapter.href] ?: return
        val highlight = ReaderHighlight(
            id = id,
            character = info.currentTotal + creation.start,
            offset = creation.offset,
            text = creation.text,
            color = color,
            createdAt = bookRepository.currentAppleReferenceDateSeconds(),
        )
        persistHighlights(highlights.orEmpty() + highlight)
    }
    fun removeHighlight(highlight: ReaderHighlight) {
        persistHighlights(highlights.orEmpty().filterNot { it.id == highlight.id })
        if (ReaderHighlights.chapterContains(highlight, book.bookInfo, currentLoadChapter())) {
            webView?.evaluateJavascript(ReaderHighlightCommand.Remove(highlight.id).source, null)
        }
    }
    fun toggleStatisticsTracking() {
        val tracker = statisticsTracker ?: return
        if (tracker.state.isTracking) {
            tracker.stop(currentDisplayedCharacter())
            syncStatisticsState()
            saveCurrentDisplayedPosition()
        } else {
            tracker.start(currentDisplayedCharacter())
            syncStatisticsState()
        }
    }
    fun pauseStatisticsForLifecycleStop(): Boolean {
        val tracker = statisticsTracker ?: return false
        val paused = tracker.pause(currentDisplayedCharacter())
        if (paused) {
            syncStatisticsState()
        }
        return paused
    }
    fun resumeStatisticsForLifecycleStartIfNeeded() {
        if (!resumeStatisticsTrackingOnStart) return
        resumeStatisticsTrackingOnStart = false
        statisticsTracker?.start(currentDisplayedCharacter())
        syncStatisticsState()
    }
    LaunchedEffect(statisticsTracker, effectiveSettings.statisticsAutostartMode) {
        if (effectiveSettings.enableStatistics && effectiveSettings.statisticsAutostartMode == StatisticsAutostartMode.On) {
            statisticsTracker?.start(currentDisplayedCharacter())
            syncStatisticsState()
        }
    }
    LaunchedEffect(statisticsTracker, statisticsState?.isTracking) {
        val tracker = statisticsTracker ?: return@LaunchedEffect
        if (tracker.state.isTracking) {
            while (tracker.state.isTracking) {
                delay(1_000)
                tracker.update(currentDisplayedCharacter())
                syncStatisticsState()
            }
        }
    }
    fun sasayakiCueForSelection(selection: ReaderSelectionData): SasayakiMatch? {
        val player = sasayakiPlayer ?: return null
        val offset = selection.normalizedOffset ?: return null
        if (!sasayakiSettings.enabled || !player.hasAudio) return null
        return player.findCue(chapterIndex = stateHolder.readerPosition.displayedPosition.index, offset = offset)
    }
    fun lookupRootPopup(selection: ReaderSelectionData): Pair<LookupPopupItem, Int>? =
        createLookupPopupItem(
            selection = selection,
            dictionaryStyles = dictionaryStyles,
            lookup = dictionaryRepository::lookup,
            options = LookupPopupOptions(
                isVertical = effectiveSettings.verticalWriting,
                isFullWidth = effectiveSettings.popupFullWidth,
                width = effectiveSettings.popupWidth,
                height = effectiveSettings.popupHeight,
                swipeToDismiss = effectiveSettings.popupSwipeToDismiss,
                swipeThreshold = effectiveSettings.popupSwipeThreshold,
                reducedMotionScrolling = effectiveSettings.popupReducedMotionScrolling,
                reducedMotionScrollPercent = effectiveSettings.popupReducedMotionScrollPercent,
                reducedMotionSwipeThreshold = effectiveSettings.popupReducedMotionSwipeThreshold,
                popupScale = effectiveSettings.popupScale,
                popupActionBar = effectiveSettings.popupActionBar,
                dictionarySettings = dictionarySettings,
                darkMode = popupDarkMode,
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
            dictionaryStyles = dictionaryStyles,
            lookup = dictionaryRepository::lookup,
            options = LookupPopupOptions(
                isVertical = false,
                isFullWidth = false,
                width = effectiveSettings.popupWidth,
                height = effectiveSettings.popupHeight,
                swipeToDismiss = effectiveSettings.popupSwipeToDismiss,
                swipeThreshold = effectiveSettings.popupSwipeThreshold,
                reducedMotionScrolling = effectiveSettings.popupReducedMotionScrolling,
                reducedMotionScrollPercent = effectiveSettings.popupReducedMotionScrollPercent,
                reducedMotionSwipeThreshold = effectiveSettings.popupReducedMotionSwipeThreshold,
                popupScale = effectiveSettings.popupScale,
                popupActionBar = effectiveSettings.popupActionBar,
                dictionarySettings = dictionarySettings,
                darkMode = popupDarkMode,
                eInkMode = effectiveSettings.eInkMode,
                audioSettings = audioSettings,
                documentTitle = book.title,
                coverPath = sasayakiCoverFile?.absolutePath,
            ),
        )?.let { (popup, highlightCount) ->
            popup.copy(sasayakiCue = sasayakiCueForSelection(selection)) to highlightCount
        }

    fun closeReader() {
        val plan = readerLifecycleAutoSyncPlan(ReaderLifecycleAutoSyncEvent.Dispose)
        if (plan.flushPendingProgressSave) {
            webView?.flushPendingProgressSave()
        }
        if (plan.saveCurrentDisplayedPosition) {
            saveCurrentDisplayedPosition()
        }
        if (plan.flushAutoSyncExport) {
            onFlushAutoSyncExport()
        }
        onClose()
    }
    fun clearReaderSelection(onCleared: () -> Unit = {}) {
        val currentWebView = webView
        if (currentWebView == null) {
            onCleared()
        } else {
            currentWebView.evaluateJavascript(ReaderSelectionCommand.ClearSelection.source) {
                onCleared()
            }
        }
    }
    fun resumeSasayakiAfterLookupIfNeeded() {
        val player = sasayakiPlayer
        if (player != null && !player.isPlaying) {
            player.togglePlayback()
        }
    }
    fun setLookupPopups(nextPopups: List<LookupPopupItem>) {
        nextPopups.firstOrNull()?.let { warmRootLookupPopup = it }
        visibleLookupPopupIds = visibleLookupPopupIds.intersect(nextPopups.mapTo(mutableSetOf()) { it.id })
        stateHolder.setLookupPopups(nextPopups, ::resumeSasayakiAfterLookupIfNeeded)
    }
    fun dismissRootLookupPopup() {
        pendingRootSelectionHighlight = null
        visibleLookupPopupIds = emptySet()
        rootSelectionHighlightRects = emptyList()
        setLookupPopups(clearPopupSelectionHighlights(stateHolder.lookupPopups))
        clearReaderSelection()
        setLookupPopups(emptyList())
    }
    fun closeLookupPopupsAndSelection() {
        pendingRootSelectionHighlight = null
        visibleLookupPopupIds = emptySet()
        rootSelectionHighlightRects = emptyList()
        if (lookupPopups.isNotEmpty()) {
            setLookupPopups(clearPopupSelectionHighlights(stateHolder.lookupPopups))
            clearReaderSelection()
            setLookupPopups(emptyList())
        } else {
            setLookupPopups(emptyList())
        }
    }
    fun openFullscreenImage(sourceUrl: String) {
        val resource = readerImageResourceBridge.imageResourceForUrl(sourceUrl) ?: return
        closeLookupPopupsAndSelection()
        fullscreenImage = ReaderFullscreenImage(sourceUrl, resource)
    }
    fun updateRootPopupSelectionBounds(popupId: String, rects: List<ReaderSelectionRect>) {
        popupAnchorRect(rects)?.let { anchor ->
            setLookupPopups(
                stateHolder.lookupPopups.map { popup ->
                    if (popup.id == popupId) {
                        popup.copy(
                            state = popup.state.copy(
                                selection = popup.state.selection.copy(rect = anchor),
                            ),
                        )
                    } else {
                        popup
                    }
                },
            )
        }
    }
    fun revealPendingRootSelectionHighlight(popupId: String) {
        val pending = pendingRootSelectionHighlight ?: return
        if (pending.popupId != popupId) return
        val rects = pending.rects ?: return
        if (!pending.contentReady) return
        rootSelectionHighlightRects = rects
        visibleLookupPopupIds = visibleLookupPopupIds + popupId
        pendingRootSelectionHighlight = null
    }
    fun setPendingRootSelectionRects(popupId: String, rects: List<ReaderSelectionRect>) {
        val pending = pendingRootSelectionHighlight ?: return
        if (pending.popupId != popupId) return
        val fallbackRect = stateHolder.lookupPopups.firstOrNull { it.id == popupId }?.state?.selection?.rect
        val displayRects = rects.ifEmpty { fallbackRect?.let(::listOf).orEmpty() }
        updateRootPopupSelectionBounds(popupId, displayRects)
        pendingRootSelectionHighlight = pending.copy(rects = displayRects)
        revealPendingRootSelectionHighlight(popupId)
    }
    fun markRootPopupContentReady(popupId: String) {
        val pending = pendingRootSelectionHighlight ?: return
        if (pending.popupId != popupId) return
        pendingRootSelectionHighlight = pending.copy(contentReady = true)
        revealPendingRootSelectionHighlight(popupId)
    }
    fun updateSasayakiSettings(settings: SasayakiSettings) {
        sasayakiSettings = settings
        scope.launch {
            sasayakiSettingsRepository.update { settings }
        }
        sasayakiPlayer?.autoScroll = settings.autoScroll
        sasayakiPlayer?.readerSkipButtonAction = settings.readerSkipButtonAction
    }
    fun goToNextChapter(): Boolean {
        if (!stateHolder.canAcceptReaderNavigationInput()) return false
        startStatisticsForProgressChangeIfNeeded()
        val next = stateHolder.goToNextChapter(book.chapters.lastIndex)
        if (next != null) {
            stateHolder.clearForwardHistoryAfterManualMovement()
            recordStatisticsAtDisplayedPosition()
            saveReaderPosition(next)
            return true
        }
        return false
    }
    fun goToPreviousChapter(): Boolean {
        if (!stateHolder.canAcceptReaderNavigationInput()) return false
        startStatisticsForProgressChangeIfNeeded()
        val previous = stateHolder.goToPreviousChapter()
        if (previous != null) {
            stateHolder.clearForwardHistoryAfterManualMovement()
            recordStatisticsAtDisplayedPosition()
            saveReaderPosition(previous)
            return true
        }
        return false
    }
    fun saveDisplayedProgress(progress: Double) {
        stateHolder.enterFocusModeForReaderInteraction()
        startStatisticsForProgressChangeIfNeeded()
        val savedPosition = stateHolder.recordDisplayedProgress(progress)
        stateHolder.clearForwardHistoryAfterManualMovement()
        recordStatisticsAtDisplayedPosition()
        saveReaderPosition(savedPosition)
    }
    fun displayPagedTurnProgress(progress: Double) {
        stateHolder.enterFocusModeForReaderInteraction()
        startStatisticsForProgressChangeIfNeeded()
        stateHolder.recordDisplayedProgress(progress)
        stateHolder.clearForwardHistoryAfterManualMovement()
        recordStatisticsAtDisplayedPosition()
    }
    fun displayContinuousScrollProgress(progress: Double, restoreEpoch: Int) {
        stateHolder.enterFocusModeForReaderInteraction()
        startStatisticsForProgressChangeIfNeeded()
        stateHolder.recordContinuousScrollDisplayProgress(progress, restoreEpoch) ?: return
        stateHolder.clearForwardHistoryAfterManualMovement()
        recordStatisticsAtDisplayedPosition()
    }
    fun saveContinuousScrollProgress(progress: Double, restoreEpoch: Int) {
        stateHolder.enterFocusModeForReaderInteraction()
        startStatisticsForProgressChangeIfNeeded()
        val savedPosition = stateHolder.recordContinuousScrollProgress(progress, restoreEpoch) ?: return
        stateHolder.clearForwardHistoryAfterManualMovement()
        recordStatisticsAtDisplayedPosition()
        saveReaderPosition(savedPosition)
    }
    fun navigateReaderPage(direction: ReaderNavigationDirection): Boolean {
        if (!stateHolder.canAcceptReaderNavigationInput()) return false
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
    val handleTextSelected: (ReaderSelectionData, (Int, (List<ReaderSelectionRect>) -> Unit) -> Unit) -> Unit = { selection, selectionRects ->
        stateHolder.enterFocusModeForReaderInteraction()
        pendingRootSelectionHighlight = null
        visibleLookupPopupIds = emptySet()
        rootSelectionHighlightRects = emptyList()
        setLookupPopups(emptyList())
        val lookup = lookupRootPopup(selection)
        if (lookup != null) {
            val (popup, highlightCount) = lookup
            pauseSasayakiForLookupIfNeeded()
            val selectionCount = onTextSelected(selection) ?: highlightCount
            pendingRootSelectionHighlight = PendingRootSelectionHighlight(
                popupId = popup.id,
            )
            setLookupPopups(listOf(popup))
            selectionRects(selectionCount) { rects ->
                setPendingRootSelectionRects(popup.id, rects)
            }
        } else {
            pendingRootSelectionHighlight = null
            onTextSelected(selection)?.let { selectionRects(it) { rootSelectionHighlightRects = it } }
        }
    }
    fun handleReaderTapOutside() {
        if (!stateHolder.toggleFocusModeFromReaderTap(hasVisiblePopups = stateHolder.lookupPopups.isNotEmpty())) {
            closeLookupPopupsAndSelection()
        }
    }
    val chromeState = remember(
        book,
        readerPosition.displayedPosition,
        stateHolder.backTargetPosition,
        stateHolder.forwardTargetPosition,
        statisticsState,
    ) {
        ReaderChromeState(
            title = book.title,
            currentCharacter = book.characterCountAt(readerPosition.displayedPosition.index, readerPosition.displayedPosition.progress),
            totalCharacters = book.bookInfo.characterCount,
            backTargetCharacter = stateHolder.backTargetPosition?.let { book.characterCountAt(it.index, it.progress) },
            forwardTargetCharacter = stateHolder.forwardTargetPosition?.let { book.characterCountAt(it.index, it.progress) },
            statistics = statisticsState?.session?.let {
                ReaderStatisticsChromeState(
                    readingSpeed = it.lastReadingSpeed,
                    readingTimeSeconds = it.readingTime,
                )
            },
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
                        ReaderPaginationScripts.highlightSasayakiCueInvocation(cue.toCueRange(), reveal),
                    ) { progressResult ->
                        ReaderPaginationScripts.doubleResult(progressResult)?.let { progress ->
                            startStatisticsForProgressChangeIfNeeded()
                            val savedPosition = stateHolder.recordDisplayedProgress(progress)
                            recordStatisticsAtDisplayedPosition()
                            saveReaderPosition(savedPosition)
                        }
                    }
                },
                onClearCue = {
                    webView?.evaluateJavascript(ReaderPaginationScripts.clearSasayakiCueInvocation(), null)
                },
                onLoadChapter = { chapterIndex ->
                    statisticsForSave()
                    val target = ReaderChapterPosition(index = chapterIndex, progress = 0.0)
                    val savedPosition = stateHolder.jumpTo(target)
                    resetStatisticsBaseline()
                    saveReaderPosition(savedPosition, statisticsTracker?.statisticsForPersistenceOrNull())
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
    sasayakiPlayer?.readerSkipButtonAction = sasayakiSettings.readerSkipButtonAction
    val currentReaderKeyHandler = rememberUpdatedState<(KeyEvent) -> Boolean> { event ->
        val action = readerHardwareKeyActionForKeyEvent(
            keyCode = event.keyCode,
            action = event.action,
            repeatCount = event.repeatCount,
            settings = effectiveSettings,
            sasayakiEnabled = sasayakiSettings.enabled,
            hasSasayakiAudio = sasayakiPlayer?.hasAudio == true,
        ) ?: return@rememberUpdatedState false
        when (action) {
            is ReaderHardwareKeyAction.ReaderNavigation -> navigateReaderPage(action.direction)
            ReaderHardwareKeyAction.SasayakiSeekBackward -> {
                sasayakiPlayer?.previousCue()
                true
            }
            ReaderHardwareKeyAction.SasayakiSeekForward -> {
                sasayakiPlayer?.nextCue()
                true
            }
        }
    }
    DisposableEffect(onReaderKeyEventHandlerChange) {
        onReaderKeyEventHandlerChange { event -> currentReaderKeyHandler.value(event) }
        onDispose { onReaderKeyEventHandlerChange(null) }
    }
    val keepScreenOn = ReaderScreenAwake.shouldKeepScreenOn(
        keepScreenOnWhileReading = effectiveSettings.keepScreenOnWhileReading,
        sasayakiIsPlaying = sasayakiPlayer?.isPlaying == true,
        sasayakiAutoScroll = sasayakiSettings.autoScroll,
    )
    DisposableEffect(context, keepScreenOn) {
        val window = context.findActivity()?.window
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var lastInactiveAtMillis by remember { mutableStateOf<Long?>(null) }
    val currentLifecycleResume = rememberUpdatedState {
        val inactiveAt = lastInactiveAtMillis
        lastInactiveAtMillis = null
        val plan = readerLifecycleAutoSyncPlan(
            event = ReaderLifecycleAutoSyncEvent.Resume,
            inactiveElapsedMillis = inactiveAt?.let { SystemClock.elapsedRealtime() - it },
        )
        if (plan.importOnForeground) {
            onForegroundAutoSyncImport()
        }
        resumeStatisticsForLifecycleStartIfNeeded()
    }
    val currentLifecyclePause = rememberUpdatedState {
        lastInactiveAtMillis = SystemClock.elapsedRealtime()
        val plan = readerLifecycleAutoSyncPlan(ReaderLifecycleAutoSyncEvent.Pause)
        if (plan.flushPendingProgressSave) {
            webView?.flushPendingProgressSave()
        }
        resumeStatisticsTrackingOnStart = pauseStatisticsForLifecycleStop()
        if (plan.saveCurrentDisplayedPosition) {
            saveCurrentDisplayedPosition()
        }
        if (plan.flushAutoSyncExport) {
            onFlushAutoSyncExport()
        }
    }
    val currentLifecycleDispose = rememberUpdatedState {
        val plan = readerLifecycleAutoSyncPlan(ReaderLifecycleAutoSyncEvent.Dispose)
        if (plan.flushPendingProgressSave) {
            webView?.flushPendingProgressSave()
        }
        if (plan.saveCurrentDisplayedPosition) {
            saveCurrentDisplayedPosition()
        }
        if (plan.flushAutoSyncExport) {
            onFlushAutoSyncExport()
        }
    }
    val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> currentLifecycleResume.value()
                Lifecycle.Event.ON_PAUSE -> currentLifecyclePause.value()
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            currentLifecycleDispose.value()
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
    DisposableEffect(context, view) {
        val activity = context.findActivity()
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        }
        val previousBehavior = controller?.systemBarsBehavior
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            if (previousBehavior != null) {
                controller.systemBarsBehavior = previousBehavior
            }
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val bottomChromeMetrics = readerBottomChromeMetrics()
    val stableStatusBarPadding = rememberStableStatusBarPadding()
    val stableNavigationBarPadding = rememberStableNavigationBarPadding()
    val sasayakiBottomSkipButtons = readerSasayakiBottomSkipButtons(
        settings = sasayakiSettings,
        hasAudio = sasayakiPlayer?.hasAudio == true,
        metrics = bottomChromeMetrics,
    )
    val sasayakiBottomSkipButtonActions = readerSasayakiBottomSkipButtonActions(
        verticalWriting = effectiveSettings.verticalWriting,
        reverseVerticalReaderSkipButtons = sasayakiSettings.reverseVerticalReaderSkipButtons,
    )
    fun performSasayakiBottomSkipAction(action: ReaderSasayakiBottomSkipButtonAction) {
        when (action) {
            ReaderSasayakiBottomSkipButtonAction.Backward -> sasayakiPlayer?.previousCue()
            ReaderSasayakiBottomSkipButtonAction.Forward -> sasayakiPlayer?.nextCue()
        }
    }
    val showSasayakiTopToggle = sasayakiSettings.enabled &&
        sasayakiSettings.showReaderToggle &&
        sasayakiMatchData != null &&
        (sasayakiPlayer?.hasAudio == true || sasayakiPlaybackData.hasStoredAudioSource())
    val reserveSasayakiTopToggle = remember(bookRoot, sasayakiSettings) {
        readerShouldReserveSasayakiTopToggle(bookRoot, sasayakiSettings)
    }
    val onSasayakiTopToggle = sasayakiPlayer
        ?.takeIf { showSasayakiTopToggle && it.hasAudio }
        ?.let { player ->
            ({
                if (sasayakiWasPausedByLookup) {
                    stateHolder.clearSasayakiPauseState()
                } else {
                    player.togglePlayback()
                }
            })
        }
    val chromeLayout = readerChromeLayout(
        chromeState,
        effectiveSettings,
        showSasayakiToggle = reserveSasayakiTopToggle || showSasayakiTopToggle,
        showStatisticsToggle = effectiveSettings.enableStatistics && effectiveSettings.showStatisticsToggle,
        focusMode = focusMode,
    )
    val chromeVisibility = readerChromeVisibility(
        focusMode = focusMode,
        hasStatisticsToggle = effectiveSettings.enableStatistics && effectiveSettings.showStatisticsToggle,
        hasSasayakiToggle = onSasayakiTopToggle != null,
        hasBackJump = stateHolder.backTargetPosition != null,
        hasForwardJump = stateHolder.forwardTargetPosition != null,
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(effectiveSettings.backgroundColor(systemDarkTheme))),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = stableStatusBarPadding)
                .padding(bottom = stableNavigationBarPadding)
                .padding(
                    top = chromeLayout.topWebViewPaddingDp.dp,
                    bottom = bottomChromeMetrics.webViewBottomPaddingDp.dp,
                ),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val viewportHorizontalPadding = maxWidth * effectiveSettings.continuousViewportHorizontalPaddingRatio.toFloat()
                val viewportVerticalPadding = maxHeight * effectiveSettings.continuousViewportVerticalPaddingRatio.toFloat()
                highlights?.let { loadedHighlights ->
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
                        onContinuousScrollDisplayProgress = { progress, restoreEpoch ->
                            displayContinuousScrollProgress(progress, restoreEpoch)
                        },
                        onContinuousScrollProgress = { progress, restoreEpoch ->
                            saveContinuousScrollProgress(progress, restoreEpoch)
                        },
                        onInternalLink = { target ->
                            closeLookupPopupsAndSelection()
                            jumpToPositionWithHistory(target.position, target.fragment)
                        },
                        scanNonJapaneseText = dictionarySettings.scanNonJapaneseText,
                        readerSettings = effectiveSettings,
                        chapterHighlightsJson = ReaderHighlights.chapterHighlightsJson(
                            highlights = loadedHighlights,
                            bookInfo = book.bookInfo,
                            chapter = currentLoadChapter(),
                        ),
                        sasayakiTextColor = sasayakiSettings.textColor(effectiveSettings.usesDarkInterface(systemDarkTheme)),
                        sasayakiBackgroundColor = sasayakiSettings.backgroundColor(effectiveSettings.usesDarkInterface(systemDarkTheme)),
                        onTextSelected = handleTextSelected,
                        onClearLookupPopup = ::closeLookupPopupsAndSelection,
                        onReaderTapOutside = ::handleReaderTapOutside,
                        onReaderInteraction = stateHolder::enterFocusModeForReaderInteraction,
                        onImageTapped = ::openFullscreenImage,
                        onHighlightCreated = ::addHighlight,
                        fontManager = fontManager,
                        systemDark = systemDarkTheme,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                horizontal = viewportHorizontalPadding,
                                vertical = viewportVerticalPadding,
                            ),
                    )
                }
                LookupPopupAndroidOverlay(
                    popups = themedLookupPopups,
                    warmRootPopup = warmRootPopup,
                    rootHighlightRects = rootSelectionHighlightRects,
                    rootHighlightVerticalWriting = effectiveSettings.verticalWriting,
                    onPopupsChange = ::setLookupPopups,
                    lookupChildPopup = ::lookupChildPopup,
                    onRootPopupDismissed = {
                        dismissRootLookupPopup()
                        true
                    },
                    isRootPopupVisible = { popup -> popup.id in visibleLookupPopupIds },
                    onRootPopupContentReady = ::markRootPopupContentReady,
                    sasayakiWasPaused = sasayakiWasPausedByLookup,
                    sasayakiIsPlaying = sasayakiPlayer?.isPlaying == true,
                    onSasayakiReplayCue = { cue -> sasayakiPlayer?.playCue(cue, stop = true) },
                    onSasayakiTogglePlayback = { sasayakiPlayer?.togglePlayback() },
                    onSasayakiPauseStateCleared = stateHolder::clearSasayakiPauseState,
                    onSasayakiPlayForward = { cue ->
                        sasayakiPlayer?.playCue(cue, stop = false)
                        closeLookupPopupsAndSelection()
                    },
                    onPrepareSasayakiAudio = { cue, sentence ->
                        sasayakiPlayer?.exportCueAudio(cue, sentence)?.absolutePath
                    },
                    rootSelectionOffsetX = viewportHorizontalPadding.value.toDouble(),
                    rootSelectionOffsetY = viewportVerticalPadding.value.toDouble(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        ReaderTopInfo(
            state = chromeState,
            settings = effectiveSettings,
            colors = readerChromeColors(effectiveSettings, systemDarkTheme),
            onStatisticsToggle = if (effectiveSettings.enableStatistics && effectiveSettings.showStatisticsToggle) {
                ::toggleStatisticsTracking
            } else {
                null
            },
            statisticsTracking = statisticsState?.isTracking == true,
            onJumpBack = stateHolder.backTargetPosition?.let {
                {
                    closeLookupPopupsAndSelection()
                    navigateJumpBack()
                }
            },
            onJumpForward = stateHolder.forwardTargetPosition?.let {
                {
                    closeLookupPopupsAndSelection()
                    navigateJumpForward()
                }
            },
            onSasayakiToggle = onSasayakiTopToggle,
            sasayakiPlaying = sasayakiPlayer?.isPlaying == true || sasayakiWasPausedByLookup,
            visibility = chromeVisibility,
            metrics = bottomChromeMetrics,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = stableStatusBarPadding)
                .padding(horizontal = 15.dp),
        )
        ReaderFocusModeToggleArea(
            metrics = bottomChromeMetrics,
            sasayakiSkipButtons = sasayakiBottomSkipButtons,
            focusMode = focusMode,
            onToggleFocusMode = ::handleReaderTapOutside,
            bottomSystemPadding = stableNavigationBarPadding,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (chromeVisibility.showBottomChrome) ReaderBottomChrome(
            state = chromeState,
            settings = effectiveSettings,
            layout = chromeLayout,
            colors = readerChromeColors(effectiveSettings, systemDarkTheme),
            onClose = ::closeReader,
            onMenu = stateHolder::toggleReaderMenu,
            menuExpanded = showReaderMenu,
            onDismissMenu = stateHolder::dismissReaderMenu,
            onChapters = stateHolder::openChaptersFromMenu,
            onHighlights = stateHolder::openHighlightsFromMenu,
            onAppearance = stateHolder::openAppearanceFromMenu,
            onStatistics = if (effectiveSettings.enableStatistics) {
                stateHolder::openStatisticsFromMenu
            } else {
                null
            },
            onSasayaki = if (sasayakiSettings.enabled && sasayakiMatchData != null) {
                stateHolder::openSasayakiFromMenu
            } else {
                null
            },
            sasayakiSkipButtons = sasayakiBottomSkipButtons,
            onSasayakiSkipBackward = { performSasayakiBottomSkipAction(sasayakiBottomSkipButtonActions.left) },
            onSasayakiSkipForward = { performSasayakiBottomSkipAction(sasayakiBottomSkipButtonActions.right) },
            metrics = bottomChromeMetrics,
            bottomSystemPadding = stableNavigationBarPadding,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (showAppearance) {
            ReaderAppearanceSheet(
                settings = effectiveSettings,
                onSettingsChange = {
                    stateHolder.applySettings(it)
                    onReaderSettingsChange(it)
                },
                sasayakiSettings = sasayakiSettings,
                onSasayakiSettingsChange = ::updateSasayakiSettings,
                fontManager = fontManager,
                onDismiss = stateHolder::dismissAppearance,
            )
        }
        if (showChapters) {
            ReaderChapterSheet(
                book = book,
                currentPosition = readerPosition.displayedPosition,
                onJump = { target, fragment ->
                    closeLookupPopupsAndSelection()
                    jumpToPositionWithHistory(target, fragment)
                    stateHolder.dismissChapters()
                },
                onDismiss = stateHolder::dismissChapters,
            )
        }
        if (showHighlights && highlights != null) {
            ReaderHighlightSheet(
                book = book,
                highlights = highlights.orEmpty(),
                onJump = { highlight ->
                    closeLookupPopupsAndSelection()
                    val target = ReaderHighlights.positionForCharacter(book.bookInfo, highlight.character)
                    jumpToPositionWithHistory(target)
                    stateHolder.dismissHighlights()
                },
                onDelete = ::removeHighlight,
                onDismiss = stateHolder::dismissHighlights,
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
        if (showStatistics && statisticsState != null) {
            ReaderStatisticsSheet(
                state = requireNotNull(statisticsState),
                currentCharacter = currentDisplayedCharacter(),
                currentChapterEndCharacter = currentChapterEndCharacter(),
                totalCharacters = book.bookInfo.characterCount,
                onToggleTracking = ::toggleStatisticsTracking,
                onDismiss = stateHolder::dismissStatistics,
            )
        }
        fullscreenImage?.let { image ->
            ReaderFullscreenImageOverlay(
                image = image,
                resourceBridge = readerImageResourceBridge,
                backgroundColor = Color(effectiveSettings.backgroundColor(systemDarkTheme)),
                onDismiss = { fullscreenImage = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        webView?.let { _ -> Unit }
    }
}

@Composable
private fun ReaderFullscreenImageOverlay(
    image: ReaderFullscreenImage,
    resourceBridge: ReaderWebResourceBridge,
    backgroundColor: Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    BackHandler(onBack = onDismiss)
    Box(
        modifier = modifier
            .background(backgroundColor)
            .navigationBarsPadding(),
    ) {
        if (image.resource.mediaType.equals("image/svg+xml", ignoreCase = true)) {
            ReaderFullscreenSvgImage(
                image = image,
                resourceBridge = resourceBridge,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ReaderFullscreenRasterImage(
                image = image,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(1f)
                .padding(top = 20.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderFullscreenImageButton(
                icon = Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.action_copy),
                onClick = { copyReaderImage(context, image) },
            )
            ReaderFullscreenImageButton(
                icon = Icons.Rounded.Download,
                contentDescription = stringResource(R.string.action_save),
                onClick = { saveReaderImage(context, image) },
            )
            ReaderFullscreenImageButton(
                icon = Icons.Rounded.Share,
                contentDescription = stringResource(R.string.action_share),
                onClick = { shareReaderImage(context, image) },
            )
            ReaderFullscreenImageButton(
                icon = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.action_close),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun ReaderFullscreenRasterImage(
    image: ReaderFullscreenImage,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(image.resource.data) {
        BitmapFactory.decodeByteArray(image.resource.data, 0, image.resource.data.size)
    }
    var scale by remember(image.sourceUrl) { mutableStateOf(1f) }
    var offset by remember(image.sourceUrl) { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .pointerInput(image.sourceUrl) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 3f
                            offset = Offset.Zero
                        }
                    },
                )
            }
            .pointerInput(image.sourceUrl) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = nextScale
                    offset = if (nextScale == 1f) Offset.Zero else offset + pan
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { decoded ->
            Image(
                bitmap = decoded.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

@Composable
private fun ReaderFullscreenSvgImage(
    image: ReaderFullscreenImage,
    resourceBridge: ReaderWebResourceBridge,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                applyHoshiWebViewSecurityDefaults()
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setSupportZoom(true)
                setBackgroundColor(AndroidColor.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        request.url?.toString()?.let(resourceBridge::resourceForUrl)?.toWebResourceResponse()
                }
            }
        },
        update = { webView ->
            val escapedSource = image.sourceUrl.htmlAttributeEscaped()
            val html = """
                <!doctype html>
                <html>
                <head>
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  <style>
                    html, body { margin: 0; width: 100%; height: 100%; background: transparent; overflow: hidden; }
                    body { display: flex; align-items: center; justify-content: center; }
                    img { max-width: 100vw; max-height: 100vh; width: auto; height: auto; object-fit: contain; }
                  </style>
                </head>
                <body><img src="$escapedSource" /></body>
                </html>
            """.trimIndent()
            webView.loadDataWithBaseURL("https://hoshi.local/", html, "text/html", "UTF-8", null)
        },
    )
}

@Composable
private fun ReaderFullscreenImageButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription)
        }
    }
}

private fun shareReaderImage(context: Context, image: ReaderFullscreenImage) {
    val file = readerImageShareFile(context, image)
    file.writeBytes(image.resource.data)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(image.resource.mediaType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newUri(context.contentResolver, file.name, uri)
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
    }.recoverCatching { error ->
        if (error is ActivityNotFoundException || error is SecurityException) Unit else throw error
    }
}

private fun copyReaderImage(context: Context, image: ReaderFullscreenImage) {
    val file = readerImageShareFile(context, image)
    file.writeBytes(image.resource.data)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newUri(
        context.contentResolver,
        context.getString(R.string.reader_image_clip_label),
        uri,
    )
    clipboard.setPrimaryClip(clip)
    if (shouldShowReaderImageCopyToast()) {
        Toast.makeText(context, R.string.reader_image_copied, Toast.LENGTH_SHORT).show()
    }
}

private fun saveReaderImage(context: Context, image: ReaderFullscreenImage) {
    val name = "hoshi_reader_${UUID.randomUUID()}.${readerImageExtension(image.sourceUrl, image.resource.mediaType)}"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, image.resource.mediaType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Hoshi Reader")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    var uri: Uri? = null
    runCatching {
        uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        resolver.openOutputStream(requireNotNull(uri))?.use { output ->
            output.write(image.resource.data)
        } ?: error("MediaStore output stream unavailable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(requireNotNull(uri), values, null, null)
        }
    }.onSuccess {
        Toast.makeText(context, R.string.reader_image_saved, Toast.LENGTH_SHORT).show()
    }.onFailure {
        uri?.let { failedUri -> resolver.delete(failedUri, null, null) }
        Toast.makeText(context, R.string.reader_image_save_failed, Toast.LENGTH_SHORT).show()
    }
}

private fun readerImageShareFile(context: Context, image: ReaderFullscreenImage): File {
    val dir = File(context.cacheDir, "reader-images").also { it.mkdirs() }
    val extension = readerImageExtension(image.sourceUrl, image.resource.mediaType)
    return File(dir, "hoshi_reader_${UUID.randomUUID()}.$extension")
}

internal fun shouldShowReaderImageCopyToast(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt < Build.VERSION_CODES.TIRAMISU

private fun readerImageExtension(sourceUrl: String, mediaType: String): String {
    val pathExtension = runCatching {
        Uri.parse(sourceUrl).lastPathSegment
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
    return pathExtension ?: when (mediaType.substringBefore(';').lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/svg+xml" -> "svg"
        else -> "bin"
    }
}

private fun String.htmlAttributeEscaped(): String =
    replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

@Composable
private fun ReaderTopInfo(
    state: ReaderChromeState,
    settings: ReaderSettings,
    colors: ReaderChromeColors,
    onStatisticsToggle: (() -> Unit)?,
    statisticsTracking: Boolean,
    onJumpBack: (() -> Unit)?,
    onJumpForward: (() -> Unit)?,
    onSasayakiToggle: (() -> Unit)?,
    sasayakiPlaying: Boolean,
    visibility: ReaderChromeVisibility,
    metrics: ReaderBottomChromeMetrics,
    modifier: Modifier = Modifier,
) {
    val progress = state.progressText(settings)
    val showBackJump = visibility.showBackJump && state.backTargetCharacter != null && onJumpBack != null
    val showForwardJump = visibility.showForwardJump && state.forwardTargetCharacter != null && onJumpForward != null
    val showStatisticsToggle = visibility.showStatisticsToggle && onStatisticsToggle != null
    val showSasayakiToggle = visibility.showSasayakiToggle && onSasayakiToggle != null
    if ((!visibility.showTitleAndProgress || !settings.showTitle) &&
        !showStatisticsToggle &&
        !showBackJump &&
        !showForwardJump &&
        !showSasayakiToggle &&
        (!visibility.showTitleAndProgress || progress.isBlank() || !settings.showProgressTop)
    ) return
    Box(modifier = modifier.fillMaxWidth()) {
        val showStartControls = showStatisticsToggle || showBackJump
        val showEndControls = showSasayakiToggle || showForwardJump
        var startControlWidth by remember { mutableStateOf(0) }
        var endControlWidth by remember { mutableStateOf(0) }
        val density = LocalDensity.current
        val dynamicTitlePadding = with(density) {
            val maxControlWidth = maxOf(
                if (showStartControls) startControlWidth else 0,
                if (showEndControls) endControlWidth else 0,
            )
            if (maxControlWidth > 0) maxControlWidth.toDp() + 4.dp else 0.dp
        }
        val titlePadding = readerTopTitlePaddingDp(
            hasStartControl = showStartControls,
            hasEndControl = showEndControls,
        )
        val resolvedTitlePadding = maxOf(
            titlePadding.startDp.dp,
            titlePadding.endDp.dp,
            dynamicTitlePadding,
        )
        Column(
            modifier = Modifier.align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (visibility.showTitleAndProgress && settings.showTitle) {
                Text(
                    text = state.title,
                    color = Color(colors.infoText),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.padding(
                        start = resolvedTitlePadding,
                        end = resolvedTitlePadding,
                    ),
                )
            }
            if (visibility.showTitleAndProgress && settings.showProgressTop && progress.isNotBlank()) {
                Text(
                    text = progress,
                    color = Color(colors.infoText),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (showStartControls) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onSizeChanged { startControlWidth = it.width },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (showStatisticsToggle) {
                    ReaderRoundButton(
                        colors = colors,
                        sizeDp = metrics.topStatisticsButtonSizeDp,
                        onClick = requireNotNull(onStatisticsToggle),
                    ) {
                        Icon(
                            imageVector = readerStatisticsTopToggleIcon(statisticsTracking),
                            contentDescription = if (statisticsTracking) {
                                stringResource(R.string.reader_statistics_pause)
                            } else {
                                stringResource(R.string.reader_statistics_start)
                            },
                            modifier = Modifier.size(metrics.topStatisticsIconSizeDp.dp),
                            tint = Color(colors.buttonContent),
                        )
                    }
                }
                if (showBackJump) {
                    ReaderJumpHistoryButton(
                        character = requireNotNull(state.backTargetCharacter),
                        icon = readerJumpBackIcon(),
                        iconFirst = true,
                        contentDescription = stringResource(R.string.reader_jump_back),
                        colors = colors,
                        heightDp = metrics.topStatisticsButtonSizeDp,
                        onClick = requireNotNull(onJumpBack),
                    )
                }
            }
        }
        if (showEndControls) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .onSizeChanged { endControlWidth = it.width },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (showForwardJump) {
                    ReaderJumpHistoryButton(
                        character = requireNotNull(state.forwardTargetCharacter),
                        icon = readerJumpForwardIcon(),
                        iconFirst = false,
                        contentDescription = stringResource(R.string.reader_jump_forward),
                        colors = colors,
                        heightDp = metrics.topSasayakiButtonSizeDp,
                        onClick = requireNotNull(onJumpForward),
                    )
                }
                if (showSasayakiToggle) {
                    ReaderRoundButton(
                        colors = colors,
                        sizeDp = metrics.topSasayakiButtonSizeDp,
                        onClick = requireNotNull(onSasayakiToggle),
                    ) {
                        Icon(
                            imageVector = readerSasayakiTopToggleIcon(sasayakiPlaying),
                            contentDescription = if (sasayakiPlaying) {
                                stringResource(R.string.sasayaki_pause)
                            } else {
                                stringResource(R.string.sasayaki_play)
                            },
                            modifier = Modifier.size(metrics.topSasayakiIconSizeDp.dp),
                            tint = Color(colors.buttonContent),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderJumpHistoryButton(
    character: Int,
    icon: ImageVector,
    iconFirst: Boolean,
    contentDescription: String,
    colors: ReaderChromeColors,
    heightDp: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(heightDp.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (iconFirst) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(16.dp),
                tint = Color(colors.infoText),
            )
        }
        Text(
            text = readerJumpTargetText(character),
            color = Color(colors.infoText),
            style = MaterialTheme.typography.labelSmall,
        )
        if (!iconFirst) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(16.dp),
                tint = Color(colors.infoText),
            )
        }
    }
}

internal fun readerStatisticsTopToggleIcon(isTracking: Boolean): ImageVector =
    if (isTracking) Icons.Rounded.Timer else Icons.AutoMirrored.Rounded.ShowChart

internal fun readerSasayakiTopToggleIcon(isPlaying: Boolean): ImageVector =
    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.GraphicEq

@Composable
private fun ReaderFocusModeToggleArea(
    metrics: ReaderBottomChromeMetrics,
    sasayakiSkipButtons: ReaderSasayakiBottomSkipButtons,
    focusMode: Boolean,
    onToggleFocusMode: () -> Unit,
    bottomSystemPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val toggleArea = readerFocusModeToggleArea(
        metrics = metrics,
        sasayakiSkipButtons = sasayakiSkipButtons,
        focusMode = focusMode,
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomSystemPadding)
            .padding(horizontal = toggleArea.horizontalPaddingDp.dp)
            .height((metrics.buttonSizeDp + metrics.bottomPaddingDp + 8).dp)
            .clickable(onClick = onToggleFocusMode),
    )
}

@Composable
private fun rememberStableStatusBarPadding(): Dp {
    val density = LocalDensity.current
    val currentTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    var stableTop by remember { mutableStateOf(0.dp) }
    LaunchedEffect(currentTop) {
        if (currentTop > 0.dp) {
            stableTop = currentTop
        }
    }
    return if (currentTop > 0.dp) currentTop else stableTop
}

@Composable
private fun rememberStableNavigationBarPadding(): Dp {
    val density = LocalDensity.current
    val currentBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    var stableBottom by remember { mutableStateOf(0.dp) }
    LaunchedEffect(currentBottom) {
        if (currentBottom > 0.dp) {
            stableBottom = currentBottom
        }
    }
    return if (currentBottom > 0.dp) currentBottom else stableBottom
}

@Composable
private fun BoxScope.ReaderBottomChrome(
    state: ReaderChromeState,
    settings: ReaderSettings,
    layout: ReaderChromeLayout,
    colors: ReaderChromeColors,
    onClose: () -> Unit,
    onMenu: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onChapters: () -> Unit,
    onHighlights: () -> Unit,
    onAppearance: () -> Unit,
    onStatistics: (() -> Unit)?,
    onSasayaki: (() -> Unit)?,
    sasayakiSkipButtons: ReaderSasayakiBottomSkipButtons,
    onSasayakiSkipBackward: () -> Unit,
    onSasayakiSkipForward: () -> Unit,
    metrics: ReaderBottomChromeMetrics,
    bottomSystemPadding: Dp,
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
            metrics = metrics,
            onChapters = onChapters,
            onHighlights = onHighlights,
            onAppearance = onAppearance,
            onStatistics = onStatistics,
            onSasayaki = onSasayaki,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = bottomSystemPadding)
                .padding(end = metrics.horizontalPaddingDp.dp, bottom = metrics.menuBottomPaddingDp.dp),
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomSystemPadding)
            .padding(
                start = metrics.horizontalPaddingDp.dp,
                end = metrics.horizontalPaddingDp.dp,
                top = 8.dp,
                bottom = metrics.bottomPaddingDp.dp,
            ),
    ) {
        if (layout.bottomCenterLineCount > 0) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .heightIn(max = layout.bottomCenterMaxHeightDp.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (layout.showStatisticsInBottomBar) {
                    Text(
                        text = state.statisticsText(settings),
                        color = Color(colors.infoText),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
                if (layout.showProgressInBottomBar) {
                    Text(
                        text = state.progressText(settings),
                        color = Color(colors.infoText),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderGlassButton(colors = colors, metrics = metrics, onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    modifier = Modifier.size(metrics.primaryIconSizeDp.dp),
                    tint = Color(colors.buttonContent),
                )
            }
            if (sasayakiSkipButtons.visible) {
                Spacer(Modifier.width(sasayakiSkipButtons.adjacentSpacingDp.dp))
                ReaderGlassButton(colors = colors, metrics = metrics, onClick = onSasayakiSkipBackward) {
                    Icon(
                        imageVector = Icons.Rounded.FastRewind,
                        contentDescription = stringResource(R.string.sasayaki_rewind),
                        modifier = Modifier.size(sasayakiSkipButtons.iconSizeDp.dp),
                        tint = Color(colors.buttonContent),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (sasayakiSkipButtons.visible) {
                ReaderGlassButton(colors = colors, metrics = metrics, onClick = onSasayakiSkipForward) {
                    Icon(
                        imageVector = Icons.Rounded.FastForward,
                        contentDescription = stringResource(R.string.sasayaki_fast_forward),
                        modifier = Modifier.size(sasayakiSkipButtons.iconSizeDp.dp),
                        tint = Color(colors.buttonContent),
                    )
                }
                Spacer(Modifier.width(sasayakiSkipButtons.adjacentSpacingDp.dp))
            }
            ReaderGlassButton(colors = colors, metrics = metrics, onClick = onMenu) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = stringResource(R.string.reader_menu),
                    modifier = Modifier.size(metrics.secondaryIconSizeDp.dp),
                    tint = Color(colors.buttonContent),
                )
            }
        }
    }
}

@Composable
private fun ReaderMenuCard(
    colors: ReaderChromeColors,
    metrics: ReaderBottomChromeMetrics,
    onChapters: () -> Unit,
    onHighlights: () -> Unit,
    onAppearance: () -> Unit,
    onStatistics: (() -> Unit)?,
    onSasayaki: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .width(metrics.menuWidthDp.dp),
        shape = RoundedCornerShape(28.dp),
        color = Color(colors.menuContainer),
        border = BorderStroke(1.dp, Color(colors.menuBorder)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = metrics.menuVerticalPaddingDp.dp),
        ) {
            ReaderMenuItem(
                text = stringResource(R.string.reader_chapters),
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.List,
                        contentDescription = null,
                        tint = Color(colors.menuContent),
                    )
                },
                colors = colors,
                metrics = metrics,
                onClick = onChapters,
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = metrics.menuItemHorizontalPaddingDp.dp),
                color = Color(colors.menuBorder),
            )
            ReaderMenuItem(
                text = stringResource(R.string.reader_highlights),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.BorderColor,
                        contentDescription = null,
                        tint = Color(colors.menuContent),
                    )
                },
                colors = colors,
                metrics = metrics,
                onClick = onHighlights,
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = metrics.menuItemHorizontalPaddingDp.dp),
                color = Color(colors.menuBorder),
            )
            ReaderMenuItem(
                text = stringResource(R.string.settings_appearance),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Palette,
                        contentDescription = null,
                        tint = Color(colors.menuContent),
                    )
                },
                colors = colors,
                metrics = metrics,
                onClick = onAppearance,
            )
            if (onStatistics != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = metrics.menuItemHorizontalPaddingDp.dp),
                    color = Color(colors.menuBorder),
                )
                ReaderMenuItem(
                    text = stringResource(R.string.reader_statistics),
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ShowChart,
                            contentDescription = null,
                            tint = Color(colors.menuContent),
                        )
                    },
                    colors = colors,
                    metrics = metrics,
                    onClick = onStatistics,
                )
            }
            if (onSasayaki != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = metrics.menuItemHorizontalPaddingDp.dp),
                    color = Color(colors.menuBorder),
                )
                ReaderMenuItem(
                    text = stringResource(R.string.sasayaki_title),
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = Color(colors.menuContent),
                        )
                    },
                    colors = colors,
                    metrics = metrics,
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
    metrics: ReaderBottomChromeMetrics,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = metrics.menuItemHorizontalPaddingDp.dp,
                vertical = metrics.menuItemVerticalPaddingDp.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.menuItemSpacingDp.dp),
    ) {
        Box(
            modifier = Modifier.size(metrics.menuItemIconBoxSizeDp.dp),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Text(
            text = text,
            color = Color(colors.menuContent),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ReaderGlassButton(
    colors: ReaderChromeColors,
    metrics: ReaderBottomChromeMetrics,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    ReaderRoundButton(
        colors = colors,
        sizeDp = metrics.buttonSizeDp,
        onClick = onClick,
        content = content,
    )
}

@Composable
private fun ReaderRoundButton(
    colors: ReaderChromeColors,
    sizeDp: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier.size(sizeDp.dp),
        shape = CircleShape,
        color = Color(colors.buttonContainer),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .fillMaxSize(),
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
    onContinuousScrollDisplayProgress: (progress: Double, restoreEpoch: Int) -> Unit,
    onContinuousScrollProgress: (progress: Double, restoreEpoch: Int) -> Unit,
    onInternalLink: (ReaderInternalLinkTarget) -> Unit,
    scanNonJapaneseText: Boolean,
    readerSettings: ReaderSettings,
    chapterHighlightsJson: String?,
    sasayakiTextColor: Long,
    sasayakiBackgroundColor: Long,
    onTextSelected: (ReaderSelectionData, selectionRects: (Int, (List<ReaderSelectionRect>) -> Unit) -> Unit) -> Unit,
    onClearLookupPopup: () -> Unit,
    onReaderTapOutside: () -> Unit,
    onReaderInteraction: () -> Unit,
    onImageTapped: (String) -> Unit,
    onHighlightCreated: (HighlightColor, String, ReaderHighlightCreationResult) -> Unit,
    fontManager: ReaderFontManager,
    systemDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val currentOnTextSelected = rememberUpdatedState(onTextSelected)
    val currentOnSaveBookmark = rememberUpdatedState(onSaveBookmark)
    val currentOnDisplayProgress = rememberUpdatedState(onDisplayProgress)
    val currentOnContinuousScrollDisplayProgress = rememberUpdatedState(onContinuousScrollDisplayProgress)
    val currentOnContinuousScrollProgress = rememberUpdatedState(onContinuousScrollProgress)
    val currentOnClearLookupPopup = rememberUpdatedState(onClearLookupPopup)
    val currentOnReaderTapOutside = rememberUpdatedState(onReaderTapOutside)
    val currentOnReaderInteraction = rememberUpdatedState(onReaderInteraction)
    val currentOnImageTapped = rememberUpdatedState(onImageTapped)
    val currentOnHighlightCreated = rememberUpdatedState(onHighlightCreated)
    val currentOnNextChapter = rememberUpdatedState(onNextChapter)
    val currentOnPreviousChapter = rememberUpdatedState(onPreviousChapter)
    val currentIsWebViewRestoring = rememberUpdatedState(isWebViewRestoring)
    val currentWebViewRestoreEpoch = rememberUpdatedState(webViewRestoreEpoch)
    val currentOnRestoreStarted = rememberUpdatedState(onRestoreStarted)
    val currentOnRestoreCompleted = rememberUpdatedState(onRestoreCompleted)
    var lastContinuousProgressUpdate by remember { mutableStateOf(0L) }
    var continuousScrollSaveRequestId by remember { mutableStateOf(0L) }
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
    var readerWebView by remember { mutableStateOf<WebView?>(null) }
    val fontFaceUrl = remember(readerSettings.selectedFont) {
        fontManager.webViewFontUrl(readerSettings.selectedFont)
    }
    val baseUrl = remember(chapter) { "https://hoshi.local/epub/${chapter.href}" }
    val readerContentReloadKey = remember(readerSettings) {
        readerSettings.readerContentReloadKey()
    }
    val readerAppearanceScript = remember(
        readerSettings.theme,
        readerSettings.eInkMode,
        readerSettings.systemLightSepia,
        readerSettings.sepiaInvertInDark,
        systemDark,
    ) {
        readerAppearanceScript(readerSettings, systemDark)
    }
    val readerSetupScript = remember(
        chapter,
        chapterPosition.progress,
        chapterFragment,
        readerContentReloadKey,
        fontFaceUrl,
        systemDark,
        scanNonJapaneseText,
        sasayakiTextColor,
        sasayakiBackgroundColor,
    ) {
        readerSetupScript(
            initialProgress = chapterPosition.progress,
            initialFragment = chapterFragment,
            settings = readerSettings,
            fontFaceUrl = fontFaceUrl,
            systemDark = systemDark,
            scanNonJapaneseText = scanNonJapaneseText,
            sasayakiTextColor = sasayakiTextColor,
            sasayakiBackgroundColor = sasayakiBackgroundColor,
            highlightsJson = chapterHighlightsJson,
        )
    }
    AndroidView(
        modifier = modifier
            .onSizeChanged(onReaderViewportSizeChanged)
            .background(Color(readerSettings.backgroundColor(systemDark))),
        factory = { context ->
            HoshiReaderWebView(context).apply {
                applyHoshiWebViewSecurityDefaults()
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                this.onHighlightCreated = { color, id, creation ->
                    currentOnHighlightCreated.value(color, id, creation)
                }
                hideForReaderRestore()
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(
                    ReaderSelectionBridge(this) { selection, selectionRects ->
                        currentOnTextSelected.value(selection, selectionRects)
                    },
                    "HoshiTextSelection",
                )
                addJavascriptInterface(
                    ReaderRestoreBridge(this) { restoredWebView ->
                        currentOnFragmentRestored.value(restoredWebView)
                    },
                    "HoshiReaderRestore",
                )
                addJavascriptInterface(
                    ReaderImageTapBridge(this) { sourceUrl ->
                        currentOnImageTapped.value(sourceUrl)
                    },
                    "HoshiReaderImage",
                )
                webViewClient = EpubWebViewClient(book, fontManager, onInternalLink) { view ->
                    view.evaluateJavascript(readerSetupScript, null)
                }
                readerWebView = this
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
                    val selectionResult = ReaderSelectionResult.fromWebViewResult(result)
                    when {
                        selectionResult.isImageTap || selectionResult.isLinkTap -> Unit
                        selectionResult.selectedNothing -> currentOnReaderTapOutside.value()
                    }
                }
            }
            if (readerSettings.continuousMode) {
                webView.setOnTouchListener(
                    ContinuousScrollTouchListener(
                        settings = readerSettings,
                        shouldIgnoreReaderGesture = {
                            currentIsWebViewRestoring.value || webView.isNativeSelectionActionModeActive()
                        },
                        onTap = ::selectAt,
                        onNextChapter = {
                            currentOnReaderInteraction.value()
                            currentOnClearLookupPopup.value()
                            val changed = currentOnNextChapter.value()
                            if (changed) webView.hideForReaderRestore()
                            changed
                        },
                        onPreviousChapter = {
                            currentOnReaderInteraction.value()
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
                    currentOnReaderInteraction.value()
                    val restoreEpoch = currentWebViewRestoreEpoch.value
                    continuousScrollSaveRequestId += 1L
                    val requestId = continuousScrollSaveRequestId
                    readerPendingProgressSaveCallbacks.remove(webView)?.let(webView::removeCallbacks)
                    currentOnClearLookupPopup.value()
                    webView.evaluateJavascript(ReaderPaginationScripts.progressInvocation()) { progressResult ->
                        if (continuousScrollSaveRequestId != requestId) return@evaluateJavascript
                        ReaderPaginationScripts.doubleResult(progressResult)?.let { progress ->
                            when (readerProgressPersistenceAction(ReaderProgressPersistenceEvent.ContinuousScrollChanged)) {
                                ReaderProgressPersistenceAction.DisplayOnly -> {
                                    currentOnContinuousScrollDisplayProgress.value(progress, restoreEpoch)
                                }
                                ReaderProgressPersistenceAction.SaveBookmark -> {
                                    currentOnContinuousScrollProgress.value(progress, restoreEpoch)
                                }
                            }
                            lateinit var saveCallback: Runnable
                            saveCallback = Runnable {
                                if (continuousScrollSaveRequestId != requestId) return@Runnable
                                if (readerPendingProgressSaveCallbacks[webView] == saveCallback) {
                                    readerPendingProgressSaveCallbacks.remove(webView)
                                }
                                when (readerProgressPersistenceAction(ReaderProgressPersistenceEvent.ContinuousScrollIdle)) {
                                    ReaderProgressPersistenceAction.DisplayOnly -> currentOnDisplayProgress.value(progress)
                                    ReaderProgressPersistenceAction.SaveBookmark -> {
                                        currentOnContinuousScrollProgress.value(progress, restoreEpoch)
                                    }
                                }
                            }
                            readerPendingProgressSaveCallbacks[webView] = saveCallback
                            webView.postDelayed(saveCallback, CONTINUOUS_SCROLL_SAVE_IDLE_DELAY_MS)
                        }
                    }
                }
            } else {
                readerPendingProgressSaveCallbacks.remove(webView)?.let(webView::removeCallbacks)
                webView.setOnScrollChangeListener(null)
                webView.setOnTouchListener(object : SwipePageTouchListener() {
                    override fun shouldIgnoreReaderGesture(): Boolean =
                        currentIsWebViewRestoring.value || webView.isNativeSelectionActionModeActive()

                    override fun onTap(x: Float, y: Float) {
                        selectAt(x, y)
                    }

                    override fun onLeftSwipe() {
                        currentOnReaderInteraction.value()
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
                        currentOnReaderInteraction.value()
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
            webView.evaluateJavascript(readerAppearanceScript, null)
            if (!readerWebViewReadyToLoad(webViewViewportSize)) return@AndroidView
            val loadKey = readerWebViewLoadKey(
                baseUrl = baseUrl,
                readerContentReloadKey = readerContentReloadKey,
                readerSetupScript = readerSetupScript,
                webViewViewportSize = webViewViewportSize,
            )
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

internal fun readerWebViewReadyToLoad(webViewViewportSize: IntSize): Boolean =
    webViewViewportSize != IntSize.Zero

internal fun readerWebViewLoadKey(
    baseUrl: String,
    readerContentReloadKey: ReaderContentReloadKey,
    readerSetupScript: String,
    webViewViewportSize: IntSize,
): String =
    "$baseUrl#${readerContentReloadKey.hashCode()}#${readerSetupScript.hashCode()}#$webViewViewportSize"

internal fun readerHtmlWithEarlyViewport(html: String): String {
    val withoutViewport = readerViewportMetaRegex.replace(html, "")
    val head = readerHeadOpenTagRegex.find(withoutViewport)
    val viewport = """<meta name="viewport" content="$ReaderViewportContent" />"""
    if (head != null) {
        val insertAt = head.range.last + 1
        return withoutViewport.substring(0, insertAt) + "\n$viewport" + withoutViewport.substring(insertAt)
    }
    val htmlTag = readerHtmlOpenTagRegex.find(withoutViewport)
    if (htmlTag != null) {
        val insertAt = htmlTag.range.last + 1
        return withoutViewport.substring(0, insertAt) + "\n<head>$viewport</head>" + withoutViewport.substring(insertAt)
    }
    return "<head>$viewport</head>\n$withoutViewport"
}

private const val ReaderViewportContent = "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"

private val readerViewportMetaRegex =
    Regex("""(?is)<meta\b(?=[^>]*\bname\s*=\s*(['"])viewport\1)[^>]*>""")

private val readerHeadOpenTagRegex = Regex("""(?is)<head\b[^>]*>""")

private val readerHtmlOpenTagRegex = Regex("""(?is)<html\b[^>]*>""")

internal fun readerShouldReserveSasayakiTopToggle(bookRoot: File?, settings: SasayakiSettings): Boolean =
    settings.enabled &&
        settings.showReaderToggle &&
        bookRoot?.resolve(ReaderSasayakiMatchFileName)?.isFile == true &&
        bookRoot.resolve(ReaderSasayakiPlaybackFileName).isFile

private class HoshiReaderWebView(context: Context) : WebView(context) {
    var onHighlightCreated: (HighlightColor, String, ReaderHighlightCreationResult) -> Unit = { _, _, _ -> }
    private var nativeSelectionActionModeActive = false
    private var nativeSelectionActionMode: ActionMode? = null
    private var nativeSelectionContentRect: Rect? = null
    private var highlightColorPopup: PopupWindow? = null

    fun isNativeSelectionActionModeActive(): Boolean = nativeSelectionActionModeActive
    fun setNativeSelectionActionMode(mode: ActionMode?) {
        nativeSelectionActionMode = mode
        nativeSelectionActionModeActive = mode != null
        evaluateJavascript(ReaderPaginationScripts.nativeSelectionActiveInvocation(nativeSelectionActionModeActive), null)
        if (mode == null) {
            nativeSelectionContentRect = null
            dismissHighlightColorPopup()
        }
    }

    fun setNativeSelectionContentRect(rect: Rect) {
        nativeSelectionContentRect = Rect(rect)
    }

    fun prepareHighlightColorPicker(mode: ActionMode) {
        val anchor = nativeSelectionContentRect?.let { Rect(it) }
        evaluateJavascript(ReaderHighlightCommand.PrepareSelection.source) { result ->
            if (result?.trim() == "true") {
                mode.finish()
                post { showHighlightColorPicker(anchor) }
            }
        }
    }

    fun showHighlightColorPicker(anchorRect: Rect? = nativeSelectionContentRect) {
        dismissHighlightColorPopup()
        val density = resources.displayMetrics.density
        val popupContent = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(AndroidColor.WHITE)
                cornerRadius = 20f * density
                setStroke((1f * density).toInt().coerceAtLeast(1), 0x22000000)
            }
            elevation = 8f * density
            val paddingHorizontal = (ReaderHighlightSelectionMenu.colorPickerHorizontalPaddingDp * density).toInt()
            val paddingVertical = (ReaderHighlightSelectionMenu.colorPickerVerticalPaddingDp * density).toInt()
            setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
            ReaderHighlightSelectionMenu.colorItems.forEach { item ->
                addView(highlightColorButton(item, density))
            }
        }
        popupContent.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        highlightColorPopup = PopupWindow(
            popupContent,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false,
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            elevation = 8f * density
        }

        val location = IntArray(2)
        getLocationOnScreen(location)
        val margin = (16f * density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val popupWidth = popupContent.measuredWidth
        val popupHeight = popupContent.measuredHeight
        val anchor = anchorRect?.let {
            ReaderHighlightSelectionAnchor(
                left = it.left,
                top = it.top,
                right = it.right,
                bottom = it.bottom,
            )
        }
        val position = ReaderHighlightSelectionMenu.colorPickerPopupPosition(
            viewLeft = location[0],
            viewTop = location[1],
            viewWidth = width,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            popupWidth = popupWidth,
            popupHeight = popupHeight,
            margin = margin,
            anchor = anchor,
        )
        highlightColorPopup?.showAtLocation(this, Gravity.NO_GRAVITY, position.x, position.y)
    }

    private fun highlightColorButton(item: ReaderHighlightSelectionMenuItem, density: Float): TextView {
        val touchTargetSize = (ReaderHighlightSelectionMenu.colorPickerTouchTargetSizeDp * density).toInt()
        val swatchInset = (
            (ReaderHighlightSelectionMenu.colorPickerTouchTargetSizeDp - ReaderHighlightSelectionMenu.colorPickerSwatchSizeDp) *
                density / 2f
            ).toInt()
        val margin = (ReaderHighlightSelectionMenu.colorPickerButtonMarginDp * density).toInt()
        return TextView(context).apply {
            contentDescription = item.title
            background = InsetDrawable(
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(item.color.swatchArgb.toInt())
                },
                swatchInset,
            )
            setOnClickListener { createHighlightFromNativeSelection(item.color) }
            layoutParams = LinearLayout.LayoutParams(touchTargetSize, touchTargetSize).apply {
                setMargins(margin, 0, margin, 0)
            }
        }
    }

    fun createHighlightFromNativeSelection(color: HighlightColor) {
        val mode = nativeSelectionActionMode
        val id = UUID.randomUUID().toString()
        dismissHighlightColorPopup()
        evaluateJavascript(ReaderHighlightCommand.Create(color, id).source) { result ->
            ReaderHighlightCreationResult.fromWebViewResult(result)?.let { creation ->
                onHighlightCreated(color, id, creation)
            }
            mode?.finish()
        }
    }

    private fun dismissHighlightColorPopup() {
        highlightColorPopup?.dismiss()
        highlightColorPopup = null
    }

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(ReaderHighlightActionModeCallback(this, callback))

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(ReaderHighlightActionModeCallback(this, callback), type)
}

private class ReaderHighlightActionModeCallback(
    private val webView: HoshiReaderWebView,
    private val delegate: ActionMode.Callback,
) : ActionMode.Callback2() {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        addHighlightMenu(menu)
        val created = delegate.onCreateActionMode(mode, menu)
        if (created) {
            webView.setNativeSelectionActionMode(mode)
            addHighlightMenu(menu)
        }
        return created
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        addHighlightMenu(menu)
        return delegate.onPrepareActionMode(mode, menu)
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId == ReaderHighlightSelectionMenu.parentItemId) {
            webView.prepareHighlightColorPicker(mode)
            return true
        }
        val color = ReaderHighlightSelectionMenu.colorForItemId(item.itemId)
        if (color != null) {
            webView.createHighlightFromNativeSelection(color)
            return true
        }
        return delegate.onActionItemClicked(mode, item)
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        webView.setNativeSelectionActionMode(null)
        delegate.onDestroyActionMode(mode)
    }

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
        if (delegate is ActionMode.Callback2) {
            delegate.onGetContentRect(mode, view, outRect)
        } else {
            super.onGetContentRect(mode, view, outRect)
        }
        webView.setNativeSelectionContentRect(outRect)
    }

    private fun addHighlightMenu(menu: Menu) {
        if (menu.findItem(ReaderHighlightSelectionMenu.parentItemId) != null) return
        ReaderHighlightSelectionMenu.actionModeItems.forEach { item ->
            menu.add(
                ReaderHighlightSelectionMenu.groupId,
                item.id,
                item.order,
                webView.context.getString(R.string.reader_highlight_action),
            ).setShowAsAction(item.showAsAction)
        }
    }
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
    scanNonJapaneseText: Boolean,
    sasayakiTextColor: Long,
    sasayakiBackgroundColor: Long,
    highlightsJson: String?,
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
        highlightsJson = highlightsJson,
    ).scriptTagBody()
    return """
        (function() {
          var style = document.createElement('style');
          style.textContent = $css;
          document.head.appendChild(style);
          window.scanNonJapaneseText = $scanNonJapaneseText;
          $selectionScript
          $paginationScript
        })();
    """.trimIndent()
}

private fun readerAppearanceScript(
    settings: ReaderSettings,
    systemDark: Boolean,
): String {
    val backgroundColor = settings.backgroundColor(systemDark).toReaderCssColor().javaScriptStringLiteral()
    val textColor = settings.textColorCss(systemDark).javaScriptStringLiteral()
    return """
        (function() {
          document.documentElement.style.setProperty('--hoshi-background-color', $backgroundColor);
          document.documentElement.style.setProperty('--hoshi-text-color', $textColor);
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
            val webView = this
            val requestId = nextReaderPageTurnProgressRequestId()
            readerPageTurnProgressRequestIds[webView] = requestId
            webView.postVisualStateCallback(
                requestId,
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (readerPageTurnProgressRequestIds[webView] != requestId) return
                        webView.postOnAnimation {
                            webView.evaluateJavascript(ReaderPaginationScripts.progressInvocation()) { progressResult ->
                                if (readerPageTurnProgressRequestIds[webView] != requestId) return@evaluateJavascript
                                readerPageTurnProgressRequestIds.remove(webView)
                                val progress = ReaderPaginationScripts.doubleResult(progressResult) ?: return@evaluateJavascript
                                onDisplayedProgress(progress)
                                when (readerProgressPersistenceAction(ReaderProgressPersistenceEvent.PaginatedPageTurnCompleted)) {
                                    ReaderProgressPersistenceAction.DisplayOnly -> Unit
                                    ReaderProgressPersistenceAction.SaveBookmark -> onSaveProgress(progress)
                                }
                            }
                        }
                    }
                },
            )
        } else {
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

private fun WebView.flushPendingProgressSave() {
    val progressCallback = readerPendingProgressSaveCallbacks.remove(this) ?: return
    removeCallbacks(progressCallback)
    progressCallback.run()
}

private class ContinuousScrollTouchListener(
    private val settings: ReaderSettings,
    private val shouldIgnoreReaderGesture: () -> Boolean,
    private val onTap: (Float, Float) -> Unit,
    private val onNextChapter: () -> Boolean,
    private val onPreviousChapter: () -> Boolean,
) : View.OnTouchListener {
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var currentGestureIgnored = false

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val webView = view as? WebView ?: return false
        if (shouldIgnoreReaderGesture()) {
            currentGestureIgnored = true
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                currentGestureIgnored = false
            }
            MotionEvent.ACTION_CANCEL -> {
                currentGestureIgnored = false
            }
            MotionEvent.ACTION_UP -> {
                if (currentGestureIgnored) {
                    currentGestureIgnored = false
                    return false
                }
                val dx = event.x - downX
                val dy = event.y - downY
                val elapsedMs = event.eventTime - downTime
                if (elapsedMs <= MAX_TAP_DURATION_MS && abs(dx) < TAP_SLOP && abs(dy) < TAP_SLOP) {
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
        const val MAX_TAP_DURATION_MS = 500L
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

private class ReaderImageTapBridge(
    private val webView: WebView,
    private val onImageTapped: (String) -> Unit,
) {
    @JavascriptInterface
    fun postMessage(sourceUrl: String) {
        webView.post {
            onImageTapped(sourceUrl)
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
private val readerPendingProgressSaveCallbacks = WeakHashMap<WebView, Runnable>()
private val readerPageTurnProgressRequestIds = WeakHashMap<WebView, Long>()
private var readerPageTurnProgressRequestId = 0L
private const val MAX_SELECTION_LENGTH = 16
private const val CONTINUOUS_PROGRESS_THROTTLE_MS = 50L
private const val CONTINUOUS_SCROLL_SAVE_IDLE_DELAY_MS = 250L

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

private fun SasayakiMatch.toCueRange(): SasayakiCueRange =
    SasayakiCueRange(id = id, start = start, length = length)

private fun SasayakiPlaybackData?.hasStoredAudioSource(): Boolean =
    this?.audioUri?.isNotBlank() == true || this?.audioFileName?.isNotBlank() == true

private const val ReaderSasayakiMatchFileName = "sasayaki_match.json"
private const val ReaderSasayakiPlaybackFileName = "sasayaki_playback.json"

internal fun androidPixelsToCssPixels(value: Float, density: Float): Float =
    value / density.coerceAtLeast(1f)

private fun String.codePointCount(): Int =
    codePointCount(0, length)
