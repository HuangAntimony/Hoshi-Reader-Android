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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import java.util.WeakHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupOptions
import moe.antimony.hoshi.features.dictionary.LookupPopupStackView
import moe.antimony.hoshi.features.dictionary.createLookupPopupItem
import moe.antimony.hoshi.features.dictionary.withLookupPopupVisualOptions
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

private data class PendingRootSelectionHighlight(
    val popupId: String,
    val rects: List<ReaderSelectionRect>? = null,
    val contentReady: Boolean = false,
)

private fun popupAnchorRect(rects: List<ReaderSelectionRect>): ReaderSelectionRect? =
    rects.firstOrNull()

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
    val dictionarySettingsRepository = appContainer.dictionarySettingsRepository
    val audioSettingsRepository = appContainer.audioSettingsRepository
    val sasayakiSettingsRepository = appContainer.sasayakiSettingsRepository
    val bookRepository = appContainer.bookRepository
    var sasayakiSettings by remember { mutableStateOf(SasayakiSettings()) }
    var sasayakiMatchData by remember(bookRoot) { mutableStateOf<SasayakiMatchData?>(null) }
    LaunchedEffect(bookRoot, bookRepository) {
        sasayakiMatchData = bookRoot?.let { bookRepository.loadSasayakiMatch(it) }
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
    var pendingRootSelectionHighlight by remember { mutableStateOf<PendingRootSelectionHighlight?>(null) }
    var visibleLookupPopupIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var rootSelectionHighlightRects by remember { mutableStateOf<List<ReaderSelectionRect>>(emptyList()) }
    val popupDarkMode = effectiveSettings.usesDarkInterface(systemDarkTheme)
    val themedLookupPopups = remember(lookupPopups, popupDarkMode, effectiveSettings.eInkMode, audioSettings) {
        lookupPopups.withLookupPopupVisualOptions(
            darkMode = popupDarkMode,
            eInkMode = effectiveSettings.eInkMode,
            audioSettings = audioSettings,
        )
    }
    val showReaderMenu = stateHolder.showReaderMenu
    val showAppearance = stateHolder.showAppearance
    val showChapters = stateHolder.showChapters
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
        visibleLookupPopupIds = visibleLookupPopupIds.intersect(nextPopups.mapTo(mutableSetOf()) { it.id })
        stateHolder.setLookupPopups(nextPopups, ::resumeSasayakiAfterLookupIfNeeded)
    }
    fun dismissRootLookupPopup() {
        pendingRootSelectionHighlight = null
        visibleLookupPopupIds = emptySet()
        rootSelectionHighlightRects = emptyList()
        clearReaderSelection()
        setLookupPopups(emptyList())
    }
    fun closeLookupPopupsAndSelection() {
        pendingRootSelectionHighlight = null
        visibleLookupPopupIds = emptySet()
        rootSelectionHighlightRects = emptyList()
        if (lookupPopups.isNotEmpty()) {
            clearReaderSelection()
            setLookupPopups(emptyList())
        } else {
            setLookupPopups(emptyList())
        }
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
        startStatisticsForProgressChangeIfNeeded()
        val next = stateHolder.goToNextChapter(book.chapters.lastIndex)
        if (next != null) {
            recordStatisticsAtDisplayedPosition()
            saveReaderPosition(next)
            return true
        }
        return false
    }
    fun goToPreviousChapter(): Boolean {
        startStatisticsForProgressChangeIfNeeded()
        val previous = stateHolder.goToPreviousChapter()
        if (previous != null) {
            recordStatisticsAtDisplayedPosition()
            saveReaderPosition(previous)
            return true
        }
        return false
    }
    fun saveDisplayedProgress(progress: Double) {
        startStatisticsForProgressChangeIfNeeded()
        val savedPosition = stateHolder.recordDisplayedProgress(progress)
        recordStatisticsAtDisplayedPosition()
        saveReaderPosition(savedPosition)
    }
    fun displayPagedTurnProgress(progress: Double) {
        startStatisticsForProgressChangeIfNeeded()
        stateHolder.recordDisplayedProgress(progress)
        recordStatisticsAtDisplayedPosition()
    }
    fun saveContinuousScrollProgress(progress: Double, restoreEpoch: Int) {
        startStatisticsForProgressChangeIfNeeded()
        val savedPosition = stateHolder.recordContinuousScrollProgress(progress, restoreEpoch) ?: return
        recordStatisticsAtDisplayedPosition()
        saveReaderPosition(savedPosition)
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
    val handleTextSelected: (ReaderSelectionData, (Int, (List<ReaderSelectionRect>) -> Unit) -> Unit) -> Unit = { selection, selectionRects ->
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
    val chromeState = remember(book, readerPosition.displayedPosition, statisticsState) {
        ReaderChromeState(
            title = book.title,
            currentCharacter = book.characterCountAt(readerPosition.displayedPosition.index, readerPosition.displayedPosition.progress),
            totalCharacters = book.bookInfo.characterCount,
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
    DisposableEffect(context, view, focusMode) {
        val activity = context.findActivity()
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        }
        if (focusMode) {
            controller?.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.statusBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    val bottomChromeMetrics = readerBottomChromeMetrics()
    val stableStatusBarPadding = rememberStableStatusBarPadding()
    val sasayakiBottomSkipButtons = readerSasayakiBottomSkipButtons(
        settings = sasayakiSettings,
        hasAudio = sasayakiPlayer?.hasAudio == true,
        metrics = bottomChromeMetrics,
    )
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
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(effectiveSettings.backgroundColor(systemDarkTheme))),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = stableStatusBarPadding)
                .navigationBarsPadding()
                .padding(
                    top = chromeLayout.topWebViewPaddingDp.dp,
                    bottom = bottomChromeMetrics.webViewBottomPaddingDp.dp,
                ),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val viewportHorizontalPadding = maxWidth * effectiveSettings.continuousViewportHorizontalPaddingRatio.toFloat()
                val viewportVerticalPadding = maxHeight * effectiveSettings.continuousViewportVerticalPaddingRatio.toFloat()
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
                        val statistics = statisticsForSave()
                        val savedPosition = stateHolder.jumpTo(target.position, target.fragment)
                        resetStatisticsBaseline()
                        saveReaderPosition(savedPosition, statistics)
                    },
                    scanNonJapaneseText = dictionarySettings.scanNonJapaneseText,
                    readerSettings = effectiveSettings,
                    sasayakiTextColor = sasayakiSettings.textColor(effectiveSettings.usesDarkInterface(systemDarkTheme)),
                    sasayakiBackgroundColor = sasayakiSettings.backgroundColor(effectiveSettings.usesDarkInterface(systemDarkTheme)),
                    onTextSelected = handleTextSelected,
                    onClearLookupPopup = ::closeLookupPopupsAndSelection,
                    fontManager = fontManager,
                    systemDark = systemDarkTheme,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = viewportHorizontalPadding,
                            vertical = viewportVerticalPadding,
                        ),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = viewportHorizontalPadding,
                            vertical = viewportVerticalPadding,
                        ),
                ) {
                    RootSelectionHighlightOverlay(
                        rects = rootSelectionHighlightRects,
                        darkMode = popupDarkMode,
                        eInkMode = effectiveSettings.eInkMode,
                        verticalWriting = effectiveSettings.verticalWriting,
                    )
                }
            }
            LookupPopupStackView(
                popups = themedLookupPopups,
                onPopupsChange = ::setLookupPopups,
                lookupChildPopup = ::lookupChildPopup,
                onRootPopupDismissed = {
                    dismissRootLookupPopup()
                    true
                },
                isPopupVisible = { popup, index -> index != 0 || popup.id in visibleLookupPopupIds },
                onPopupContentReady = ::markRootPopupContentReady,
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
                modifier = Modifier.fillMaxSize(),
            )
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
            onSasayakiToggle = onSasayakiTopToggle,
            sasayakiPlaying = sasayakiPlayer?.isPlaying == true || sasayakiWasPausedByLookup,
            focusMode = focusMode,
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
            onToggleFocusMode = stateHolder::toggleFocusMode,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (!focusMode) ReaderBottomChrome(
            state = chromeState,
            settings = effectiveSettings,
            layout = chromeLayout,
            colors = readerChromeColors(effectiveSettings, systemDarkTheme),
            onClose = ::closeReader,
            onMenu = stateHolder::toggleReaderMenu,
            menuExpanded = showReaderMenu,
            onDismissMenu = stateHolder::dismissReaderMenu,
            onChapters = stateHolder::openChaptersFromMenu,
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
            onSasayakiSkipBackward = { sasayakiPlayer?.previousCue() },
            onSasayakiSkipForward = { sasayakiPlayer?.nextCue() },
            metrics = bottomChromeMetrics,
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
                onJump = { target ->
                    closeLookupPopupsAndSelection()
                    val statistics = statisticsForSave()
                    val savedPosition = stateHolder.jumpTo(target)
                    resetStatisticsBaseline()
                    saveReaderPosition(savedPosition, statistics)
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
        webView?.let { _ -> Unit }
    }
}

