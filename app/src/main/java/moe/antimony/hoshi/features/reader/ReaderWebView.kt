package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.sync.SyncProvider
import moe.antimony.hoshi.features.sync.SyncBook
import moe.antimony.hoshi.features.sync.syncKey
import moe.antimony.hoshi.features.sync.resolveTtuCharacterPosition

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.webkit.WebView
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import moe.antimony.hoshi.ui.theme.LocalSystemDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import moe.antimony.hoshi.ui.HoshiAlertDialog as AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.features.settings.SettingsContent
import moe.antimony.hoshi.features.settings.SettingsLoadState
import moe.antimony.hoshi.features.settings.settingsLoadStates
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsUpdate
import moe.antimony.hoshi.features.tts.*
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDetailsText
import moe.antimony.hoshi.ui.withErrorDetails
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.HighlightColor
import moe.antimony.hoshi.epub.ReadingSessions
import moe.antimony.hoshi.epub.ReaderHighlight
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.audio.withLocalizedSourceNames
import moe.antimony.hoshi.features.audio.WordAudioPlayer
import moe.antimony.hoshi.features.anki.AnkiViewModel
import moe.antimony.hoshi.features.anki.AnkiOperationErrorDialog
import moe.antimony.hoshi.features.dictionary.DictionaryImageRequestHandler
import moe.antimony.hoshi.features.dictionary.lookupOptions
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
import moe.antimony.hoshi.features.display.DisplaySettingsSheet
import moe.antimony.hoshi.features.sasayaki.BookSasayakiPlaybackRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiAudioRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiAudiobookInfo
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRange
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRevealSource
import moe.antimony.hoshi.features.sasayaki.SasayakiPlayer
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.features.sasayaki.SasayakiSheet
import moe.antimony.hoshi.features.sasayaki.SasayakiTranscriptionViewModel
import moe.antimony.hoshi.features.sasayaki.SasayakiSubtitleExportHost
import moe.antimony.hoshi.features.sasayaki.SasayakiSubtitleExportViewModel
import moe.antimony.hoshi.features.sasayaki.rememberSasayakiTranscriptionState
import moe.antimony.hoshi.features.sasayaki.SasayakiMatchDependencies
import moe.antimony.hoshi.features.sasayaki.sasayakiDefaultSheetTab
import moe.antimony.hoshi.features.sasayaki.sasayakiImageHoldMillis
import kotlin.coroutines.resume
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderWebView(
    bookId: String,
    book: EpubBook,
    bookEntry: BookEntry? = null,
    bookRoot: File? = null,
    bookCoverFile: File? = null,
    initialChapterIndex: Int = 0,
    initialProgress: Double = 0.0,
    readerSettings: ReaderSettings = ReaderSettings(),
    onReaderSettingsChange: ((ReaderSettings) -> ReaderSettings) -> Unit = {},
    onReaderKeyEventHandlerChange: (((KeyEvent) -> Boolean)?) -> Unit = {},
    onSaveBookmark: (chapterIndex: Int, progress: Double, statistics: ReadingSessions?) -> Unit = { _, _, _ -> },
    onSaveStatistics: (ReadingSessions) -> Unit = {},
    onFlushBookmarkSaves: suspend () -> Unit = {},
    syncProvider: SyncProvider = SyncProvider.Gdrive,
    onFlushAutoSyncExport: () -> Unit = {},
    onForegroundAutoSyncImport: () -> Unit = {},
    onTextSelected: (ReaderSelectionData) -> Int? = { null },
    contentLanguageProfile: ContentLanguageProfile = ContentLanguageProfile.Default,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var bookDeleted by remember(bookRoot) { mutableStateOf(false) }
    var applyingBookmark by remember(bookRoot) { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current
    val appContainer = LocalHoshiUiDependencies.current
    val profileState by appContainer.profileRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val fontManager = appContainer.readerFontManager
    val fontLibraryState by fontManager.libraryState.collectAsStateWithLifecycle()
    val readerImageResourceBridge = remember(book, fontManager) {
        ReaderWebResourceBridge(book, fontManager)
    }
    val dictionaryRepository = appContainer.dictionaryRepository
    val dictionarySettingsRepository = appContainer.dictionarySettingsRepository
    val audioSettingsRepository = appContainer.audioSettingsRepository
    val sasayakiSettingsRepository = appContainer.sasayakiSettingsRepository
    val sasayakiPlaybackServiceRuntime = appContainer.sasayakiPlaybackServiceRuntime
    val statisticsDateProvider = appContainer.statisticsDateProvider
    val bookRepository = appContainer.bookRepository
    var sasayakiSettingsState by remember(sasayakiSettingsRepository) {
        mutableStateOf<SettingsLoadState<SasayakiSettings>>(SettingsLoadState.Loading)
    }
    var sasayakiLoadAttempt by remember { mutableIntStateOf(0) }
    var sasayakiSettings by remember(sasayakiSettingsRepository) { mutableStateOf(SasayakiSettings()) }
    var sasayakiMatchData by remember(bookRoot) { mutableStateOf<SasayakiMatchData?>(null) }
    var sasayakiSheetMatchData by remember(bookRoot) { mutableStateOf<SasayakiMatchData?>(null) }
    var isSasayakiMatchLoaded by remember(bookRoot) { mutableStateOf(bookRoot == null) }
    LaunchedEffect(bookRoot, bookRepository) {
        isSasayakiMatchLoaded = bookRoot == null
        val loadedMatch = bookRoot?.let { bookRepository.loadSasayakiMatch(it) }
        sasayakiMatchData = loadedMatch
        sasayakiSheetMatchData = loadedMatch
        isSasayakiMatchLoaded = true
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
    var sasayakiAudiobookInfo by remember(bookRoot) {
        mutableStateOf(SasayakiAudiobookInfo.Empty)
    }
    val sasayakiCoverFile = remember(bookCoverFile) {
        bookCoverFile?.takeIf { it.isFile }
    }
    var sasayakiPlayer by remember { mutableStateOf<SasayakiPlayer?>(null) }
    val ttsViewModel: TtsViewModel = hiltViewModel()
    val ttsLifetime: ReaderTtsLifetime = hiltViewModel()
    val ttsState by ttsViewModel.playback.state.collectAsStateWithLifecycle()
    val ttsPreferences by ttsViewModel.preferences.collectAsStateWithLifecycle()
    val ttsEnabled = ttsPreferences?.settings?.enabled == true
    val ttsBookId = bookRoot?.name ?: book.title
    ttsLifetime.bookId = ttsBookId
    val ttsAssociated = (sasayakiPlayer?.playback ?: sasayakiPlaybackData).hasStoredAudioSource() || sasayakiPlayer?.hasAudio == true
    val ttsAvailable = ttsEnabled && isSasayakiPlaybackLoaded && !ttsAssociated
    val hasStartedTtsSession = ttsAvailable && ttsState.bookId == ttsBookId && ttsState.started
    var showTts by remember(bookRoot) { mutableStateOf(false) }
    var ttsSelection by remember(bookRoot) { mutableStateOf<ReaderSelectionData?>(null) }
    var ankiOperationError by remember(bookRoot) { mutableStateOf<Throwable?>(null) }
    var ankiSynchronizationWarning by remember(bookRoot) { mutableStateOf<Throwable?>(null) }
    val ttsMiningJobs = remember { mutableMapOf<String, Job>() }
    var pendingSasayakiMatchUpdate by remember(bookRoot) { mutableStateOf<PendingSasayakiMatchUpdate?>(null) }
    var preserveSasayakiCueLayout by remember(bookRoot) { mutableStateOf(false) }
    val sasayakiTranscriptionViewModel: SasayakiTranscriptionViewModel = hiltViewModel()
    val sasayakiSubtitleExportViewModel: SasayakiSubtitleExportViewModel = hiltViewModel()
    SasayakiSubtitleExportHost(sasayakiSubtitleExportViewModel)
    val onSasayakiMatchUpdated: (SasayakiMatchData) -> Unit = { data ->
        sasayakiSheetMatchData = data
        pendingSasayakiMatchUpdate = PendingSasayakiMatchUpdate(data, preserveLayout = false)
    }
    val sasayakiTranscriptionState = rememberSasayakiTranscriptionState(
        root = bookRoot,
        audioRepository = sasayakiAudioRepository,
        playback = sasayakiPlayer?.playback,
        viewModel = sasayakiTranscriptionViewModel,
        matchSource = sasayakiSheetMatchData?.source,
        onMatchUpdated = { data ->
            sasayakiSheetMatchData = data
            pendingSasayakiMatchUpdate = PendingSasayakiMatchUpdate(data, preserveLayout = true)
        },
    )
    var lastSasayakiCue by remember(book) { mutableStateOf<PendingSasayakiCue?>(null) }
    var pendingSasayakiCue by remember(book) { mutableStateOf<PendingSasayakiCue?>(null) }
    var pendingSasayakiRestoreCue by remember(book) { mutableStateOf<PendingSasayakiCue?>(null) }
    var pendingReaderPlaybackTargetRestore by remember(book) {
        mutableStateOf<PendingReaderPlaybackTargetRestore?>(null)
    }
    val sasayakiMediaStopJson = remember { Json { ignoreUnknownKeys = true } }
    var sasayakiAutoPageJob by remember(book) { mutableStateOf<Job?>(null) }
    val view = LocalView.current
    val systemDarkTheme = LocalSystemDarkTheme.current
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
    val ttsLookupPause = stateHolder.ttsLookupPause
    LaunchedEffect(book, ttsBookId, isSasayakiPlaybackLoaded, ttsAssociated, ttsEnabled) {
        if (ttsEnabled && isSasayakiPlaybackLoaded) ttsViewModel.playback.load(ttsBookId, book, ttsAssociated)
        if (!ttsEnabled || ttsAssociated) {
            showTts = false
            ttsSelection = null
            ttsLookupPause.clear()
        }
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
    LaunchedEffect(sasayakiSettingsRepository, sasayakiLoadAttempt) {
        sasayakiSettingsState = SettingsLoadState.Loading
        sasayakiSettingsRepository.settings.settingsLoadStates().collect { state ->
            sasayakiSettingsState = state
            if (state is SettingsLoadState.Loaded) sasayakiSettings = state.value
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
    val popupContentLanguageProfile = contentLanguageProfile
    val progressDisplay = readerProgressDisplay(contentLanguageProfile)
    val noAudioFoundText = stringResource(R.string.audio_no_audio_found)
    val audioLoadingText = stringResource(R.string.loading)
    val popupAudioSettings = audioSettings.withLocalizedSourceNames()
    val readerPopupIframeDocument = remember(
        dictionaryStyles,
        dictionarySettings,
        effectiveSettings.popupSwipeToDismiss,
        effectiveSettings.popupSwipeThreshold,
        effectiveSettings.popupReducedMotionScrolling,
        effectiveSettings.popupReducedMotionScrollPercent,
        effectiveSettings.popupReducedMotionSwipeThreshold,
        popupAudioSettings,
        ankiUiState.popupSettings,
        fontManager,
        fontLibraryState.revision,
        effectiveSettings.popupScale,
        popupContentLanguageProfile,
        noAudioFoundText,
        audioLoadingText,
    ) {
        LookupPopupHtml.renderIframeDocument(
            assets = null,
            assetVersion = popupAssets.hashCode(),
            dictionaryStyles = dictionaryStyles,
            settings = dictionarySettings,
            swipeToDismiss = effectiveSettings.popupSwipeToDismiss,
            swipeThreshold = effectiveSettings.popupSwipeThreshold,
            reducedMotionScrolling = effectiveSettings.popupReducedMotionScrolling,
            reducedMotionScrollPercent = effectiveSettings.popupReducedMotionScrollPercent,
            reducedMotionSwipeThreshold = effectiveSettings.popupReducedMotionSwipeThreshold,
            audioSettings = popupAudioSettings,
            noAudioFoundText = noAudioFoundText,
            audioLoadingText = audioLoadingText,
            ankiSettings = ankiUiState.popupSettings,
            fontFaceCss = fontManager.popupFontFaceCss(),
            popupScale = effectiveSettings.popupScale,
            contentLanguageProfile = popupContentLanguageProfile,
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
    val readerPopupResourceHandler = remember(context, popupAssets, fontManager, dictionaryRepository) {
        ReaderLookupPopupResourceHandler(
            context = context.applicationContext,
            assets = popupAssets,
            fontManager = fontManager,
            audioRequestHandler = appContainer.audioRequestHandler,
            imageRequestHandler = DictionaryImageRequestHandler(dictionaryRepository::dictionaryMedia),
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
    val showDisplaySettings = stateHolder.showDisplaySettings
    val showGoTo = stateHolder.showGoTo
    val showSasayaki = stateHolder.showSasayaki
    val showStatistics = stateHolder.showStatistics
    val focusMode = stateHolder.focusMode
    LaunchedEffect(ttsState.error) {
        if (ttsAvailable && ttsState.error != null &&
            (ttsState.started || stateHolder.lookupPopups.isNotEmpty())
        ) showTts = true
    }
    val sasayakiWasPausedByLookup = stateHolder.sasayakiLookupPause.pending
    var persistedStatistics by remember(bookRoot) {
        mutableStateOf<ReadingSessions?>(if (bookRoot == null) emptyMap() else null)
    }
    var statisticsLoadFailure by remember(bookRoot) { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(bookRoot, bookRepository) {
        try {
            persistedStatistics = if (bookRoot != null) bookRepository.loadSessions(bookRoot) else emptyMap()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Leave the tracker unavailable so corrupt history cannot be overwritten.
            statisticsLoadFailure = error
        }
    }
    val statisticsTracker = remember(
        bookRoot,
        book.title,
        persistedStatistics,
    ) {
        persistedStatistics?.let { statistics ->
            ReaderStatisticsTracker(
                initialStatistics = statistics,
                resetMinutes = effectiveSettings.statisticsResetMinutes,
            )
        }
    }
    var statisticsState by remember(statisticsTracker) { mutableStateOf(statisticsTracker?.state) }
    LaunchedEffect(statisticsTracker, effectiveSettings.statisticsResetMinutes) {
        statisticsTracker?.resetMinutes = effectiveSettings.statisticsResetMinutes
        statisticsState = statisticsTracker?.state
    }
    LaunchedEffect(statisticsTracker, bookRoot) {
        if (bookRoot != null && statisticsTracker != null) {
            bookRepository.statisticsStore.changes.collect {
                statisticsTracker.applySessions(bookRepository.loadSessions(bookRoot))
                statisticsState = statisticsTracker.state
            }
        }
    }
    var resumeStatisticsTrackingOnStart by remember(statisticsTracker) { mutableStateOf(false) }
    fun currentDisplayedCharacter(): Int =
        book.characterCountAt(
            stateHolder.readerPosition.displayedPosition.index,
            stateHolder.readerPosition.displayedPosition.progress,
        )
    fun currentChapterEndCharacter(): Int {
        return book.tocRangeAt(stateHolder.readerPosition.displayedPosition).endCharacter
    }
    fun syncStatisticsState() {
        statisticsState = statisticsTracker?.state
    }
    fun startStatisticsForProgressChangeIfNeeded() {
        if (effectiveSettings.statisticsAutostartOnPageTurn) {
            statisticsTracker?.startForPageTurnIfNeeded(currentDisplayedCharacter())
            syncStatisticsState()
        }
    }
    fun recordStatisticsAtDisplayedPosition() {
        if (applyingBookmark || bookDeleted) return
        statisticsTracker?.update(currentDisplayedCharacter())
        syncStatisticsState()
    }
    fun resetStatisticsBaseline() {
        statisticsTracker?.resetBaseline(currentDisplayedCharacter())
        syncStatisticsState()
    }
    fun statisticsForSave(): ReadingSessions? {
        recordStatisticsAtDisplayedPosition()
        return statisticsTracker?.statisticsForPersistenceOrNull()
    }
    fun flushStatistics() {
        if (bookDeleted || statisticsTracker?.state?.isTracking != true) return
        statisticsForSave()?.let(onSaveStatistics)
    }
    fun saveReaderPosition(position: ReaderChapterPosition, statistics: ReadingSessions? = statisticsForSave()) {
        if (applyingBookmark || bookDeleted) return
        onSaveBookmark(position.index, position.progress, statistics)
    }
    fun saveCurrentDisplayedPosition() {
        saveReaderPosition(stateHolder.readerPosition.displayedPosition)
    }
    fun cancelReaderAutoPage() {
        ttsViewModel.playback.cancelReaderPresentation()
        cancelReaderSasayakiAutoPage(
            job = sasayakiAutoPageJob,
            clearAutoPageJob = { sasayakiAutoPageJob = null },
            clearPendingRestoreCue = {
                pendingSasayakiRestoreCue = null
                if (pendingReaderPlaybackTargetRestore?.audiobookCue != null) {
                    pendingReaderPlaybackTargetRestore?.result?.cancel()
                    pendingReaderPlaybackTargetRestore = null
                }
            },
            resumeAfterAutoPageHold = { sasayakiPlayer?.resumeAfterAutoPageHold() },
        )
    }
    fun clearSearchHighlight() {
        webView?.evaluateJavascript(ReaderPaginationScripts.clearSearchHighlightInvocation(), null)
    }
    fun jumpToPositionWithHistory(position: ReaderChapterPosition, fragment: String? = null) {
        clearSearchHighlight()
        cancelReaderAutoPage()
        val statistics = statisticsForSave()
        val savedPosition = stateHolder.jumpToWithHistory(position, fragment)
        resetStatisticsBaseline()
        saveReaderPosition(savedPosition, statistics)
    }
    fun navigateJumpBack() {
        clearSearchHighlight()
        cancelReaderAutoPage()
        val statistics = statisticsForSave()
        val savedPosition = stateHolder.navigateBackInJumpHistory() ?: return
        resetStatisticsBaseline()
        saveReaderPosition(savedPosition, statistics)
    }
    fun navigateJumpForward() {
        clearSearchHighlight()
        cancelReaderAutoPage()
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
    fun changeHighlight(color: HighlightColor, id: String, result: ReaderHighlightResult) {
        if (result !is ReaderHighlightCreationResult) {
            val current = highlights.orEmpty()
            val next = ReaderHighlights.applyEdit(current, result, color)
            if (next != current) persistHighlights(next)
            return
        }
        val creation = result
        val chapter = currentLoadChapter()
        val info = book.bookInfo.chapterInfo[chapter.href] ?: return
        val highlight = ReaderHighlight(
            id = id,
            character = info.currentTotal + creation.start,
            offset = creation.offset,
            text = creation.text,
            textFurigana = creation.textFurigana,
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
            flushStatistics()
            tracker.stop(currentDisplayedCharacter())
            syncStatisticsState()
        } else {
            tracker.start(currentDisplayedCharacter())
            syncStatisticsState()
        }
    }
    fun pauseStatisticsForLifecycleStop(): Boolean {
        val tracker = statisticsTracker ?: return false
        flushStatistics()
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
    val hasStatisticsBlockingModal = stateHolder.hasStatisticsBlockingSheet || fullscreenImage != null
    LaunchedEffect(statisticsTracker, hasStatisticsBlockingModal) {
        statisticsTracker?.setModalPaused(
            paused = hasStatisticsBlockingModal,
            currentCharacter = currentDisplayedCharacter(),
        )
        syncStatisticsState()
    }
    LaunchedEffect(statisticsTracker, effectiveSettings.statisticsAutostartOnBookOpen) {
        if (effectiveSettings.statisticsAutostartOnBookOpen) {
            statisticsTracker?.start(currentDisplayedCharacter())
            syncStatisticsState()
        }
    }
    LaunchedEffect(statisticsTracker, statisticsState?.isTracking) {
        val tracker = statisticsTracker ?: return@LaunchedEffect
        if (tracker.state.isTracking) {
            while (tracker.state.isTracking) {
                delay(1_000)
                if (!applyingBookmark && !bookDeleted) tracker.update(currentDisplayedCharacter())
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
            lookup = { text, maxResults, scanLength -> dictionaryRepository.lookup(text, maxResults, scanLength, dictionarySettings.lookupOptions()) },
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
                contentLanguageProfile = popupContentLanguageProfile,
            ),
        )?.let { (popup, highlightCount) ->
            popup.copy(sasayakiCue = sasayakiCueForSelection(selection), readerChapterIndex = readerPosition.displayedPosition.index) to highlightCount
        }
    fun lookupChildPopup(selection: ReaderSelectionData): Pair<LookupPopupItem, Int>? =
        createLookupPopupItem(
            selection = selection,
            dictionaryStyles = dictionaryStyles,
            lookup = { text, maxResults, scanLength -> dictionaryRepository.lookup(text, maxResults, scanLength, dictionarySettings.lookupOptions()) },
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
                contentLanguageProfile = popupContentLanguageProfile,
            ),
        )?.let { (popup, highlightCount) ->
            popup.copy(sasayakiCue = sasayakiCueForSelection(selection)) to highlightCount
        }

    fun closeReader() {
        flushStatistics()
        statisticsTracker?.stop(currentDisplayedCharacter())
        syncStatisticsState()
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
        cancelReaderAutoPage()
        ttsViewModel.playback.stop()
        sasayakiPlayer?.stopPlayback()
        sasayakiPlayer = null
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
    fun setLookupPopups(nextPopups: List<LookupPopupItem>) {
        val activeIds = nextPopups.mapTo(mutableSetOf()) { it.id }
        ttsMiningJobs.filterKeys { it !in activeIds }.values.forEach { it.cancel() }
        ttsMiningJobs.keys.retainAll(activeIds)
        readerPopupHistories = readerPopupHistories.filterKeys(activeIds::contains)
        rootSelectionHighlight = rootSelectionHighlight?.takeIf { highlight ->
            highlight.popupId == null || highlight.popupId in activeIds
        }
        stateHolder.updateLookupPopups(nextPopups)
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
        openReaderFullscreenImage(
            sourceUrl = sourceUrl,
            imageResourceForUrl = readerImageResourceBridge::imageResourceForUrl,
            closeLookupPopupsAndSelection = ::closeLookupPopupsAndSelection,
            showFullscreenImage = { fullscreenImage = it },
        )
    }
    fun updateSasayakiSettings(update: SasayakiSettingsUpdate) {
        val settings = update(sasayakiSettings)
        sasayakiSettings = settings
        if (sasayakiSettingsState is SettingsLoadState.Loaded) sasayakiSettingsState = SettingsLoadState.Loaded(settings)
        scope.launch {
            sasayakiSettingsRepository.update(update)
        }
        sasayakiPlayer?.autoScroll = settings.autoScroll
        sasayakiPlayer?.readerSkipButtonAction = settings.readerSkipButtonAction
    }
    fun goToNextChapter(): Boolean {
        if (!stateHolder.canAcceptReaderNavigationInput()) return false
        cancelReaderAutoPage()
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
        cancelReaderAutoPage()
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
        if (applyingBookmark || bookDeleted) return
        stateHolder.enterFocusModeForReaderInteraction()
        startStatisticsForProgressChangeIfNeeded()
        val savedPosition = stateHolder.recordDisplayedProgress(progress)
        stateHolder.clearForwardHistoryAfterManualMovement()
        recordStatisticsAtDisplayedPosition()
        saveReaderPosition(savedPosition)
    }
    fun displayPagedTurnProgress(progress: Double) {
        if (applyingBookmark || bookDeleted) return
        stateHolder.enterFocusModeForReaderInteraction()
        startStatisticsForProgressChangeIfNeeded()
        stateHolder.recordDisplayedProgress(progress)
        stateHolder.clearForwardHistoryAfterManualMovement()
        recordStatisticsAtDisplayedPosition()
    }
    fun displayContinuousScrollProgress(progress: Double, restoreEpoch: Int) {
        if (applyingBookmark || bookDeleted) return
        startStatisticsForProgressChangeIfNeeded()
        stateHolder.recordContinuousScrollDisplayProgress(progress, restoreEpoch) ?: return
        stateHolder.clearForwardHistoryAfterManualMovement()
        recordStatisticsAtDisplayedPosition()
    }
    fun saveContinuousScrollProgress(progress: Double, restoreEpoch: Int) {
        if (applyingBookmark || bookDeleted) return
        startStatisticsForProgressChangeIfNeeded()
        val savedPosition = stateHolder.recordContinuousScrollProgress(progress, restoreEpoch) ?: return
        stateHolder.clearForwardHistoryAfterManualMovement()
        recordStatisticsAtDisplayedPosition()
        saveReaderPosition(savedPosition)
    }
    fun navigateReaderPage(direction: ReaderNavigationDirection): Boolean {
        val currentWebView = webView ?: return false
        if (!stateHolder.beginReaderNavigationInput()) return false
        cancelReaderAutoPage()
        closeLookupPopupsAndSelection()
        val onLimit = when (direction) {
            ReaderNavigationDirection.Forward -> ::goToNextChapter
            ReaderNavigationDirection.Backward -> ::goToPreviousChapter
        }
        currentWebView.navigatePage(direction, onLimit, ::displayPagedTurnProgress, ::saveDisplayedProgress)
        return true
    }
    fun pauseNarrationForLookupIfNeeded() {
        ttsLookupPause.pauseIfPlaying(
            enabled = sasayakiSettings.autoPause && ttsAvailable,
            isPlaying = ttsViewModel.playback.state.value.playing,
            pause = ttsViewModel.playback::pauseForLookup,
        )
        val player = sasayakiPlayer
        stateHolder.sasayakiLookupPause.pauseIfPlaying(
            enabled = sasayakiSettings.enabled && sasayakiSettings.autoPause,
            isPlaying = player?.isPlaying == true,
        ) {
            requireNotNull(player).pausePlayback()
            val resume = {
                if (sasayakiPlayer === player && !player.isPlaying) player.togglePlayback()
            }
            resume
        }
    }
    fun handleReaderInteraction() {
        cancelReaderAutoPage()
        stateHolder.enterFocusModeForReaderInteraction()
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
                if (ttsMiningJobs[message.popupId]?.isActive == true) return
                val needsSentence = ankiUiState.usesHandlebar(message.formatId, "{sentence-audio}")
                val needsSasayaki = ankiUiState.usesHandlebar(message.formatId, "{sasayaki-audio}")
                val linkedAudio = ttsAssociated
                val audioPlayer = sasayakiPlayer
                val selectionChapter = popup.readerChapterIndex ?: readerPosition.displayedPosition.index
                ttsMiningJobs[message.popupId] = scope.launch {
                    var generated: File? = null
                    try {
                        val cue = popup.sasayakiCue
                        val audiobook = if ((needsSasayaki || needsSentence && linkedAudio) && cue != null)
                            withContext(Dispatchers.IO) { audioPlayer?.exportCueAudio(cue, popup.state.selection.sentence) } else null
                        val sentenceAudio = if (needsSentence) {
                            if (linkedAudio) audiobook ?: throw TtsException(TtsFailure.AudioUnavailable)
                            else {
                                ttsViewModel.sentenceAudio(ttsBookId) {
                                    ttsViewModel.sourceSentence(book, selectionChapter, popup.state.selection)
                                }.also { generated = it }
                            }
                        } else null
                        val ankiContext = popup.state.ankiContext.copy(
                            sasayakiAudioPath = if (needsSasayaki) audiobook?.absolutePath else null,
                            sentenceAudioPath = sentenceAudio?.absolutePath,
                            sentenceAudioSuppressed = needsSentence && !linkedAudio && sentenceAudio == null,
                        )
                        val mined = ankiViewModel.minePreparedEntry(message.formatId, message.payloadJson, ankiContext,
                            onWarning = { ankiSynchronizationWarning = it })
                        replyReaderPopupMessage(message.popupId, messageId, mined.toString())
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        ankiOperationError = error
                        replyReaderPopupMessage(message.popupId, messageId, "false")
                    } finally { generated?.delete() }
                }
            }
            is ReaderLookupPopupBridgeMessage.DuplicateCheck -> {
                ankiViewModel.duplicateStatesAsync(message.valuesByHandlebar) { states ->
                    replyReaderPopupMessage(message.popupId, message.messageId ?: return@duplicateStatesAsync, readerPopupBooleanMapJson(states))
                }
            }
            is ReaderLookupPopupBridgeMessage.ShowNotes -> {
                ankiViewModel.showNotesAsync(
                    message.formatId,
                    message.valuesByHandlebar,
                    onError = { ankiOperationError = it },
                ) { shown ->
                    replyReaderPopupMessage(message.popupId, message.messageId ?: return@showNotesAsync, shown.toString())
                }
            }
            is ReaderLookupPopupBridgeMessage.LookupRedirect -> {
                val popup = popupById(message.popupId) ?: return
                val results = dictionaryRepository.lookup(
                    message.query,
                    popup.state.dictionarySettings.maxResults,
                    popup.state.dictionarySettings.scanLength,
                    popup.state.dictionarySettings.lookupOptions(),
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
            is ReaderLookupPopupBridgeMessage.KanjiRedirect -> {
                val result = dictionaryRepository.lookupKanji(message.kanji)
                replyReaderPopupMessage(
                    message.popupId,
                    message.messageId ?: return,
                    if (result.entries.isEmpty()) "null" else LookupPopupHtml.kanjiJsonString(result),
                )
            }
            is ReaderLookupPopupBridgeMessage.KanjiRedirectCommitted -> {
                val current = readerPopupHistories[message.popupId] ?: ReaderPopupHistoryCounts()
                readerPopupHistories = readerPopupHistories + (
                    message.popupId to current.copy(
                        backCount = current.backCount + 1,
                        forwardCount = 0,
                    )
                )
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
            is ReaderLookupPopupBridgeMessage.ScrollState,
            is ReaderLookupPopupBridgeMessage.SourceHistoryRestored -> Unit
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
            is ReaderLookupPopupBridgeMessage.TtsReplaySentence,
            is ReaderLookupPopupBridgeMessage.TtsTogglePlayback,
            is ReaderLookupPopupBridgeMessage.TtsPlayForward -> {
                val popup = popupById(message.popupId) ?: return
                val chapter = popup.readerChapterIndex ?: return
                val offset = popup.state.selection.normalizedOffset ?: return
                if (!ttsAvailable) return
                WordAudioPlayer.get(context).stop()
                val wasPaused = ttsLookupPause.pending
                ttsLookupPause.clear()
                when (message) {
                    is ReaderLookupPopupBridgeMessage.TtsReplaySentence ->
                        ttsViewModel.playback.startAt(chapter, offset, singleSentence = true)
                    is ReaderLookupPopupBridgeMessage.TtsPlayForward -> {
                        ttsViewModel.playback.startAt(chapter, offset)
                        closeLookupPopupsAndSelection()
                    }
                    else -> {
                        if (wasPaused) ttsViewModel.playback.pause()
                        else if (ttsState.started) ttsViewModel.playback.toggle()
                        else ttsViewModel.playback.startAt(chapter, offset)
                    }
                }
            }
            is ReaderLookupPopupBridgeMessage.SasayakiReplayCue -> {
                popupById(message.popupId)?.sasayakiCue?.let { cue ->
                    WordAudioPlayer.get(context).stop()
                    sasayakiPlayer?.playCue(cue, stop = true)
                }
            }
            is ReaderLookupPopupBridgeMessage.SasayakiTogglePlayback -> {
                WordAudioPlayer.get(context).stop()
                if (sasayakiWasPausedByLookup) {
                    stateHolder.sasayakiLookupPause.clear()
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
        cancelReaderAutoPage()
        stateHolder.enterFocusModeForReaderInteraction()
        rootSelectionHighlight = null
        val lookup = lookupRootPopup(selection)
        if (lookup != null) {
            val (popup, highlightCount) = lookup
            pauseNarrationForLookupIfNeeded()
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
            setLookupPopups(emptyList())
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
        cancelReaderAutoPage()
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
        val currentCharacter = book.characterCountAt(
            readerPosition.displayedPosition.index,
            readerPosition.displayedPosition.progress,
        )
        val chapterRange = book.tocRangeAt(readerPosition.displayedPosition)
        ReaderChromeState(
            title = book.title,
            currentCharacter = currentCharacter,
            totalCharacters = book.bookInfo.characterCount,
            chapterCurrentCharacter = chapterRange.currentCharacter(currentCharacter),
            chapterTotalCharacters = chapterRange.totalCharacters,
            backTargetCharacter = stateHolder.backTargetPosition?.let { book.characterCountAt(it.index, it.progress) },
            forwardTargetCharacter = stateHolder.forwardTargetPosition?.let { book.characterCountAt(it.index, it.progress) },
            statistics = statisticsState?.session?.let {
                ReaderStatisticsChromeState(
                    readingSpeed = it.readingSpeed,
                    readingTimeSeconds = it.readingTime,
                )
            },
        )
    }
    fun commitSasayakiRestoreLanding(progress: Double?) {
        val savedPosition = progress?.let(stateHolder::recordDisplayedProgress)
            ?: stateHolder.readerPosition.displayedPosition
        resetStatisticsBaseline()
        saveReaderPosition(savedPosition, statisticsTracker?.statisticsForPersistenceOrNull())
    }
    fun recordSasayakiDisplayedProgress(progress: Double, countStatistics: Boolean = true) {
        if (countStatistics) {
            startStatisticsForProgressChangeIfNeeded()
            val savedPosition = stateHolder.recordDisplayedProgress(progress)
            recordStatisticsAtDisplayedPosition()
            saveReaderPosition(savedPosition)
        } else {
            commitSasayakiRestoreLanding(progress)
        }
    }
    fun decodeSasayakiMediaStops(result: String?): List<ReaderSasayakiMediaStop> {
        val payload = result?.takeUnless { it == "null" } ?: return emptyList()
        return runCatching {
            sasayakiMediaStopJson.decodeFromString(
                ListSerializer(ReaderSasayakiMediaStop.serializer()),
                payload,
            )
        }.getOrDefault(emptyList())
    }
    suspend fun evaluateReaderJavascript(source: String): String? {
        val targetWebView = webView ?: return null
        return suspendCancellableCoroutine { continuation ->
            targetWebView.evaluateJavascript(source) { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }
    }
    suspend fun awaitReaderChapterReady(chapterIndex: Int): Boolean =
        awaitReaderSasayakiChapterReady(
            isChapterReady = {
                !stateHolder.isWebViewRestoring &&
                webView != null &&
                    stateHolder.readerPosition.displayedPosition.index == chapterIndex
            },
        )
    suspend fun sasayakiMediaStopsToChapterEnd(): List<ReaderSasayakiMediaStop> =
        decodeSasayakiMediaStops(
            evaluateReaderJavascript(ReaderPaginationScripts.sasayakiMediaStopsToChapterEndInvocation()),
        )
    suspend fun showSasayakiMediaStop(stop: ReaderSasayakiMediaStop, countStatistics: Boolean = true): Double? {
        val progress = ReaderPaginationScripts.doubleResult(
            evaluateReaderJavascript(
                ReaderPaginationScripts.showSasayakiMediaStopInvocation(
                    sasayakiMediaStopJson.encodeToString(ReaderSasayakiMediaStop.serializer(), stop),
                ),
            ),
        )
        progress?.let { recordSasayakiDisplayedProgress(it, countStatistics = countStatistics) }
        return progress
    }
    suspend fun loadSasayakiChapter(
        chapterIndex: Int,
        initialProgress: Double = 0.0,
        restoreCue: SasayakiMatch? = null,
    ): Boolean? {
        if (restoreCue != null) {
            pendingSasayakiRestoreCue = PendingSasayakiCue(
                cue = restoreCue,
                reveal = true,
                source = SasayakiCueRevealSource.DirectJump,
            )
        }
        val target = ReaderChapterPosition(index = chapterIndex, progress = initialProgress)
        readerSasayakiChapterLoadPosition(
            saveStatistics = ::statisticsForSave,
            jumpToTarget = { stateHolder.jumpTo(target) },
            resetStatisticsBaseline = ::resetStatisticsBaseline,
            saveReaderPosition = { position, statistics -> saveReaderPosition(position, statistics) },
        )
        if (!awaitReaderChapterReady(chapterIndex)) {
            pendingSasayakiRestoreCue = null
            return null
        }
        return restoreCue != null && pendingSasayakiRestoreCue?.cue?.id != restoreCue.id
    }
    fun completePendingReaderPlaybackTargetRestore(
        pending: PendingReaderPlaybackTargetRestore,
        result: ReaderPlaybackTargetRestoreResult,
    ) {
        if (pendingReaderPlaybackTargetRestore === pending) {
            pendingReaderPlaybackTargetRestore = null
            if (!pending.result.isCompleted) {
                pending.result.complete(result)
            }
        }
    }
    suspend fun loadReaderPlaybackTargetChapter(
        chapterIndex: Int,
        range: SasayakiCueRange,
        inspectMedia: Boolean,
        initialProgress: Double = 0.0,
        audiobookCue: SasayakiMatch? = null,
        canPresent: () -> Boolean = { true },
    ): ReaderPlaybackTargetRestoreResult? {
        val pending = PendingReaderPlaybackTargetRestore(
            chapterIndex, range, inspectMedia, audiobookCue, canPresent, CompletableDeferred(),
        )
        pendingReaderPlaybackTargetRestore?.result?.cancel()
        pendingReaderPlaybackTargetRestore = pending
        try {
            readerSasayakiChapterLoadPosition(
                saveStatistics = ::statisticsForSave,
                jumpToTarget = { stateHolder.jumpTo(ReaderChapterPosition(chapterIndex, initialProgress)) },
                resetStatisticsBaseline = ::resetStatisticsBaseline,
                saveReaderPosition = { position, statistics -> saveReaderPosition(position, statistics) },
            )
            if (!awaitReaderChapterReady(chapterIndex) || !canPresent()) return null
            return pending.result.await()
        } finally {
            if (pendingReaderPlaybackTargetRestore === pending) pendingReaderPlaybackTargetRestore = null
            if (!pending.result.isCompleted) pending.result.cancel()
        }
    }
    fun sasayakiCueRestoreBeforeVisible(
        restoredWebView: WebView,
        pending: PendingSasayakiCue,
    ): ReaderRestoreBeforeVisibleAction = { show, completeAfterVisible ->
        restoredWebView.evaluateJavascript(
            ReaderPaginationScripts.highlightSasayakiCueInvocation(
                pending.cue.toCueRange(),
                reveal = true,
            ),
        ) { result ->
            val progress = ReaderPaginationScripts.doubleResult(result)
            restoredWebView.postVisualStateCallback(
                SystemClock.uptimeMillis(),
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        restoredWebView.post {
                            if (show()) {
                                if (pendingSasayakiRestoreCue?.cue?.id == pending.cue.id) {
                                    commitSasayakiRestoreLanding(progress)
                                    pendingSasayakiRestoreCue = null
                                }
                                completeAfterVisible()
                            }
                        }
                    }
                },
            )
        }
    }
    fun readerPlaybackTargetRestoreBeforeVisible(
        restoredWebView: WebView,
        pendingTargetMedia: PendingReaderPlaybackTargetRestore,
    ): ReaderRestoreBeforeVisibleAction = restore@{ show, completeAfterVisible ->
        fun abandonIfObsolete(): Boolean {
            if (pendingTargetMedia.canPresent()) return false
            completePendingReaderPlaybackTargetRestore(pendingTargetMedia, ReaderPlaybackTargetRestoreResult(false))
            if (show()) completeAfterVisible()
            return true
        }
        if (abandonIfObsolete()) return@restore
        val inspect = if (pendingTargetMedia.inspectMedia)
            ReaderPaginationScripts.sasayakiMediaStopsBeforeCueInvocation(pendingTargetMedia.range) else "[]"
        restoredWebView.evaluateJavascript(inspect) { mediaStopsResult ->
            if (abandonIfObsolete()) return@evaluateJavascript
            val stops = decodeSasayakiMediaStops(mediaStopsResult)
            if (stops.isEmpty()) {
                restoredWebView.evaluateJavascript(
                    ReaderPaginationScripts.highlightSasayakiCueInvocation(
                        pendingTargetMedia.range,
                        reveal = true,
                    ),
                ) { result ->
                    val progress = ReaderPaginationScripts.doubleResult(result)
                    restoredWebView.postVisualStateCallback(
                        SystemClock.uptimeMillis(),
                        object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                restoredWebView.post {
                                    if (abandonIfObsolete()) return@post
                                    if (show()) {
                                        commitSasayakiRestoreLanding(progress)
                                        completePendingReaderPlaybackTargetRestore(
                                            pending = pendingTargetMedia,
                                            result = ReaderPlaybackTargetRestoreResult(
                                                restoredCue = true,
                                            ),
                                        )
                                        completeAfterVisible()
                                    }
                                }
                            }
                        },
                    )
                }
            } else {
                val firstStop = stops.first()
                restoredWebView.evaluateJavascript(
                    ReaderPaginationScripts.showSasayakiMediaStopInvocation(
                        sasayakiMediaStopJson.encodeToString(
                            ReaderSasayakiMediaStop.serializer(),
                            firstStop,
                        ),
                    ),
                ) { result ->
                    val progress = ReaderPaginationScripts.doubleResult(result)
                    restoredWebView.postVisualStateCallback(
                        SystemClock.uptimeMillis(),
                        object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                restoredWebView.post {
                                    if (abandonIfObsolete()) return@post
                                    if (show()) {
                                        completePendingReaderPlaybackTargetRestore(
                                            pending = pendingTargetMedia,
                                            result = ReaderPlaybackTargetRestoreResult(
                                                restoredCue = false,
                                                firstStopWasShown = true,
                                                firstStopProgress = progress,
                                                remainingStops = stops.drop(1),
                                            ),
                                        )
                                        completeAfterVisible()
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
    fun sasayakiRestoreBeforeVisibleAction(
        restoredWebView: WebView,
        chapterIndex: Int,
    ): ReaderRestoreBeforeVisibleAction? {
        val pendingTargetMedia = pendingReaderPlaybackTargetRestore
        if (
            pendingTargetMedia != null &&
            pendingTargetMedia.chapterIndex == chapterIndex
        ) {
            return readerPlaybackTargetRestoreBeforeVisible(restoredWebView, pendingTargetMedia)
        }
        val pending = pendingSasayakiRestoreCue ?: return null
        if (!pending.reveal || pending.cue.chapterIndex != chapterIndex) {
            return null
        }
        return sasayakiCueRestoreBeforeVisible(restoredWebView, pending)
    }
    suspend fun revealReaderCueWithMediaStops(
        range: SasayakiCueRange,
        chapterIndex: Int,
        reveal: Boolean,
        source: SasayakiCueRevealSource,
        preserveReveal: Boolean = false,
        audiobookCue: SasayakiMatch? = null,
        canPresent: () -> Boolean = { true },
    ): ReaderPlaybackRevealResult {
        if (!canPresent() || !awaitReaderChapterReady(stateHolder.readerPosition.displayedPosition.index)) {
            return ReaderPlaybackRevealResult(null, false)
        }
        var holdStarted = false
        var shouldResume = false
        val imageHoldMillis = sasayakiImageHoldMillis(sasayakiSettings.imageHoldSeconds)
        fun startHoldIfNeeded() {
            if (!holdStarted) {
                holdStarted = true
                if (audiobookCue != null) shouldResume = sasayakiPlayer?.pauseForAutoPageHold() == true
            }
        }
        suspend fun awaitImageHold() {
            awaitReaderSasayakiImageHold(imageHoldMillis, isFullscreenImageVisible = { fullscreenImage != null })
        }
        val actions = object : ReaderPlaybackPresentationActions {
            override suspend fun stopsBeforeCue() = decodeSasayakiMediaStops(evaluateReaderJavascript(
                ReaderPaginationScripts.sasayakiMediaStopsBeforeCueInvocation(range)))
            override suspend fun stopsToChapterEnd() = sasayakiMediaStopsToChapterEnd()
            override suspend fun showStops(stops: List<ReaderSasayakiMediaStop>, countStatistics: Boolean) {
                if (stops.isEmpty()) return
                startHoldIfNeeded()
                for (stop in stops) {
                    if (!canPresent()) return
                    showSasayakiMediaStop(stop, countStatistics)
                    awaitImageHold()
                }
            }
            override suspend fun holdVisibleStop(progress: Double?) {
                progress?.let { recordSasayakiDisplayedProgress(it, countStatistics = false) }
                startHoldIfNeeded()
                awaitImageHold()
            }
            override suspend fun loadChapter(chapterIndex: Int) = loadSasayakiChapter(chapterIndex) != null
            override suspend fun restoreTarget(inspectMedia: Boolean, fromChapter: Int): ReaderPlaybackTargetRestoreResult? {
                val result = if (!inspectMedia && audiobookCue != null) {
                    val restored = loadSasayakiChapter(chapterIndex,
                        readerSasayakiCrossChapterInitialProgress(book, audiobookCue, fromChapter), audiobookCue)
                        ?: return null
                    ReaderPlaybackTargetRestoreResult(restored)
                } else {
                    val initialProgress = if (chapterIndex < fromChapter) {
                        val count = book.chapters.getOrNull(chapterIndex)?.let { book.bookInfo.chapterInfo[it.href]?.chapterCount } ?: 0
                        if (count > 0) (range.start.toDouble() / count).coerceIn(0.0, 1.0) else 0.0
                    } else 0.0
                    loadReaderPlaybackTargetChapter(chapterIndex, range, inspectMedia, initialProgress, audiobookCue, canPresent)
                        ?: return null
                }
                pendingSasayakiRestoreCue = null
                return result
            }
            override suspend fun reveal(reveal: Boolean) = ReaderPaginationScripts.doubleResult(evaluateReaderJavascript(
                ReaderPaginationScripts.highlightSasayakiCueInvocation(range, reveal, preserveReveal)))
        }
        return try {
            revealReaderPlaybackWithMediaStops(chapterIndex, reveal, source, imageHoldMillis,
                currentChapter = { stateHolder.readerPosition.displayedPosition.index }, actions, canPresent)
        } finally {
            if (shouldResume) sasayakiPlayer?.resumeAfterAutoPageHold()
        }
    }
    fun dispatchSasayakiCueToReader(
        cue: SasayakiMatch,
        reveal: Boolean,
        source: SasayakiCueRevealSource,
    ) {
        lastSasayakiCue = PendingSasayakiCue(cue, reveal, source)
        val targetWebView = webView
        if (targetWebView == null || stateHolder.isWebViewRestoring) {
            pendingSasayakiCue = PendingSasayakiCue(cue = cue, reveal = reveal, source = source)
            return
        }
        pendingSasayakiCue = null
        if (source == SasayakiCueRevealSource.MatchRefresh) {
            targetWebView.evaluateJavascript(
                ReaderPaginationScripts.highlightSasayakiCueInvocation(
                    cue.toCueRange(), reveal = false, preserveReveal = true,
                ),
                null,
            )
            return
        }
        cancelReaderAutoPage()
        sasayakiAutoPageJob = scope.launch {
            val progress = revealReaderCueWithMediaStops(
                range = cue.toCueRange(),
                chapterIndex = cue.chapterIndex,
                audiobookCue = cue,
                reveal = reveal,
                source = source,
            )
            progress.progress?.let {
                recordSasayakiDisplayedProgress(it, countStatistics = progress.countStatistics)
            }
        }
    }
    LaunchedEffect(
        pendingSasayakiMatchUpdate,
        webView,
        sasayakiAutoPageJob,
        lookupPopups.isNotEmpty(),
        fullscreenImage,
        stateHolder.isWebViewRestoring,
    ) {
        val update = pendingSasayakiMatchUpdate ?: return@LaunchedEffect
        if (lookupPopups.isNotEmpty() || fullscreenImage != null || stateHolder.isWebViewRestoring) return@LaunchedEffect
        // Updating cue targets can replace DOM text nodes; wait for lookup and media holds to finish.
        awaitReaderSasayakiPresentationIdle(webView?.readerNativeSelectionState()) { sasayakiAutoPageJob }
        if (pendingSasayakiMatchUpdate !== update) return@LaunchedEffect
        if (stateHolder.lookupPopups.isNotEmpty() || fullscreenImage != null || stateHolder.isWebViewRestoring) return@LaunchedEffect
        sasayakiMatchData = update.data
        preserveSasayakiCueLayout = update.preserveLayout
        pendingSasayakiMatchUpdate = null
        sasayakiPlayer?.updateMatchData(update.data)
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
        dispatchSasayakiCueToReader(pending.cue, pending.reveal, pending.source)
    }
    val ttsPresenter: suspend (TtsSentence, Boolean, ReaderTtsPresentationSource) -> Unit = present@{ sentence, allowImages, source ->
        val presentationContext = currentCoroutineContext()
        val restoring = source != ReaderTtsPresentationSource.Playback
        val directJump = !restoring && !ttsViewModel.playback.state.value.playing
        fun presentation() = readerTtsPresentation(
            ttsViewModel.playback.state.value, sentence,
            stateHolder.readerPosition.displayedPosition.index, sasayakiSettings.autoScroll, restoring, directJump,
            passiveRestore = source == ReaderTtsPresentationSource.DocumentRestore,
        )
        val mode = presentation()
        if (mode == ReaderTtsPresentation.None) return@present
        val foreground = view.findViewTreeLifecycleOwner()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true
        if (foreground && ttsAvailable) {
            val result = revealReaderCueWithMediaStops(
                range = SasayakiCueRange("tts-${sentence.chapter}-${sentence.start}", sentence.start, sentence.length),
                chapterIndex = sentence.chapter,
                reveal = mode == ReaderTtsPresentation.Follow,
                preserveReveal = mode == ReaderTtsPresentation.Highlight,
                source = if (allowImages) SasayakiCueRevealSource.NaturalPlayback else SasayakiCueRevealSource.DirectJump,
                canPresent = { presentationContext.isActive && presentation() == mode },
            )
            if (mode == ReaderTtsPresentation.Follow) result.progress?.let {
                recordSasayakiDisplayedProgress(it, countStatistics = result.countStatistics)
            }
        } else if (mode == ReaderTtsPresentation.Follow && sentence.imageBefore && allowImages) {
            delay(sasayakiImageHoldMillis(sasayakiSettings.imageHoldSeconds))
        }
    }
    ttsViewModel.playback.imageHoldMillis = if (sasayakiSettings.autoScroll) {
        sasayakiImageHoldMillis(sasayakiSettings.imageHoldSeconds)
    } else 0L
    val currentTtsPresenter = rememberUpdatedState(ttsPresenter)
    DisposableEffect(ttsViewModel) {
        ttsViewModel.playback.presentSentence = { sentence, images -> currentTtsPresenter.value(sentence, images, ReaderTtsPresentationSource.Playback) }
        onDispose { ttsViewModel.playback.presentSentence = null }
    }
    LaunchedEffect(ttsAvailable, ttsState.started, ttsState.playing, webView, stateHolder.isWebViewRestoring) {
        if (ttsAvailable && ttsState.started && !stateHolder.isWebViewRestoring) ttsState.sentence?.let { currentTtsPresenter.value(it, false, ReaderTtsPresentationSource.DocumentRestore) }
        else if (!ttsAssociated && !stateHolder.isWebViewRestoring) {
            webView?.evaluateJavascript(ReaderPaginationScripts.clearSasayakiCueInvocation(), null)
        }
    }
    val sasayakiCallbacks = rememberReaderSasayakiCallbacks(
        onCue = ::dispatchSasayakiCueToReader,
        onClearCue = {
            cancelReaderAutoPage()
            lastSasayakiCue = null
            pendingSasayakiCue = null
            pendingSasayakiRestoreCue = null
            webView?.evaluateJavascript(ReaderPaginationScripts.clearSasayakiCueInvocation(), null)
        },
    )
    LaunchedEffect(bookRoot, isSasayakiMatchLoaded, isSasayakiPlaybackLoaded, sasayakiPlaybackData) {
        cancelReaderAutoPage()
        sasayakiPlayer?.release()
        sasayakiPlayer = if (bookRoot != null && isSasayakiMatchLoaded && isSasayakiPlaybackLoaded) {
            SasayakiPlayer(
                bookId = bookId,
                bookRoot = bookRoot,
                playbackRepository = BookSasayakiPlaybackRepository(bookRoot, bookRepository),
                bookTitle = book.title,
                bookCoverFile = sasayakiCoverFile,
                matchData = sasayakiMatchData,
                initialPlayback = sasayakiPlaybackData,
                getCurrentChapterIndex = { stateHolder.readerPosition.displayedPosition.index },
                onCue = sasayakiCallbacks.onCue,
                onClearCue = sasayakiCallbacks.onClearCue,
                playbackServiceRuntime = sasayakiPlaybackServiceRuntime,
            )
        } else {
            null
        }
    }
    val currentSasayakiPlayback = sasayakiPlayer?.playback ?: sasayakiPlaybackData
    LaunchedEffect(
        sasayakiAudioRepository,
        currentSasayakiPlayback?.audioUri,
        currentSasayakiPlayback?.audioFileName,
    ) {
        val repository = sasayakiAudioRepository
        val playback = currentSasayakiPlayback
        sasayakiAudiobookInfo = if (repository != null && playback != null) {
            withContext(Dispatchers.IO) {
                repository.inspectAudiobook(playback, context)
            }
        } else {
            SasayakiAudiobookInfo.Empty
        }
    }
    val currentSyncFlush = rememberUpdatedState<suspend (String) -> Unit> { key ->
        if (bookRoot?.name?.syncKey() == key) withContext(Dispatchers.Main.immediate) {
            onFlushBookmarkSaves()
            sasayakiPlayer?.flushSaves()
        }
    }
    val currentSyncStop = rememberUpdatedState<suspend (String) -> Unit> { key ->
        if (bookRoot?.name?.syncKey() == key) withContext(Dispatchers.Main.immediate) {
            flushStatistics()
            statisticsTracker?.stop(currentDisplayedCharacter())
            syncStatisticsState()
            bookDeleted = true
            onFlushBookmarkSaves()
            cancelReaderAutoPage()
            val player = sasayakiPlayer
            player?.stopPlayback()
            player?.flushSaves()
            player?.release()
            sasayakiPlayer = null
            onClose()
        }
    }
    val currentSyncApply = rememberUpdatedState<suspend (String, SyncBook, Boolean, Boolean, Boolean) -> Unit> { key, syncedBook, bookmarkChanged, audiobookChanged, highlightsChanged ->
        if (bookRoot?.name?.syncKey() == key && !bookDeleted) withContext(Dispatchers.Main.immediate) {
            if (bookmarkChanged) {
                book.bookInfo.resolveTtuCharacterPosition(syncedBook.bookmark!!.value.characterCount)?.let { position ->
                    applyingBookmark = true
                    stateHolder.applySyncedBookmark(ReaderChapterPosition(position.spineIndex, position.progress))
                    resetStatisticsBaseline()
                }
            }
            if (highlightsChanged) highlights = bookRepository.loadHighlights(bookRoot)
            if (audiobookChanged) {
                bookRepository.loadSasayakiPlayback(bookRoot)?.let { sasayakiPlayer?.applySyncedPlayback(it) }
            }
            statisticsTracker?.applySessions(syncedBook.sessions)
            syncStatisticsState()
        }
    }
    val currentSyncMatch = rememberUpdatedState<suspend (String) -> Unit> { key ->
        if (bookRoot?.name?.syncKey() == key) withContext(Dispatchers.Main.immediate) {
            val savedMatch = bookRepository.loadSasayakiMatch(bookRoot)
            val match = sasayakiTranscriptionViewModel.matchForReader(bookRoot, savedMatch)
            sasayakiSheetMatchData = match
            pendingSasayakiMatchUpdate = PendingSasayakiMatchUpdate(match, preserveLayout = true)
        }
    }
    DisposableEffect(bookRoot) {
        val sync = appContainer.googleDriveSyncManager
        sync.flushReader = { currentSyncFlush.value(it) }
        sync.stopReader = { currentSyncStop.value(it) }
        sync.store.applyReaderState = { key, book, bookmarkChanged, audiobookChanged, highlightsChanged ->
            currentSyncApply.value(key, book, bookmarkChanged, audiobookChanged, highlightsChanged)
        }
        sync.reloadSyncedMatch = { currentSyncMatch.value(it) }
        onDispose {
            sync.flushReader = null
            sync.stopReader = null
            sync.store.applyReaderState = null
            sync.reloadSyncedMatch = null
        }
    }
    DisposableEffect(Unit) {
        onDispose { sasayakiPlayer?.release() }
    }
    sasayakiPlayer?.autoScroll = sasayakiSettings.autoScroll
    sasayakiPlayer?.readerSkipButtonAction = sasayakiSettings.readerSkipButtonAction
    val currentReaderKeyHandler = rememberUpdatedState<(KeyEvent) -> Boolean> { event ->
        val keyEvent = readerHardwareKeyEventForKeyEvent(
            keyCode = event.keyCode,
            action = event.action,
            repeatCount = event.repeatCount,
            settings = effectiveSettings,
            sasayakiEnabled = sasayakiSettings.enabled,
            hasSasayakiAudio = sasayakiPlayer?.hasAudio == true,
            hasLookupPopup = stateHolder.lookupPopups.isNotEmpty(),
            hasStartedTts = ttsAvailable && ttsViewModel.playback.state.value.let { it.bookId == ttsBookId && it.started },
        )
        if (!keyEvent.consumed) return@rememberUpdatedState false
        when (val action = keyEvent.action) {
            is ReaderHardwareKeyAction.ReaderNavigation -> navigateReaderPage(action.direction)
            is ReaderHardwareKeyAction.PopupTermNavigation -> {
                val direction = when (action.direction) {
                    PopupTermNavigationDirection.Previous -> "previous"
                    PopupTermNavigationDirection.Next -> "next"
                }
                webView?.evaluateJavascript(
                    "window.hoshiReaderPopupHost && window.hoshiReaderPopupHost.navigateTopTerm('$direction')",
                    null,
                )
            }
            is ReaderHardwareKeyAction.TtsSentenceNavigation -> {
                ttsViewModel.stopPreview()
                if (action.direction == ReaderNavigationDirection.Forward) ttsViewModel.playback.next()
                else ttsViewModel.playback.previous()
            }
            ReaderHardwareKeyAction.SasayakiSeekBackward -> {
                sasayakiPlayer?.previousCue()
            }
            ReaderHardwareKeyAction.SasayakiSeekForward -> {
                sasayakiPlayer?.nextCue()
            }
            null -> Unit
        }
        true
    }
    DisposableEffect(onReaderKeyEventHandlerChange) {
        onReaderKeyEventHandlerChange { event -> currentReaderKeyHandler.value(event) }
        onDispose { onReaderKeyEventHandlerChange(null) }
    }
    val keepScreenOn = ReaderScreenAwake.shouldKeepScreenOn(
        keepScreenOnWhileReading = effectiveSettings.keepScreenOnWhileReading,
        sasayakiIsPlaying = sasayakiPlayer?.isPlaying == true || ttsState.playing,
        sasayakiAutoScroll = sasayakiSettings.autoScroll,
    ) || sasayakiTranscriptionState.running
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
        if (ttsAvailable && ttsState.started) ttsState.sentence?.let { sentence -> scope.launch { currentTtsPresenter.value(sentence, false, ReaderTtsPresentationSource.Foreground) } }
        val inactiveAt = lastInactiveAtMillis
        lastInactiveAtMillis = null
        val plan = readerLifecycleAutoSyncPlan(
            event = ReaderLifecycleAutoSyncEvent.Resume,
            inactiveElapsedMillis = inactiveAt?.let { SystemClock.elapsedRealtime() - it },
            provider = syncProvider,
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
        flushStatistics()
        statisticsTracker?.stop(currentDisplayedCharacter())
        syncStatisticsState()
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

    val topChromeMetrics = readerTopChromeMetrics(effectiveSettings.topSafeAreaDp)
    val bottomChromeMetrics = readerBottomChromeMetrics(effectiveSettings.bottomSafeAreaDp)
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
    fun ttsAtReadingPosition(startPlayback: Boolean) {
        if (!ttsAvailable) return
        val position = readerPosition.displayedPosition
        val count = book.bookInfo.chapterInfo[book.chapters[position.index].href]?.chapterCount ?: 0
        val offset = (position.progress * count).toInt()
        if (startPlayback) ttsViewModel.playback.startAt(position.index, offset)
        else ttsViewModel.playback.prepareAt(position.index, offset)
    }
    val topPlaybackControl = readerTopPlaybackControl(
        settings = sasayakiSettings,
        tts = if (hasStartedTtsSession) {
            ReaderTopPlaybackControl(ttsState.playing || ttsLookupPause.pending) {
                ttsViewModel.stopPreview()
                if (ttsLookupPause.pending) {
                    ttsLookupPause.clear()
                    ttsViewModel.playback.pause()
                } else {
                    ttsViewModel.playback.toggle()
                }
            }
        } else null,
        sasayaki = sasayakiPlayer?.takeIf { it.hasAudio }?.let { player ->
            ReaderTopPlaybackControl(player.isPlaying || sasayakiWasPausedByLookup) {
                if (sasayakiWasPausedByLookup) {
                    stateHolder.sasayakiLookupPause.clear()
                } else {
                    player.togglePlayback()
                }
            }
        },
    )
    val reserveSasayakiTopToggle = remember(bookRoot, sasayakiSettings) {
        readerShouldReserveSasayakiTopToggle(bookRoot, sasayakiSettings)
    }
    val contentChromeInsets = readerContentChromeInsets(
        state = chromeState,
        settings = effectiveSettings,
        showSasayakiToggle = reserveSasayakiTopToggle || topPlaybackControl != null,
        showStatisticsToggle = effectiveSettings.showStatisticsToggle,
        focusMode = focusMode,
        topSystemInsetDp = stableStatusBarPadding.value.roundToInt().coerceAtLeast(0),
    )
    val topInfoPadding = readerTopInfoOverlayPaddingDp(
        topSystemInsetDp = currentStatusBarPaddingDp,
        focusMode = focusMode,
        settings = effectiveSettings,
    ).dp
    val chromeLayout = readerChromeLayout(
        chromeState,
        effectiveSettings,
        progressDisplay = progressDisplay,
        showSasayakiToggle = reserveSasayakiTopToggle || topPlaybackControl != null,
        showStatisticsToggle = effectiveSettings.showStatisticsToggle,
        focusMode = focusMode,
    )
    val chromeVisibility = readerChromeVisibility(
        focusMode = focusMode,
        hasStatisticsToggle = effectiveSettings.showStatisticsToggle,
        hasSasayakiToggle = topPlaybackControl != null,
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
    val restoreLoadingPresentation = readerRestoreLoadingPresentation(stateHolder.isWebViewRestoring)
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
                val readerLookupPopupViewport = ReaderLookupPopupViewport(
                    width = maxWidth.value.toDouble(),
                    height = maxHeight.value.toDouble(),
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
                    ttsAvailable, ttsState.playing, ttsState.canStart, ttsLookupPause.resumeAction,
                    readerPopupIframeUrl,
                    rootSelectionHighlight,
                ) {
                    readerLookupPopupFramePayloads(
                        popups = themedLookupPopups,
                        histories = readerPopupHistories,
                        viewport = readerLookupPopupViewport,
                        sasayakiWasPaused = sasayakiWasPausedByLookup,
                        sasayakiIsPlaying = sasayakiPlayer?.isPlaying == true,
                        ttsAvailable = ttsAvailable && ttsState.canStart,
                        ttsIsPlaying = ttsState.playing,
                        ttsWasPaused = ttsLookupPause.pending,
                        iframeUrl = readerPopupIframeUrl,
                        rootSelectionHighlight = rootSelectionHighlight,
                    )
                }
                if (highlights != null) {
                    val loadChapter = currentLoadChapter()
                    val currentSasayakiColors = readerSasayakiColors(
                        settings = effectiveSettings,
                        sasayakiSettings = sasayakiSettings,
                        systemDark = systemDarkTheme,
                    )
                    val generation = stateHolder.webViewGeneration
                    val restoreEpoch = stateHolder.webViewRestoreEpoch
                    val restoreChapterIndex = readerPosition.loadPosition.index
                    key(generation) {
                        ChapterWebView(
                            book = book,
                            chapterPosition = readerPosition.loadPosition,
                            chapterFragment = readerPosition.loadFragment,
                            webViewViewportSize = stateHolder.webViewViewportSize,
                            onReaderViewportSizeChanged = { size ->
                                if (stateHolder.updateViewportSize(size)) {
                                    rootSelectionHighlight = null
                                }
                            },
                            onWebViewReady = { webView = it },
                            onRendererTerminated = { terminatedView ->
                                if (webView === terminatedView && stateHolder.onRendererTerminated(
                                        ReaderRendererTermination(generation),
                                    )
                                ) {
                                    webView = null
                                    pendingSasayakiCue = readerSasayakiCueAfterRendererTermination(
                                        pendingCue = pendingSasayakiCue,
                                        pendingRestoreCue = pendingSasayakiRestoreCue,
                                        pendingTargetMediaCue = pendingReaderPlaybackTargetRestore?.audiobookCue,
                                        lastCue = lastSasayakiCue,
                                    )
                                    cancelReaderAutoPage()
                                    closeLookupPopupsAndSelection()
                                }
                            },
                            isWebViewRestoring = stateHolder.isWebViewRestoring,
                            webViewRestoreEpoch = stateHolder.webViewRestoreEpoch,
                            onRestoreStarted = stateHolder::markWebViewRestoring,
                            onRestoreCompleted = {
                                if (generation == stateHolder.webViewGeneration && restoreEpoch == stateHolder.webViewRestoreEpoch) {
                                    stateHolder.markWebViewRestored()
                                    if (applyingBookmark) {
                                        applyingBookmark = false
                                        resetStatisticsBaseline()
                                    }
                                    stateHolder.takeSearchHighlight(restoreChapterIndex, restoreEpoch)?.let { highlight ->
                                        webView?.evaluateJavascript(ReaderPaginationScripts.showSearchHighlightInvocation(highlight), null)
                                    }
                                }
                            },
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
                            selectionScanLength = readerSelectionMaxLength(dictionarySettings),
                            contentLanguageProfile = popupContentLanguageProfile,
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
                            preserveSasayakiCueLayout = preserveSasayakiCueLayout,
                            sasayakiTextColor = currentSasayakiColors.textColor,
                            sasayakiBackgroundColor = currentSasayakiColors.backgroundColor,
                            onTextSelected = handleTextSelected,
                            onClearLookupPopup = ::closeLookupPopupsAndSelection,
                            onReaderTapOutside = ::handleReaderTapOutside,
                            onReaderInteraction = ::handleReaderInteraction,
                            onImageTapped = ::openFullscreenImage,
                            onHighlightChanged = ::changeHighlight,
                            ttsEnabled = ttsEnabled,
                            onTtsReplacementSelected = { selection -> if (ttsEnabled) ttsSelection = selection },
                            readerPopupBridgeHolder = readerPopupBridgeHolder,
                            readerPopupResourceHandler = readerPopupResourceHandler,
                            readerPopupFrames = readerLookupPopupPayloads,
                            fontManager = fontManager,
                            systemDark = systemDarkTheme,
                            onBeforeRestoreVisible = { restoredWebView ->
                                sasayakiRestoreBeforeVisibleAction(
                                    restoredWebView = restoredWebView,
                                    chapterIndex = readerPosition.loadPosition.index,
                                )
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                ReaderLookupPopupIframeSync(
                    webView = webView,
                    payloads = readerLookupPopupPayloads,
                    rootHighlight = readerRootSelectionHighlightPayload,
                )
            }
        }
        if (restoreLoadingPresentation == ReaderRestoreLoadingPresentation.BehindChromeStaticSpinner) {
            ReaderLoadingPage(
                backgroundColor = Color(effectiveSettings.backgroundColor(systemDarkTheme)),
                modifier = Modifier.fillMaxSize(),
            )
        }
        ReaderTopInfo(
            state = chromeState,
            settings = effectiveSettings,
            progressDisplay = progressDisplay,
            colors = readerChromeColors(effectiveSettings, systemDarkTheme),
            onStatisticsToggle = if (effectiveSettings.showStatisticsToggle) {
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
            onSasayakiToggle = topPlaybackControl?.onToggle,
            sasayakiPlaying = topPlaybackControl?.isPlaying == true,
            visibility = topInfoVisibility,
            metrics = topChromeMetrics,
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
            progressDisplay = progressDisplay,
            colors = readerChromeColors(effectiveSettings, systemDarkTheme),
            metrics = bottomChromeMetrics,
            focusMode = focusMode,
            sasayakiPlaybackControls = if (hasStartedTtsSession) sasayakiBottomPlaybackControls.copy(visible = sasayakiSettings.showReaderBottomPlaybackControls) else sasayakiBottomPlaybackControls,
            sasayakiPlaying = if (ttsAvailable) ttsState.playing else sasayakiPlayer?.isPlaying == true,
            onTapSafeArea = ::handleReaderTapOutside,
            onSasayakiSkipBackward = { if (ttsAvailable) { if (sasayakiBottomSkipButtonActions.left == ReaderSasayakiBottomSkipButtonAction.Backward) ttsViewModel.playback.previous() else ttsViewModel.playback.next() } else performSasayakiBottomSkipAction(sasayakiBottomSkipButtonActions.left) },
            onSasayakiTogglePlayback = { if (ttsAvailable) ttsViewModel.playback.toggle() else sasayakiPlayer?.togglePlayback() },
            onSasayakiSkipForward = { if (ttsAvailable) { if (sasayakiBottomSkipButtonActions.right == ReaderSasayakiBottomSkipButtonAction.Backward) ttsViewModel.playback.previous() else ttsViewModel.playback.next() } else performSasayakiBottomSkipAction(sasayakiBottomSkipButtonActions.right) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (chromeVisibility.showBottomChrome) ReaderBottomChrome(
            state = chromeState,
            settings = effectiveSettings,
            layout = chromeLayout,
            progressDisplay = progressDisplay,
            colors = readerChromeColors(effectiveSettings, systemDarkTheme),
            onClose = ::closeReader,
            onMenu = stateHolder::toggleReaderMenu,
            menuExpanded = showReaderMenu,
            onDismissMenu = stateHolder::dismissReaderMenu,
            onGoTo = stateHolder::openGoToFromMenu,
            onDisplaySettings = stateHolder::openDisplaySettingsFromMenu,
            onReadingSettings = stateHolder::openAppearanceFromMenu,
            onStatistics = stateHolder::openStatisticsFromMenu,
            onTts = if (ttsAvailable) ({ stateHolder.dismissReaderMenu(); ttsAtReadingPosition(false); showTts = true }) else null,
            onSasayaki = if (sasayakiSettings.enabled && bookRoot != null) {
                {
                    stateHolder.openSasayakiFromMenu(
                        sasayakiDefaultSheetTab(
                            hasAudio = sasayakiPlayer?.hasAudio == true,
                            hasChapters = sasayakiAudiobookInfo.chapters.isNotEmpty(),
                        ),
                    )
                }
            } else {
                null
            },
            metrics = bottomChromeMetrics,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (showAppearance) {
            ReaderAppearanceSheet(
                settings = effectiveSettings,
                profileName = profileState.effectiveProfile.name,
                progressDisplay = progressDisplay,
                onSettingsChange = { transform ->
                    stateHolder.applySettings(transform(stateHolder.effectiveSettings))
                    onReaderSettingsChange(transform)
                },
                sasayakiSettings = sasayakiSettingsState,
                onRetrySasayakiSettings = { sasayakiLoadAttempt++ },
                onSasayakiSettingsChange = ::updateSasayakiSettings,
                fontManager = fontManager,
                onDismiss = stateHolder::dismissAppearance,
            )
        }
        if (showDisplaySettings) {
            DisplaySettingsSheet(onDismiss = stateHolder::dismissDisplaySettings)
        }
        if (showGoTo) {
            ReaderGoToSheet(
                book = book,
                currentPosition = readerPosition.displayedPosition,
                progressDisplay = progressDisplay,
                highlights = highlights.orEmpty(),
                selectedTab = stateHolder.selectedGoToTab,
                onSelectedTabChange = stateHolder::selectGoToTab,
                onChapterJump = { target, fragment ->
                    closeLookupPopupsAndSelection()
                    jumpToPositionWithHistory(target, fragment)
                    stateHolder.dismissGoTo()
                },
                onCharacterJump = { count ->
                    closeLookupPopupsAndSelection()
                    jumpToPositionWithHistory(book.chapterPositionForCharacter(count), null)
                    stateHolder.dismissGoTo()
                },
                onSearchResultJump = { result ->
                    closeLookupPopupsAndSelection()
                    clearSearchHighlight()
                    cancelReaderAutoPage()
                    val chapter = book.chapters[result.chapterIndex]
                    val chapterInfo = book.bookInfo.chapterInfo[chapter.href]
                    val offset = (result.character - (chapterInfo?.currentTotal ?: 0)).coerceAtLeast(0)
                    val count = chapterInfo?.chapterCount ?: 0
                    val target = ReaderChapterPosition(
                        index = result.chapterIndex,
                        progress = if (count > 0) (offset.toDouble() / count).coerceIn(0.0, 1.0) else 0.0,
                    )
                    val statistics = statisticsForSave()
                    val savedPosition = stateHolder.jumpToSearchResult(target, offset, result.matchLength)
                    resetStatisticsBaseline()
                    saveReaderPosition(savedPosition, statistics)
                    stateHolder.dismissGoTo()
                },
                onHighlightJump = { highlight ->
                    closeLookupPopupsAndSelection()
                    val target = ReaderHighlights.positionForCharacter(book.bookInfo, highlight.character)
                    jumpToPositionWithHistory(target)
                    stateHolder.dismissGoTo()
                },
                onHighlightDelete = ::removeHighlight,
                onGalleryImageSelected = { path ->
                    stateHolder.dismissGoTo()
                    book.galleryResourceUrl(path)?.let(::openFullscreenImage)
                },
                onDismiss = stateHolder::dismissGoTo,
            )
        }
        if (showTts && ttsAvailable) {
            TtsSheet(ttsViewModel, ttsBookId, onStart = { ttsAtReadingPosition(true) }, onDismiss = { showTts = false })
        }
        ttsSelection?.takeIf { ttsEnabled }?.let { selection ->
            val draft = remember(selection) {
                TtsWord(java.util.UUID.randomUUID().toString(), selection.text.lineSequence().joinToString(""), "")
            }
            val chapter = readerPosition.displayedPosition.index
            val previewSentence by produceState<TtsSentence?>(null, selection) {
                try { value = ttsViewModel.sourceSentence(book, chapter, selection) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) {
                    value = TtsSentence(chapter, selection.normalizedOffset ?: 0, 0, selection.sentence)
                }
            }
            previewSentence?.let { sentence ->
                TtsWordEditor(ttsViewModel, draft, ttsBookId, sentence, onDismiss = { ttsSelection = null })
            }
        }
        ankiOperationError?.let { error ->
            AlertDialog(onDismissRequest = { ankiOperationError = null },
                title = { Text(stringResource(R.string.dialog_error_title)) },
                text = { ErrorDetailsText(stringResource(R.string.anki_operation_failed).withErrorDetails(error)) },
                confirmButton = { TextButton(onClick = { ankiOperationError = null }) { Text(stringResource(R.string.action_back)) } })
        }
        AnkiOperationErrorDialog(ankiSynchronizationWarning, R.string.anki_sync_failed) { ankiSynchronizationWarning = null }
        if (showSasayaki && sasayakiPlayer != null && sasayakiAudioRepository != null) {
            val confirmedSasayakiSettings = (sasayakiSettingsState as? SettingsLoadState.Loaded)?.value
            if (confirmedSasayakiSettings == null) {
                ReaderBottomPanel(sheetStyle = readerSheetStyle(), onDismiss = stateHolder::dismissSasayaki) {
                    SettingsContent(sasayakiSettingsState, Modifier.fillMaxWidth().weight(1f), { sasayakiLoadAttempt++ }) {}
                }
            } else {
                SasayakiSheet(
                    player = requireNotNull(sasayakiPlayer),
                    audioRepository = sasayakiAudioRepository,
                    settings = confirmedSasayakiSettings,
                    bookTitle = book.title,
                    bookCoverFile = sasayakiCoverFile,
                    audiobookInfo = sasayakiAudiobookInfo,
                    subtitleMatchData = sasayakiSheetMatchData,
                    matchDependencies = bookEntry?.let { entry ->
                        SasayakiMatchDependencies(
                            bookEntry = entry,
                            bookRepository = bookRepository,
                            epubBookParser = appContainer.epubBookParser,
                            characterCount = book.bookInfo.characterCount,
                        )
                    },
                    selectedTab = stateHolder.selectedSasayakiTab,
                    onSelectedTabChange = stateHolder::selectSasayakiTab,
                    onSubtitleMatchUpdated = onSasayakiMatchUpdated,
                    onSettingsChange = ::updateSasayakiSettings,
                    transcriptionState = sasayakiTranscriptionState,
                    transcriptionViewModel = sasayakiTranscriptionViewModel,
                    subtitleExportViewModel = sasayakiSubtitleExportViewModel,
                    onDismiss = stateHolder::dismissSasayaki,
                )
            }
        }
        statisticsLoadFailure?.let { error ->
            AlertDialog(
                onDismissRequest = { statisticsLoadFailure = null },
                text = { ErrorDetailsText(stringResource(R.string.statistics_operation_failed).withErrorDetails(error)) },
                confirmButton = {
                    TextButton(onClick = { statisticsLoadFailure = null }) { Text(stringResource(R.string.action_ok)) }
                },
            )
        }
        if (showStatistics && statisticsState != null) {
            ReaderStatisticsSheet(
                state = requireNotNull(statisticsState),
                currentCharacter = currentDisplayedCharacter(),
                currentChapterEndCharacter = currentChapterEndCharacter(),
                totalCharacters = book.bookInfo.characterCount,
                progressDisplay = progressDisplay,
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

internal enum class ReaderRestoreLoadingPresentation {
    BehindChromeStaticSpinner,
}

internal fun readerRestoreLoadingPresentation(
    isWebViewRestoring: Boolean,
): ReaderRestoreLoadingPresentation? =
    if (isWebViewRestoring) ReaderRestoreLoadingPresentation.BehindChromeStaticSpinner else null

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun SasayakiMatch.toCueRange(): SasayakiCueRange =
    SasayakiCueRange(id = id, start = start, length = length)

internal fun readerSasayakiChapterLoadPosition(
    saveStatistics: () -> ReadingSessions?,
    jumpToTarget: () -> ReaderChapterPosition,
    resetStatisticsBaseline: () -> Unit,
    saveReaderPosition: (ReaderChapterPosition, ReadingSessions?) -> Unit,
): ReaderChapterPosition {
    val statistics = saveStatistics()
    val savedPosition = jumpToTarget()
    saveReaderPosition(savedPosition, statistics)
    resetStatisticsBaseline()
    return savedPosition
}

internal fun readerSasayakiCueProgress(
    book: EpubBook,
    cue: SasayakiMatch,
): Double {
    val chapter = book.chapters.getOrNull(cue.chapterIndex) ?: return 0.0
    val chapterCount = book.bookInfo.chapterInfo[chapter.href]?.chapterCount
        ?.takeIf { it > 0 }
        ?: return 0.0
    return cue.start.toDouble().div(chapterCount).coerceIn(0.0, 1.0)
}

internal fun readerSasayakiCrossChapterInitialProgress(
    book: EpubBook,
    cue: SasayakiMatch,
    currentChapterIndex: Int,
): Double =
    if (cue.chapterIndex < currentChapterIndex) {
        readerSasayakiCueProgress(book, cue)
    } else {
        0.0
    }

internal fun readerSasayakiCueAfterRendererTermination(
    pendingCue: PendingSasayakiCue?,
    pendingRestoreCue: PendingSasayakiCue?,
    pendingTargetMediaCue: SasayakiMatch?,
    lastCue: PendingSasayakiCue?,
): PendingSasayakiCue? =
    pendingCue ?: pendingRestoreCue ?: pendingTargetMediaCue?.let {
        PendingSasayakiCue(it, reveal = true, source = SasayakiCueRevealSource.DirectJump)
    } ?: lastCue?.copy(reveal = false)

internal data class PendingSasayakiCue(
    val cue: SasayakiMatch,
    val reveal: Boolean,
    val source: SasayakiCueRevealSource,
)

private data class PendingSasayakiMatchUpdate(
    val data: SasayakiMatchData?,
    val preserveLayout: Boolean,
)

private data class PendingReaderPlaybackTargetRestore(
    val chapterIndex: Int,
    val range: SasayakiCueRange,
    val inspectMedia: Boolean,
    val audiobookCue: SasayakiMatch?,
    val canPresent: () -> Boolean,
    val result: CompletableDeferred<ReaderPlaybackTargetRestoreResult>,
)

private fun SasayakiPlaybackData?.hasStoredAudioSource(): Boolean =
    this?.audioUri?.isNotBlank() == true || this?.audioFileName?.isNotBlank() == true
