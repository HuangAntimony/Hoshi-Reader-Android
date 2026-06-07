package moe.antimony.hoshi.features.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.webkit.WebView
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.HighlightColor
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.ReaderHighlight
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.features.audio.AudioRequestHandler
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.audio.WordAudioPlayer
import moe.antimony.hoshi.features.anki.AnkiViewModel
import moe.antimony.hoshi.features.dictionary.DictionaryImageRequestHandler
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.dictionary.LookupPopupAssets
import moe.antimony.hoshi.features.dictionary.LookupPopupHtml
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupOptions
import moe.antimony.hoshi.features.dictionary.closeChildPopupsAndClearSelection
import moe.antimony.hoshi.features.dictionary.closeChildPopupsForScrolledParent
import moe.antimony.hoshi.features.dictionary.clearPopupSelectionHighlights
import moe.antimony.hoshi.features.dictionary.createLookupPopupItem
import moe.antimony.hoshi.features.dictionary.dismissPopupAt
import moe.antimony.hoshi.features.dictionary.openPopupExternalLink
import moe.antimony.hoshi.features.dictionary.withLookupPopupVisualOptions
import moe.antimony.hoshi.features.sasayaki.BookSasayakiPlaybackRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiAudioRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRange
import moe.antimony.hoshi.features.sasayaki.SasayakiPlayer
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.features.sasayaki.SasayakiSheet
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderWebView(
    book: EpubBook,
    bookRoot: File? = null,
    bookCoverFile: File? = null,
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
    val appContainer = LocalHoshiUiDependencies.current
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
    val sasayakiCoverFile = remember(bookCoverFile) {
        bookCoverFile?.takeIf { it.isFile }
    }
    var sasayakiPlayer by remember { mutableStateOf<SasayakiPlayer?>(null) }
    var pendingSasayakiCue by remember(book) { mutableStateOf<PendingSasayakiCue?>(null) }
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
    var readerPopupHistories by remember { mutableStateOf<Map<String, ReaderPopupHistoryCounts>>(emptyMap()) }
    var rootSelectionHighlight by remember { mutableStateOf<ReaderRootSelectionHighlight?>(null) }
    var fullscreenImage by remember { mutableStateOf<ReaderFullscreenImage?>(null) }
    val ankiViewModel: AnkiViewModel = hiltViewModel()
    val ankiUiState by ankiViewModel.uiState.collectAsStateWithLifecycle()
    val popupAssets = remember(context) { LookupPopupAssets.load(context) }
    val readerPopupBridgeHolder = remember { ReaderLookupPopupBridgeCallbackHolder() }
    val popupDarkMode = effectiveSettings.usesDarkInterface(systemDarkTheme)
    val readerPopupIframeDocument = remember(
        dictionaryStyles,
        dictionarySettings,
        effectiveSettings.popupSwipeToDismiss,
        effectiveSettings.popupSwipeThreshold,
        effectiveSettings.popupReducedMotionScrolling,
        effectiveSettings.popupReducedMotionScrollPercent,
        effectiveSettings.popupReducedMotionSwipeThreshold,
        popupDarkMode,
        effectiveSettings.eInkMode,
        audioSettings,
        ankiUiState.popupSettings,
        fontManager,
        effectiveSettings.popupScale,
    ) {
        LookupPopupHtml.renderIframeDocument(
            assets = null,
            dictionaryStyles = dictionaryStyles,
            settings = dictionarySettings,
            swipeToDismiss = effectiveSettings.popupSwipeToDismiss,
            swipeThreshold = effectiveSettings.popupSwipeThreshold,
            reducedMotionScrolling = effectiveSettings.popupReducedMotionScrolling,
            reducedMotionScrollPercent = effectiveSettings.popupReducedMotionScrollPercent,
            reducedMotionSwipeThreshold = effectiveSettings.popupReducedMotionSwipeThreshold,
            darkMode = popupDarkMode,
            eInkMode = effectiveSettings.eInkMode,
            audioSettings = audioSettings,
            ankiSettings = ankiUiState.popupSettings,
            fontFaceCss = fontManager.popupFontFaceCss(),
            popupScale = effectiveSettings.popupScale,
        )
    }
    val currentReaderPopupIframeDocument = rememberUpdatedState(readerPopupIframeDocument)
    val readerPopupIframeUrl = remember(readerPopupIframeDocument) {
        readerLookupPopupIframeUrl(readerPopupIframeDocument.hashCode())
    }
    LaunchedEffect(webView, readerPopupIframeUrl) {
        webView?.evaluateJavascript(
            """
                (function() {
                  var iframeUrl = ${readerJavaScriptStringLiteral(readerPopupIframeUrl)};
                  window.__hoshiReaderPopupIframeUrl = iframeUrl;
                  if (window.hoshiReaderPopupHost) {
                    window.hoshiReaderPopupHost.preloadIdleRootFrame(iframeUrl);
                  }
                })();
            """.trimIndent(),
            null,
        )
    }
    val readerPopupResourceHandler = remember(context, popupAssets, fontManager) {
        ReaderLookupPopupResourceHandler(
            context = context.applicationContext,
            assets = popupAssets,
            fontManager = fontManager,
            audioRequestHandler = AudioRequestHandler(LocalAudioRepository.fromContext(context.applicationContext)),
            imageRequestHandler = DictionaryImageRequestHandler(),
            iframeDocument = { currentReaderPopupIframeDocument.value },
        )
    }
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
        val activeIds = nextPopups.mapTo(mutableSetOf()) { it.id }
        readerPopupHistories = readerPopupHistories.filterKeys(activeIds::contains)
        rootSelectionHighlight = rootSelectionHighlight?.takeIf { highlight ->
            highlight.popupId == null || highlight.popupId in activeIds
        }
        stateHolder.setLookupPopups(nextPopups, ::resumeSasayakiAfterLookupIfNeeded)
    }
    fun dismissRootLookupPopup() {
        rootSelectionHighlight = null
        setLookupPopups(clearPopupSelectionHighlights(stateHolder.lookupPopups))
        clearReaderSelection()
        setLookupPopups(emptyList())
    }
    fun closeLookupPopupsAndSelection() {
        rootSelectionHighlight = null
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
        startStatisticsForProgressChangeIfNeeded()
        stateHolder.recordContinuousScrollDisplayProgress(progress, restoreEpoch) ?: return
        stateHolder.clearForwardHistoryAfterManualMovement()
        recordStatisticsAtDisplayedPosition()
    }
    fun saveContinuousScrollProgress(progress: Double, restoreEpoch: Int) {
        startStatisticsForProgressChangeIfNeeded()
        val savedPosition = stateHolder.recordContinuousScrollProgress(progress, restoreEpoch) ?: return
        stateHolder.clearForwardHistoryAfterManualMovement()
        recordStatisticsAtDisplayedPosition()
        saveReaderPosition(savedPosition)
    }
    fun navigateReaderPage(direction: ReaderNavigationDirection): Boolean {
        val currentWebView = webView ?: return false
        if (!stateHolder.beginReaderNavigationInput()) return false
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
    fun replyReaderPopupMessage(popupId: String, messageId: String, bodyJson: String) {
        webView?.evaluateJavascript(
            """
                window.hoshiReaderPopupHost &&
                window.hoshiReaderPopupHost.resolveMessage(${readerJavaScriptStringLiteral(popupId)}, ${readerJavaScriptStringLiteral(messageId)}, $bodyJson)
            """.trimIndent(),
            null,
        )
    }
    fun highlightReaderPopupSelection(popupId: String, highlightCount: Int) {
        webView?.evaluateJavascript(
            """
                window.hoshiReaderPopupHost &&
                window.hoshiReaderPopupHost.highlightSelection(${readerJavaScriptStringLiteral(popupId)}, $highlightCount)
            """.trimIndent(),
            null,
        )
    }
    fun popupIndex(popupId: String): Int =
        stateHolder.lookupPopups.indexOfFirst { it.id == popupId }

    fun popupById(popupId: String): LookupPopupItem? =
        stateHolder.lookupPopups.firstOrNull { it.id == popupId }

    fun handleReaderPopupBridgeMessage(message: ReaderLookupPopupBridgeMessage) {
        when (message) {
            is ReaderLookupPopupBridgeMessage.OpenLink -> context.openPopupExternalLink(message.url)
            is ReaderLookupPopupBridgeMessage.TapOutside -> {
                val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                setLookupPopups(closeChildPopupsAndClearSelection(stateHolder.lookupPopups, index))
            }
            is ReaderLookupPopupBridgeMessage.SwipeDismiss -> {
                val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                if (index == 0) {
                    dismissRootLookupPopup()
                } else {
                    setLookupPopups(dismissPopupAt(stateHolder.lookupPopups, index))
                }
            }
            is ReaderLookupPopupBridgeMessage.TextSelected -> {
                val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                val nextPopups = stateHolder.lookupPopups.take(index + 1)
                val lookup = lookupChildPopup(message.selection)
                if (lookup == null) {
                    highlightReaderPopupSelection(message.popupId, 0)
                    return
                }
                val (childPopup, highlightCount) = lookup
                setLookupPopups(nextPopups + childPopup)
                highlightReaderPopupSelection(message.popupId, highlightCount)
            }
            is ReaderLookupPopupBridgeMessage.PlayWordAudio -> {
                WordAudioPlayer.get(context).play(message.url, message.mode)
            }
            is ReaderLookupPopupBridgeMessage.MineEntry -> {
                val popup = popupById(message.popupId) ?: return
                val messageId = message.messageId ?: return
                val ankiContext = popup.sasayakiCue?.takeIf { ankiUiState.popupSettings.needsSasayakiAudio }?.let { cue ->
                    popup.state.ankiContext.copy(
                        sasayakiAudioPath = sasayakiPlayer?.exportCueAudio(cue, popup.state.selection.sentence)?.absolutePath,
                    )
                } ?: popup.state.ankiContext
                ankiViewModel.mineEntryAsync(message.payloadJson, ankiContext) { mined ->
                    replyReaderPopupMessage(message.popupId, messageId, mined.toString())
                }
            }
            is ReaderLookupPopupBridgeMessage.DuplicateCheck -> {
                ankiViewModel.duplicateCheckAsync(message.expression) { isDuplicate ->
                    replyReaderPopupMessage(message.popupId, message.messageId ?: return@duplicateCheckAsync, isDuplicate.toString())
                }
            }
            is ReaderLookupPopupBridgeMessage.LookupRedirect -> {
                val popup = popupById(message.popupId) ?: return
                val results = dictionaryRepository.lookup(
                    message.query,
                    popup.state.dictionarySettings.maxResults,
                    popup.state.dictionarySettings.scanLength,
                )
                if (results.isNotEmpty()) {
                    setLookupPopups(
                        stateHolder.lookupPopups.map { existing ->
                            if (existing.id == message.popupId) {
                                existing.copy(state = existing.state.copy(results = results))
                            } else {
                                existing
                            }
                        },
                    )
                    val current = readerPopupHistories[message.popupId] ?: ReaderPopupHistoryCounts()
                    readerPopupHistories = readerPopupHistories + (
                        message.popupId to current.copy(
                            backCount = current.backCount + 1,
                            forwardCount = 0,
                        )
                        )
                }
                replyReaderPopupMessage(message.popupId, message.messageId ?: return, results.size.toString())
            }
            is ReaderLookupPopupBridgeMessage.GetEntry -> {
                val entry = popupById(message.popupId)?.state?.results?.getOrNull(message.index)
                val body = entry?.let(LookupPopupHtml::entryJsonString) ?: "null"
                replyReaderPopupMessage(message.popupId, message.messageId ?: return, body)
            }
            is ReaderLookupPopupBridgeMessage.PopupScrolled -> {
                val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                setLookupPopups(closeChildPopupsForScrolledParent(stateHolder.lookupPopups, index))
            }
            is ReaderLookupPopupBridgeMessage.ScrollState -> Unit
            is ReaderLookupPopupBridgeMessage.NavigateBack -> {
                val current = readerPopupHistories[message.popupId] ?: return
                if (current.backCount > 0) {
                    readerPopupHistories = readerPopupHistories + (
                        message.popupId to current.copy(
                            backCount = current.backCount - 1,
                            forwardCount = current.forwardCount + 1,
                        )
                        )
                }
            }
            is ReaderLookupPopupBridgeMessage.NavigateForward -> {
                val current = readerPopupHistories[message.popupId] ?: return
                if (current.forwardCount > 0) {
                    readerPopupHistories = readerPopupHistories + (
                        message.popupId to current.copy(
                            backCount = current.backCount + 1,
                            forwardCount = current.forwardCount - 1,
                        )
                        )
                }
            }
            is ReaderLookupPopupBridgeMessage.ContentReady -> Unit
            is ReaderLookupPopupBridgeMessage.SasayakiReplayCue -> {
                popupById(message.popupId)?.sasayakiCue?.let { cue ->
                    WordAudioPlayer.get(context).stop()
                    sasayakiPlayer?.playCue(cue, stop = true)
                }
            }
            is ReaderLookupPopupBridgeMessage.SasayakiTogglePlayback -> {
                WordAudioPlayer.get(context).stop()
                if (sasayakiWasPausedByLookup) {
                    stateHolder.clearSasayakiPauseState()
                } else {
                    sasayakiPlayer?.togglePlayback()
                }
            }
            is ReaderLookupPopupBridgeMessage.SasayakiPlayForward -> {
                popupById(message.popupId)?.sasayakiCue?.let { cue ->
                    WordAudioPlayer.get(context).stop()
                    sasayakiPlayer?.playCue(cue, stop = false)
                    closeLookupPopupsAndSelection()
                }
            }
        }
    }
    readerPopupBridgeHolder.callbacks = ReaderLookupPopupBridgeCallbacks(::handleReaderPopupBridgeMessage)
    val handleTextSelected: (ReaderSelectionData, (Int, (List<ReaderSelectionRect>) -> Unit) -> Unit) -> Unit = { selection, selectionRects ->
        stateHolder.enterFocusModeForReaderInteraction()
        rootSelectionHighlight = null
        setLookupPopups(emptyList())
        val lookup = lookupRootPopup(selection)
        if (lookup != null) {
            val (popup, highlightCount) = lookup
            pauseSasayakiForLookupIfNeeded()
            val selectionCount = onTextSelected(selection) ?: highlightCount
            rootSelectionHighlight = ReaderRootSelectionHighlight(
                popupId = popup.id,
                rects = null,
            )
            setLookupPopups(listOf(popup))
            selectionRects(selectionCount) { rects ->
                if (stateHolder.lookupPopups.none { it.id == popup.id }) return@selectionRects
                val displayRects = rects.ifEmpty { listOf(popup.state.selection.rect) }
                val anchor = displayRects.firstOrNull()
                if (anchor != null) {
                    setLookupPopups(
                        stateHolder.lookupPopups.map { existing ->
                            if (existing.id == popup.id) {
                                existing.copy(
                                    state = existing.state.copy(
                                        selection = existing.state.selection.copy(rect = anchor),
                                    ),
                                )
                            } else {
                                existing
                            }
                        },
                    )
                }
                rootSelectionHighlight = ReaderRootSelectionHighlight(
                    popupId = popup.id,
                    rects = displayRects,
                )
            }
        } else {
            onTextSelected(selection)?.let { count ->
                selectionRects(count) { rects ->
                    rootSelectionHighlight = ReaderRootSelectionHighlight(
                        popupId = null,
                        rects = rects,
                    )
                }
            }
        }
    }
    fun handleReaderTapOutside() {
        if (stateHolder.lookupPopups.isEmpty()) {
            rootSelectionHighlight = null
            clearReaderSelection {
                stateHolder.toggleFocusModeFromReaderTap(hasVisiblePopups = false)
            }
        } else {
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
    fun dispatchSasayakiCueToReader(cue: SasayakiMatch, reveal: Boolean) {
        val targetWebView = webView
        if (targetWebView == null || stateHolder.isWebViewRestoring) {
            pendingSasayakiCue = PendingSasayakiCue(cue = cue, reveal = reveal)
            return
        }
        pendingSasayakiCue = null
        targetWebView.evaluateJavascript(
            ReaderPaginationScripts.highlightSasayakiCueInvocation(cue.toCueRange(), reveal),
        ) { progressResult ->
            ReaderPaginationScripts.doubleResult(progressResult)?.let { progress ->
                startStatisticsForProgressChangeIfNeeded()
                val savedPosition = stateHolder.recordDisplayedProgress(progress)
                recordStatisticsAtDisplayedPosition()
                saveReaderPosition(savedPosition)
            }
        }
    }
    LaunchedEffect(
        webView,
        stateHolder.isWebViewRestoring,
        readerPosition.displayedPosition.index,
        pendingSasayakiCue,
    ) {
        val pending = pendingSasayakiCue ?: return@LaunchedEffect
        if (stateHolder.isWebViewRestoring || webView == null) return@LaunchedEffect
        if (pending.cue.chapterIndex != readerPosition.displayedPosition.index) {
            pendingSasayakiCue = null
            return@LaunchedEffect
        }
        dispatchSasayakiCueToReader(pending.cue, pending.reveal)
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
                    dispatchSasayakiCueToReader(cue, reveal)
                },
                onClearCue = {
                    pendingSasayakiCue = null
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

    BackHandler {
        if (stateHolder.handleBackNavigation()) {
            closeReader()
        }
    }
    val useDarkSystemBarIcons = effectiveSettings.usesDarkSystemBarIcons(systemDarkTheme)
    DisposableEffect(context, view, useDarkSystemBarIcons) {
        val activity = context.findActivity()
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        }
        controller?.isAppearanceLightStatusBars = useDarkSystemBarIcons
        controller?.isAppearanceLightNavigationBars = useDarkSystemBarIcons
        onDispose {
            controller?.isAppearanceLightStatusBars = useDarkSystemBarIcons
            controller?.isAppearanceLightNavigationBars = useDarkSystemBarIcons
        }
    }
    DisposableEffect(context, view) {
        val activity = context.findActivity()
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        }
        val previousBehavior = controller?.systemBarsBehavior
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            if (previousBehavior != null) {
                controller.systemBarsBehavior = previousBehavior
            }
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(context, view, focusMode) {
        val activity = context.findActivity()
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        } ?: return@LaunchedEffect
        val visibility = readerSystemBarVisibility(focusMode)
        if (visibility.showStatusBar) {
            controller.show(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
        if (visibility.showNavigationBar) {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    val bottomChromeMetrics = readerBottomChromeMetrics()
    val currentStatusBarPadding = rememberCurrentStatusBarPadding()
    val stableStatusBarPadding = rememberStableStatusBarPadding()
    val stableNavigationBarPadding = rememberStableNavigationBarPadding()
    val currentStatusBarPaddingDp = currentStatusBarPadding.value.roundToInt().coerceAtLeast(0)
    val stableStatusBarPaddingDp = stableStatusBarPadding.value.roundToInt().coerceAtLeast(0)
    val sasayakiBottomPlaybackControls = readerSasayakiBottomPlaybackControls(
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
    val contentChromeInsets = readerContentChromeInsets(
        state = chromeState,
        settings = effectiveSettings,
        showSasayakiToggle = reserveSasayakiTopToggle || showSasayakiTopToggle,
        showStatisticsToggle = effectiveSettings.enableStatistics && effectiveSettings.showStatisticsToggle,
        focusMode = focusMode,
        topSystemInsetDp = stableStatusBarPadding.value.roundToInt().coerceAtLeast(0),
    )
    val topInfoPadding = readerTopInfoOverlayPaddingDp(
        topSystemInsetDp = currentStatusBarPaddingDp,
        focusMode = focusMode,
    ).dp
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
    val topInfoVisibility = chromeVisibility.copy(
        showTitleAndProgress = chromeVisibility.showTitleAndProgress &&
            readerShouldShowTitleAndProgress(
                focusMode = focusMode,
                currentStatusBarInsetDp = currentStatusBarPaddingDp,
                stableStatusBarInsetDp = stableStatusBarPaddingDp,
            ),
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(effectiveSettings.backgroundColor(systemDarkTheme))),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = contentChromeInsets.topDp.dp,
                    bottom = contentChromeInsets.bottomDp.dp,
                ),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val viewportHorizontalPadding = maxWidth * effectiveSettings.continuousViewportHorizontalPaddingRatio.toFloat()
                val viewportVerticalPadding = maxHeight * effectiveSettings.continuousViewportVerticalPaddingRatio.toFloat()
                val readerLookupPopupViewport = ReaderLookupPopupViewport(
                    width = (maxWidth.value - viewportHorizontalPadding.value * 2f).coerceAtLeast(0f).toDouble(),
                    height = (maxHeight.value - viewportVerticalPadding.value * 2f).coerceAtLeast(0f).toDouble(),
                )
                val readerRootSelectionHighlightPayload = remember(
                    rootSelectionHighlight,
                    popupDarkMode,
                    effectiveSettings.eInkMode,
                    effectiveSettings.verticalWriting,
                ) {
                    rootSelectionHighlight?.let { highlight ->
                        ReaderLookupPopupRootHighlightPayload.fromReaderRects(
                            popupId = highlight.popupId,
                            rects = highlight.rects,
                            darkMode = popupDarkMode,
                            eInkMode = effectiveSettings.eInkMode,
                            verticalWriting = effectiveSettings.verticalWriting,
                        )
                    }
                }
                val readerLookupPopupPayloads = remember(
                    themedLookupPopups,
                    readerPopupHistories,
                    readerLookupPopupViewport,
                    sasayakiWasPausedByLookup,
                    sasayakiPlayer?.isPlaying == true,
                    readerPopupIframeUrl,
                    rootSelectionHighlight,
                ) {
                    readerLookupPopupFramePayloads(
                        popups = themedLookupPopups,
                        histories = readerPopupHistories,
                        viewport = readerLookupPopupViewport,
                        sasayakiWasPaused = sasayakiWasPausedByLookup,
                        sasayakiIsPlaying = sasayakiPlayer?.isPlaying == true,
                        iframeUrl = readerPopupIframeUrl,
                        rootSelectionHighlight = rootSelectionHighlight,
                    )
                }
                if (highlights != null) {
                    val loadChapter = currentLoadChapter()
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
                            highlights = highlights.orEmpty(),
                            bookInfo = book.bookInfo,
                            chapter = loadChapter,
                        ),
                        chapterSasayakiCuesJson = ReaderSasayakiCues.chapterCuesJson(
                            matchData = sasayakiMatchData,
                            chapterIndex = readerPosition.loadPosition.index,
                        ),
                        sasayakiTextColor = sasayakiSettings.textColor(effectiveSettings.usesDarkInterface(systemDarkTheme)),
                        sasayakiBackgroundColor = sasayakiSettings.backgroundColor(effectiveSettings.usesDarkInterface(systemDarkTheme)),
                        onTextSelected = handleTextSelected,
                        onClearLookupPopup = ::closeLookupPopupsAndSelection,
                        onReaderTapOutside = ::handleReaderTapOutside,
                        onReaderInteraction = stateHolder::enterFocusModeForReaderInteraction,
                        onImageTapped = ::openFullscreenImage,
                        onHighlightCreated = ::addHighlight,
                        readerPopupBridgeHolder = readerPopupBridgeHolder,
                        readerPopupResourceHandler = readerPopupResourceHandler,
                        readerPopupFrames = readerLookupPopupPayloads,
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
                ReaderLookupPopupIframeSync(
                    webView = webView,
                    payloads = readerLookupPopupPayloads,
                    rootHighlight = readerRootSelectionHighlightPayload,
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
            visibility = topInfoVisibility,
            metrics = bottomChromeMetrics,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topInfoPadding)
                .padding(horizontal = 15.dp),
        )
        ReaderFocusModeToggleArea(
            metrics = bottomChromeMetrics,
            focusMode = focusMode,
            onToggleFocusMode = ::handleReaderTapOutside,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        ReaderBottomSafeProgress(
            state = chromeState,
            settings = effectiveSettings,
            colors = readerChromeColors(effectiveSettings, systemDarkTheme),
            metrics = bottomChromeMetrics,
            focusMode = focusMode,
            sasayakiPlaybackControls = sasayakiBottomPlaybackControls,
            sasayakiPlaying = sasayakiPlayer?.isPlaying == true,
            onTapSafeArea = ::handleReaderTapOutside,
            onSasayakiSkipBackward = { performSasayakiBottomSkipAction(sasayakiBottomSkipButtonActions.left) },
            onSasayakiTogglePlayback = { sasayakiPlayer?.togglePlayback() },
            onSasayakiSkipForward = { performSasayakiBottomSkipAction(sasayakiBottomSkipButtonActions.right) },
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
                topSafeAreaPadding = stableStatusBarPadding,
                bottomSafeAreaPadding = stableNavigationBarPadding,
                onDismiss = { fullscreenImage = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        webView?.let { _ -> Unit }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun SasayakiMatch.toCueRange(): SasayakiCueRange =
    SasayakiCueRange(id = id, start = start, length = length)

private data class PendingSasayakiCue(
    val cue: SasayakiMatch,
    val reveal: Boolean,
)

private fun SasayakiPlaybackData?.hasStoredAudioSource(): Boolean =
    this?.audioUri?.isNotBlank() == true || this?.audioFileName?.isNotBlank() == true