@Composable
private fun RootSelectionHighlightOverlay(
    rects: List<ReaderSelectionRect>,
    darkMode: Boolean,
    eInkMode: Boolean,
    verticalWriting: Boolean,
) {
    val blendMode = if (darkMode) BlendMode.Screen else BlendMode.Multiply
    val underlineColor = if (darkMode) Color.White else Color.Black
    rects.forEachIndexed { index, rect ->
        if (rect.width <= 0.0 || rect.height <= 0.0) return@forEachIndexed
        Box(
            modifier = Modifier
                .absoluteOffset(x = rect.x.dp, y = rect.y.dp)
                .width(rect.width.dp)
                .height(rect.height.dp)
                .drawBehind {
                    if (eInkMode) {
                        val lineHeight = 1.5.dp.toPx()
                        if (verticalWriting) {
                            val lineLeft = (size.width - 2.dp.toPx() - lineHeight).coerceAtLeast(0f)
                            drawRect(
                                color = underlineColor,
                                topLeft = Offset(lineLeft, 0f),
                                size = Size(lineHeight, size.height),
                            )
                        } else {
                            val lineTop = (size.height - 2.dp.toPx()).coerceAtLeast(0f)
                            drawRect(
                                color = underlineColor,
                                topLeft = Offset(0f, lineTop),
                                size = Size(size.width, lineHeight),
                            )
                        }
                    } else {
                        drawRect(
                            color = Color(0x66A0A0A0),
                            blendMode = blendMode,
                        )
                    }
                }
                .zIndex(1f + index * 0.001f),
        )
    }
}

@Composable
private fun ReaderTopInfo(
    state: ReaderChromeState,
    settings: ReaderSettings,
    colors: ReaderChromeColors,
    onStatisticsToggle: (() -> Unit)?,
    statisticsTracking: Boolean,
    onSasayakiToggle: (() -> Unit)?,
    sasayakiPlaying: Boolean,
    focusMode: Boolean,
    metrics: ReaderBottomChromeMetrics,
    modifier: Modifier = Modifier,
) {
    val progress = state.progressText(settings)
    if ((focusMode || !settings.showTitle) &&
        onStatisticsToggle == null &&
        onSasayakiToggle == null &&
        (focusMode || progress.isBlank() || !settings.showProgressTop)
    ) return
    Box(modifier = modifier.fillMaxWidth()) {
        val titlePadding = readerTopTitlePaddingDp(
            hasStartControl = onStatisticsToggle != null,
            hasEndControl = onSasayakiToggle != null,
        )
        Column(
            modifier = Modifier.align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (!focusMode && settings.showTitle) {
                Text(
                    text = state.title,
                    color = Color(colors.infoText),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.padding(
                        start = titlePadding.startDp.dp,
                        end = titlePadding.endDp.dp,
                    ),
                )
            }
            if (!focusMode && settings.showProgressTop && progress.isNotBlank()) {
                Text(
                    text = progress,
                    color = Color(colors.infoText),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (onStatisticsToggle != null) {
            ReaderRoundButton(
                colors = colors,
                sizeDp = metrics.topStatisticsButtonSizeDp,
                onClick = onStatisticsToggle,
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Icon(
                    imageVector = readerStatisticsTopToggleIcon(statisticsTracking),
                    contentDescription = if (statisticsTracking) "Pause statistics" else "Start statistics",
                    modifier = Modifier.size(metrics.topStatisticsIconSizeDp.dp),
                    tint = Color(colors.buttonContent),
                )
            }
        }
        if (onSasayakiToggle != null) {
            ReaderRoundButton(
                colors = colors,
                sizeDp = metrics.topSasayakiButtonSizeDp,
                onClick = onSasayakiToggle,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    imageVector = readerSasayakiTopToggleIcon(sasayakiPlaying),
                    contentDescription = if (sasayakiPlaying) "Pause Sasayaki" else "Play Sasayaki",
                    modifier = Modifier.size(metrics.topSasayakiIconSizeDp.dp),
                    tint = Color(colors.buttonContent),
                )
            }
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
            .navigationBarsPadding()
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
    onAppearance: () -> Unit,
    onStatistics: (() -> Unit)?,
    onSasayaki: (() -> Unit)?,
    sasayakiSkipButtons: ReaderSasayakiBottomSkipButtons,
    onSasayakiSkipBackward: () -> Unit,
    onSasayakiSkipForward: () -> Unit,
    metrics: ReaderBottomChromeMetrics,
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
            onAppearance = onAppearance,
            onStatistics = onStatistics,
            onSasayaki = onSasayaki,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = metrics.horizontalPaddingDp.dp, bottom = metrics.menuBottomPaddingDp.dp),
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
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
                    contentDescription = "Back",
                    modifier = Modifier.size(metrics.primaryIconSizeDp.dp),
                    tint = Color(colors.buttonContent),
                )
            }
            if (sasayakiSkipButtons.visible) {
                Spacer(Modifier.width(sasayakiSkipButtons.adjacentSpacingDp.dp))
                ReaderGlassButton(colors = colors, metrics = metrics, onClick = onSasayakiSkipBackward) {
                    Icon(
                        imageVector = Icons.Rounded.FastRewind,
                        contentDescription = "Sasayaki Rewind",
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
                        contentDescription = "Sasayaki Fast-forward",
                        modifier = Modifier.size(sasayakiSkipButtons.iconSizeDp.dp),
                        tint = Color(colors.buttonContent),
                    )
                }
                Spacer(Modifier.width(sasayakiSkipButtons.adjacentSpacingDp.dp))
            }
            ReaderGlassButton(colors = colors, metrics = metrics, onClick = onMenu) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = "Reader Menu",
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
                text = "Chapters",
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
                text = "Appearance",
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
                    text = "Statistics",
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
                    text = "Sasayaki",
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
    onContinuousScrollProgress: (progress: Double, restoreEpoch: Int) -> Unit,
    onInternalLink: (ReaderInternalLinkTarget) -> Unit,
    scanNonJapaneseText: Boolean,
    readerSettings: ReaderSettings,
    sasayakiTextColor: Long,
    sasayakiBackgroundColor: Long,
    onTextSelected: (ReaderSelectionData, selectionRects: (Int, (List<ReaderSelectionRect>) -> Unit) -> Unit) -> Unit,
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
                    continuousScrollSaveRequestId += 1L
                    val requestId = continuousScrollSaveRequestId
                    readerPendingProgressSaveCallbacks.remove(webView)?.let(webView::removeCallbacks)
                    currentOnClearLookupPopup.value()
                    webView.evaluateJavascript(ReaderPaginationScripts.progressInvocation()) { progressResult ->
                        if (continuousScrollSaveRequestId != requestId) return@evaluateJavascript
                        ReaderPaginationScripts.doubleResult(progressResult)?.let { progress ->
                            when (readerProgressPersistenceAction(ReaderProgressPersistenceEvent.ContinuousScrollChanged)) {
                                ReaderProgressPersistenceAction.DisplayOnly -> currentOnDisplayProgress.value(progress)
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
            if (!readerWebViewReadyToLoad(webViewViewportSize)) return@AndroidView
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

internal fun readerWebViewReadyToLoad(webViewViewportSize: IntSize): Boolean =
    webViewViewportSize != IntSize.Zero

internal fun readerShouldReserveSasayakiTopToggle(bookRoot: File?, settings: SasayakiSettings): Boolean =
    settings.enabled &&
        settings.showReaderToggle &&
        bookRoot?.resolve(ReaderSasayakiMatchFileName)?.isFile == true &&
        bookRoot.resolve(ReaderSasayakiPlaybackFileName).isFile

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
