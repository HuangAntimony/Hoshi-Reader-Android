package moe.antimony.hoshi.features.dictionary

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.audio.AudioRequestHandler
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.audio.WordAudioPlayer
import moe.antimony.hoshi.features.anki.AnkiMiningContext
import moe.antimony.hoshi.features.anki.AnkiViewModel
import moe.antimony.hoshi.features.reader.ReaderLookupPopupBridgeCallbackHolder
import moe.antimony.hoshi.features.reader.ReaderLookupPopupBridgeCallbacks
import moe.antimony.hoshi.features.reader.ReaderLookupPopupBridgeMessage
import moe.antimony.hoshi.features.reader.ReaderLookupPopupFramePayload
import moe.antimony.hoshi.features.reader.ReaderLookupPopupIframeSync
import moe.antimony.hoshi.features.reader.ReaderLookupPopupResourceHandler
import moe.antimony.hoshi.features.reader.ReaderLookupPopupWebBridge
import moe.antimony.hoshi.features.reader.ReaderLookupPopupViewport
import moe.antimony.hoshi.features.reader.ReaderPopupHistoryCounts
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.androidPixelsToCssPixels
import moe.antimony.hoshi.features.reader.readerLookupPopupIframeUrl
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.hoshiSingleLineTextFieldLineLimits
import moe.antimony.hoshi.ui.hoshiTextFieldCursorBrush
import moe.antimony.hoshi.ui.rememberSyncedTextFieldState
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults
import org.json.JSONObject.quote
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DictionaryPopupTopInset = 118.0
private const val DictionaryPopupBottomInset = 0.0
private val DictionaryPullResetThreshold = DictionaryPullResetTriggerDistanceDp.dp

internal fun dictionarySearchKeyboardOptions(): KeyboardOptions = KeyboardOptions(
    imeAction = ImeAction.Search,
    showKeyboardOnFocus = true,
    hintLocales = LocaleList(Locale("ja-JP")),
)

internal fun dictionarySearchPopupOptions(
    readerSettings: ReaderSettings,
    dictionarySettings: DictionarySettings,
    darkMode: Boolean,
    audioSettings: AudioSettings,
    topInset: Double = DictionaryPopupTopInset,
): LookupPopupOptions = LookupPopupOptions(
    isVertical = false,
    isFullWidth = false,
    width = readerSettings.popupWidth,
    height = readerSettings.popupHeight,
    swipeToDismiss = readerSettings.popupSwipeToDismiss,
    swipeThreshold = readerSettings.popupSwipeThreshold,
    reducedMotionScrolling = readerSettings.popupReducedMotionScrolling,
    reducedMotionScrollPercent = readerSettings.popupReducedMotionScrollPercent,
    reducedMotionSwipeThreshold = readerSettings.popupReducedMotionSwipeThreshold,
    popupScale = readerSettings.popupScale,
    popupActionBar = false,
    topInset = topInset,
    bottomInset = DictionaryPopupBottomInset,
    dictionarySettings = dictionarySettings,
    darkMode = darkMode,
    eInkMode = readerSettings.eInkMode,
    audioSettings = audioSettings,
)

@Composable
fun DictionarySearchView(
    readerSettings: ReaderSettings,
    focusRequestKey: Int = 0,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val appContainer = LocalHoshiUiDependencies.current
    val assets = remember(context) { LookupPopupAssets.load(context) }
    val searchViewModel: DictionarySearchViewModel = hiltViewModel()
    val ankiViewModel: AnkiViewModel = hiltViewModel()
    val uiState by searchViewModel.uiState.collectAsStateWithLifecycle()
    val ankiUiState by ankiViewModel.uiState.collectAsStateWithLifecycle()
    var rootIframeAtTop by remember { mutableStateOf(true) }
    var childHistories by remember { mutableStateOf<Map<String, ReaderPopupHistoryCounts>>(emptyMap()) }
    var iframeHostWebView by remember { mutableStateOf<WebView?>(null) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var searchBarBottomDp by remember { mutableStateOf(0.0) }
    var pullDistancePx by remember { mutableFloatStateOf(0f) }
    var localFocusRequestKey by remember { mutableIntStateOf(0) }
    val localAudioRepository = appContainer.localAudioRepository
    val fontManager = appContainer.readerFontManager
    val fontFaceCss = fontManager.popupFontFaceCss()
    val readerPopupBridgeHolder = remember { ReaderLookupPopupBridgeCallbackHolder() }
    val popupDarkMode = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val popupOptions = dictionarySearchPopupOptions(
        readerSettings = readerSettings,
        dictionarySettings = uiState.dictionarySettings,
        darkMode = popupDarkMode,
        audioSettings = uiState.audioSettings,
        topInset = searchBarBottomDp,
    )
    val viewport = remember(viewportSize, density) {
        ReaderLookupPopupViewport(
            width = with(density) { viewportSize.width.toDp().value.toDouble() },
            height = with(density) { viewportSize.height.toDp().value.toDouble() },
        )
    }
    val themedPopups = remember(
        uiState.popups,
        popupDarkMode,
        readerSettings.eInkMode,
        uiState.audioSettings,
        readerSettings.popupScale,
    ) {
        uiState.popups.withLookupPopupVisualOptions(
            darkMode = popupDarkMode,
            eInkMode = readerSettings.eInkMode,
            audioSettings = uiState.audioSettings,
            popupScale = readerSettings.popupScale,
        )
    }
    val readerPopupIframeDocument = remember(
        uiState.dictionaryStyles,
        uiState.dictionarySettings,
        readerSettings.popupSwipeToDismiss,
        readerSettings.popupSwipeThreshold,
        readerSettings.popupReducedMotionScrolling,
        readerSettings.popupReducedMotionScrollPercent,
        readerSettings.popupReducedMotionSwipeThreshold,
        popupDarkMode,
        readerSettings.eInkMode,
        uiState.audioSettings,
        ankiUiState.popupSettings,
        fontFaceCss,
        readerSettings.popupScale,
    ) {
        LookupPopupHtml.renderIframeDocument(
            assets = null,
            dictionaryStyles = uiState.dictionaryStyles,
            settings = uiState.dictionarySettings,
            swipeToDismiss = readerSettings.popupSwipeToDismiss,
            swipeThreshold = readerSettings.popupSwipeThreshold,
            reducedMotionScrolling = readerSettings.popupReducedMotionScrolling,
            reducedMotionScrollPercent = readerSettings.popupReducedMotionScrollPercent,
            reducedMotionSwipeThreshold = readerSettings.popupReducedMotionSwipeThreshold,
            darkMode = popupDarkMode,
            eInkMode = readerSettings.eInkMode,
            audioSettings = uiState.audioSettings,
            ankiSettings = ankiUiState.popupSettings,
            fontFaceCss = fontFaceCss,
            popupScale = readerSettings.popupScale,
        )
    }
    val currentReaderPopupIframeDocument = rememberUpdatedState(readerPopupIframeDocument)
    val readerPopupIframeUrl = remember(readerPopupIframeDocument) {
        readerLookupPopupIframeUrl(readerPopupIframeDocument.hashCode())
    }
    val readerPopupResourceHandler = remember(context, assets, fontManager, localAudioRepository) {
        ReaderLookupPopupResourceHandler(
            context = context.applicationContext,
            assets = assets,
            fontManager = fontManager,
            audioRequestHandler = AudioRequestHandler(localAudioRepository),
            imageRequestHandler = DictionaryImageRequestHandler(),
            iframeDocument = { currentReaderPopupIframeDocument.value },
        )
    }
    val iframePayloads = remember(
        uiState.results,
        themedPopups,
        childHistories,
        viewport,
        searchBarBottomDp,
        popupDarkMode,
        readerSettings.eInkMode,
        readerPopupIframeUrl,
        uiState.resultClearSelectionSignal,
        uiState.backCount,
        uiState.forwardCount,
    ) {
        dictionarySearchIframePayloads(
            rootResults = uiState.results,
            childPopups = themedPopups,
            childHistories = childHistories,
            rootHistory = ReaderPopupHistoryCounts(
                backCount = uiState.backCount,
                forwardCount = uiState.forwardCount,
            ),
            viewport = viewport,
            searchBarBottomDp = searchBarBottomDp,
            darkMode = popupDarkMode,
            eInkMode = readerSettings.eInkMode,
            iframeUrl = readerPopupIframeUrl,
            rootClearSelectionSignal = uiState.resultClearSelectionSignal,
        )
    }
    fun requestSearchFocus() {
        localFocusRequestKey += 1
    }
    val runLookup = {
        childHistories = emptyMap()
        rootIframeAtTop = true
        searchViewModel.runLookup()
    }
    val lookupPopup = { selection: moe.antimony.hoshi.features.reader.ReaderSelectionData ->
        searchViewModel.createPopup(
            selection = selection,
            options = popupOptions,
        )
    }
    fun setIframePopups(next: List<LookupPopupItem>) {
        val activeIds = next.mapTo(mutableSetOf()) { it.id }
        childHistories = childHistories.filterKeys(activeIds::contains)
        searchViewModel.setPopups(next)
    }
    fun popupIndex(popupId: String): Int =
        uiState.popups.indexOfFirst { it.id == popupId }

    fun popupById(popupId: String): LookupPopupItem? =
        uiState.popups.firstOrNull { it.id == popupId }

    fun replyIframeMessage(popupId: String, messageId: String, bodyJson: String) {
        iframeHostWebView?.evaluateJavascript(
            """
                window.hoshiReaderPopupHost &&
                window.hoshiReaderPopupHost.resolveMessage(${quote(popupId)}, ${quote(messageId)}, $bodyJson)
            """.trimIndent(),
            null,
        )
    }
    fun highlightIframeSelection(popupId: String, highlightCount: Int) {
        iframeHostWebView?.evaluateJavascript(
            """
                window.hoshiReaderPopupHost &&
                window.hoshiReaderPopupHost.highlightSelection(${quote(popupId)}, $highlightCount)
            """.trimIndent(),
            null,
        )
    }
    fun navigateRootIframeBack() {
        iframeHostWebView?.evaluateJavascript(
            "window.hoshiReaderPopupHost && window.hoshiReaderPopupHost.navigateBack(${quote(DictionarySearchRootPopupId)})",
            null,
        )
    }
    fun navigateRootIframeForward() {
        iframeHostWebView?.evaluateJavascript(
            "window.hoshiReaderPopupHost && window.hoshiReaderPopupHost.navigateForward(${quote(DictionarySearchRootPopupId)})",
            null,
        )
    }
    fun handleReaderPopupBridgeMessage(message: ReaderLookupPopupBridgeMessage) {
        when (message) {
            is ReaderLookupPopupBridgeMessage.OpenLink -> context.openPopupExternalLink(message.url)
            is ReaderLookupPopupBridgeMessage.TapOutside -> {
                if (message.popupId == DictionarySearchRootPopupId) {
                    childHistories = emptyMap()
                    searchViewModel.dismissRootPopup()
                } else {
                    val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                    setIframePopups(closeChildPopupsAndClearSelection(uiState.popups, index))
                }
            }
            is ReaderLookupPopupBridgeMessage.SwipeDismiss -> {
                if (message.popupId == DictionarySearchRootPopupId) {
                    searchViewModel.dismissRootPopup()
                } else {
                    val dismissal = dictionarySearchIframePopupsAfterSwipeDismiss(
                        popups = uiState.popups,
                        popupId = message.popupId,
                    )
                    if (dismissal.clearRootSelection) {
                        childHistories = emptyMap()
                        searchViewModel.dismissRootPopup()
                    } else {
                        setIframePopups(dismissal.popups)
                    }
                }
            }
            is ReaderLookupPopupBridgeMessage.TextSelected -> {
                if (message.popupId == DictionarySearchRootPopupId) {
                    val highlightCount = searchViewModel.openRootPopup(message.selection, popupOptions)
                    highlightIframeSelection(message.popupId, highlightCount ?: 0)
                    return
                }
                val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                val nextPopups = closeChildPopups(uiState.popups, index)
                val lookup = lookupPopup(message.selection)
                if (lookup == null) {
                    highlightIframeSelection(message.popupId, 0)
                } else {
                    val (childPopup, highlightCount) = lookup
                    setIframePopups(nextPopups + childPopup)
                    highlightIframeSelection(message.popupId, highlightCount)
                }
            }
            is ReaderLookupPopupBridgeMessage.PlayWordAudio -> {
                WordAudioPlayer.get(context).play(message.url, message.mode)
            }
            is ReaderLookupPopupBridgeMessage.MineEntry -> {
                val messageId = message.messageId ?: return
                val miningContext = if (message.popupId == DictionarySearchRootPopupId) {
                    AnkiMiningContext(sentence = uiState.lastQuery.ifBlank { uiState.query })
                } else {
                    popupById(message.popupId)?.state?.ankiContext ?: return
                }
                ankiViewModel.mineEntryAsync(message.payloadJson, miningContext) { mined ->
                    replyIframeMessage(message.popupId, messageId, mined.toString())
                }
            }
            is ReaderLookupPopupBridgeMessage.DuplicateCheck -> {
                val messageId = message.messageId ?: return
                ankiViewModel.duplicateCheckAsync(message.expression) { isDuplicate ->
                    replyIframeMessage(message.popupId, messageId, isDuplicate.toString())
                }
            }
            is ReaderLookupPopupBridgeMessage.LookupRedirect -> {
                val messageId = message.messageId ?: return
                val results = if (message.popupId == DictionarySearchRootPopupId) {
                    childHistories = emptyMap()
                    searchViewModel.lookupRootRedirect(message.query)
                } else {
                    val popup = popupById(message.popupId) ?: return
                    searchViewModel.lookupRedirect(message.query).also { redirected ->
                        if (redirected.isNotEmpty()) {
                            setIframePopups(
                                uiState.popups.map { existing ->
                                    if (existing.id == popup.id) {
                                        existing.copy(state = existing.state.copy(results = redirected))
                                    } else {
                                        existing
                                    }
                                },
                            )
                            val current = childHistories[message.popupId] ?: ReaderPopupHistoryCounts()
                            childHistories = childHistories + (
                                message.popupId to current.copy(
                                    backCount = current.backCount + 1,
                                    forwardCount = 0,
                                )
                                )
                        }
                    }
                }
                replyIframeMessage(message.popupId, messageId, results.size.toString())
            }
            is ReaderLookupPopupBridgeMessage.GetEntry -> {
                val entry = searchViewModel.entryForPopup(message.popupId, message.index)
                replyIframeMessage(
                    popupId = message.popupId,
                    messageId = message.messageId ?: return,
                    bodyJson = entry?.let(LookupPopupHtml::entryJsonString) ?: "null",
                )
            }
            is ReaderLookupPopupBridgeMessage.ContentReady -> Unit
            is ReaderLookupPopupBridgeMessage.PopupScrolled -> {
                if (message.popupId == DictionarySearchRootPopupId) {
                    if (uiState.popups.isNotEmpty()) {
                        childHistories = emptyMap()
                        searchViewModel.dismissRootPopup()
                    } else {
                        searchViewModel.closePopups()
                    }
                } else {
                    val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                    setIframePopups(closeChildPopupsForScrolledParent(uiState.popups, index))
                }
            }
            is ReaderLookupPopupBridgeMessage.ScrollState -> {
                if (message.popupId == DictionarySearchRootPopupId) {
                    rootIframeAtTop = message.atTop
                }
            }
            is ReaderLookupPopupBridgeMessage.NavigateBack -> {
                if (message.popupId == DictionarySearchRootPopupId) {
                    searchViewModel.navigateBack()
                } else {
                    val current = childHistories[message.popupId] ?: return
                    if (current.backCount > 0) {
                        childHistories = childHistories + (
                            message.popupId to current.copy(
                                backCount = current.backCount - 1,
                                forwardCount = current.forwardCount + 1,
                            )
                            )
                    }
                }
            }
            is ReaderLookupPopupBridgeMessage.NavigateForward -> {
                if (message.popupId == DictionarySearchRootPopupId) {
                    searchViewModel.navigateForward()
                } else {
                    val current = childHistories[message.popupId] ?: return
                    if (current.forwardCount > 0) {
                        childHistories = childHistories + (
                            message.popupId to current.copy(
                                backCount = current.backCount + 1,
                                forwardCount = current.forwardCount - 1,
                            )
                            )
                    }
                }
            }
            is ReaderLookupPopupBridgeMessage.SasayakiReplayCue,
            is ReaderLookupPopupBridgeMessage.SasayakiTogglePlayback,
            is ReaderLookupPopupBridgeMessage.SasayakiPlayForward,
            -> Unit
        }
    }
    readerPopupBridgeHolder.callbacks = ReaderLookupPopupBridgeCallbacks(::handleReaderPopupBridgeMessage)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onSizeChanged { viewportSize = it },
    ) {
        when {
            uiState.hasResults -> {
                DictionarySearchIframeHost(
                    callbackHolder = readerPopupBridgeHolder,
                    resourceHandler = readerPopupResourceHandler,
                    rootAtTop = rootIframeAtTop,
                    popupFrames = iframePayloads,
                    onWebViewChanged = { iframeHostWebView = it },
                    onPullStarted = { keyboardController?.hide() },
                    onPullDistance = { distance -> pullDistancePx = distance },
                    onPullReleased = { distance ->
                        when (
                            dictionaryPullResetAction(
                                distancePx = distance,
                                thresholdPx = with(density) { DictionaryPullResetThreshold.toPx() },
                                hasQuery = uiState.query.isNotEmpty(),
                            )
                        ) {
                            DictionaryPullResetAction.ResetAndFocus -> {
                                childHistories = emptyMap()
                                rootIframeAtTop = true
                                searchViewModel.resetSearch()
                                requestSearchFocus()
                            }
                            DictionaryPullResetAction.FocusOnly -> requestSearchFocus()
                            DictionaryPullResetAction.None -> Unit
                        }
                        pullDistancePx = 0f
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .observeDictionaryHistorySwipe(
                            backCount = uiState.backCount,
                            forwardCount = uiState.forwardCount,
                            canStart = { position ->
                                dictionarySearchHistorySwipeGestureCanStart(
                                    popups = iframePayloads,
                                    x = with(density) { position.x.toDp().value.toDouble() },
                                    y = with(density) { position.y.toDp().value.toDouble() },
                                )
                            },
                            onBack = ::navigateRootIframeBack,
                            onForward = ::navigateRootIframeForward,
                        ),
                )
                ReaderLookupPopupIframeSync(
                    webView = iframeHostWebView,
                    payloads = iframePayloads,
                    rootHighlight = null,
                )
            }
            uiState.errorMessage != null -> DictionarySearchMessage(
                text = requireNotNull(uiState.errorMessage).asString(),
                modifier = Modifier.fillMaxSize(),
            )
            uiState.hasSearched && !uiState.isSearching -> DictionarySearchMessage(
                text = "",
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (uiState.hasResults && pullDistancePx > 1f) {
            DictionaryPullResetIndicator(
                distancePx = pullDistancePx,
                thresholdPx = with(density) { DictionaryPullResetThreshold.toPx() },
                topPaddingDp = searchBarBottomDp,
                hasQuery = uiState.query.isNotEmpty(),
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        DictionarySearchTopBar(
            query = uiState.query,
            isSearching = uiState.isSearching,
            onQueryChange = searchViewModel::updateQuery,
            onSubmit = runLookup,
            focusRequestKey = focusRequestKey to localFocusRequestKey,
            onBottomChanged = { bottomPx ->
                searchBarBottomDp = with(density) { bottomPx.toDp().value.toDouble() }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DictionarySearchIframeHost(
    callbackHolder: ReaderLookupPopupBridgeCallbackHolder,
    resourceHandler: ReaderLookupPopupResourceHandler,
    rootAtTop: Boolean,
    popupFrames: List<ReaderLookupPopupFramePayload>,
    onWebViewChanged: (WebView) -> Unit,
    onPullStarted: () -> Unit,
    onPullDistance: (Float) -> Unit,
    onPullReleased: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentRootAtTop = rememberUpdatedState(rootAtTop)
    val currentPopupFrames = rememberUpdatedState(popupFrames)
    val currentOnPullStarted = rememberUpdatedState(onPullStarted)
    val currentOnPullDistance = rememberUpdatedState(onPullDistance)
    val currentOnPullReleased = rememberUpdatedState(onPullReleased)
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { viewContext ->
            WebView(viewContext).apply {
                applyHoshiWebViewSecurityDefaults()
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                ReaderLookupPopupWebBridge.install(this, callbackHolder)
                webViewClient = LookupPopupIframeWebViewClient(resourceHandler)
                installDictionaryPullTouchObserver(
                    rootAtTop = { currentRootAtTop.value },
                    pullGestureCanStart = { event ->
                        val density = resources.displayMetrics.density
                        dictionarySearchPullGestureCanStart(
                            popups = currentPopupFrames.value,
                            x = androidPixelsToCssPixels(event.x, density).toDouble(),
                            y = androidPixelsToCssPixels(event.y, density).toDouble(),
                        )
                    },
                    onPullStarted = { currentOnPullStarted.value() },
                    onPullDistance = { currentOnPullDistance.value(it) },
                    onPullReleased = { currentOnPullReleased.value(it) },
                )
                loadDataWithBaseURL(
                    "https://appassets.androidplatform.net/dictionary/iframe-host.html",
                    lookupPopupIframeHostHtml(),
                    "text/html",
                    "UTF-8",
                    null,
                )
                onWebViewChanged(this)
            }
        },
        update = { webView ->
            webView.webViewClient = LookupPopupIframeWebViewClient(resourceHandler)
            onWebViewChanged(webView)
        },
    )
}

@SuppressLint("ClickableViewAccessibility")
private fun WebView.installDictionaryPullTouchObserver(
    rootAtTop: () -> Boolean,
    pullGestureCanStart: (MotionEvent) -> Boolean,
    onPullStarted: () -> Unit,
    onPullDistance: (Float) -> Unit,
    onPullReleased: (Float) -> Unit,
) {
    var downY = 0f
    var canStart = false
    var active = false
    var lastDistance = 0f
    setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                canStart = rootAtTop() && pullGestureCanStart(event)
                active = false
                lastDistance = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val distance = event.y - downY
                if (canStart && rootAtTop() && distance > 0f) {
                    if (!active && distance > 8f) {
                        active = true
                        onPullStarted()
                    }
                    if (active) {
                        lastDistance = distance
                        onPullDistance(distance)
                    }
                } else if (active) {
                    active = false
                    lastDistance = 0f
                    onPullDistance(0f)
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                if (active) {
                    onPullReleased(lastDistance)
                } else {
                    onPullDistance(0f)
                }
                active = false
                canStart = false
                lastDistance = 0f
            }
        }
        false
    }
}

@Composable
private fun DictionaryPullResetIndicator(
    distancePx: Float,
    thresholdPx: Float,
    topPaddingDp: Double,
    hasQuery: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val reached = distancePx >= thresholdPx
    val label = when {
        hasQuery && reached -> R.string.dictionary_release_clear
        hasQuery -> R.string.dictionary_pull_clear
        reached -> R.string.dictionary_release_show_keyboard
        else -> R.string.dictionary_pull_show_keyboard
    }
    Surface(
        modifier = modifier.padding(top = topPaddingDp.dp + 8.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Text(
            text = stringResource(label),
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = with(density) {
                    (distancePx.coerceAtMost(thresholdPx) / 10f).toDp().coerceAtLeast(6.dp)
                },
            ),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private fun Modifier.observeDictionaryHistorySwipe(
    backCount: Int,
    forwardCount: Int,
    canStart: (Offset) -> Boolean = { true },
    onBack: () -> Unit,
    onForward: () -> Unit,
): Modifier = pointerInput(backCount, forwardCount, canStart) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!canStart(down.position)) {
            do {
                val event = awaitPointerEvent(pass = PointerEventPass.Final)
            } while (event.changes.any { it.pressed })
            return@awaitEachGesture
        }
        var lastPosition = down.position
        var totalX = 0f
        var totalY = 0f
        do {
            val event = awaitPointerEvent(pass = PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
            if (change != null) {
                val currentPosition = change.position
                val delta = currentPosition - lastPosition
                totalX += delta.x
                totalY += delta.y
                lastPosition = currentPosition
            }
        } while (event.changes.any { it.pressed })

        if (abs(totalX) > 30f && abs(totalX) > abs(totalY) * 1.75f) {
            if (totalX > 0 && backCount > 0) {
                onBack()
            } else if (totalX < 0 && forwardCount > 0) {
                onForward()
            }
        }
    }
}

@Composable
private fun DictionarySearchTopBar(
    query: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequestKey: Any,
    onBottomChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .statusBarsPadding()
            .onGloballyPositioned { coordinates ->
                onBottomChanged((coordinates.positionInRoot().y + coordinates.size.height).roundToInt())
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            DictionarySearchBar(
                query = query,
                isSearching = isSearching,
                onQueryChange = onQueryChange,
                onSubmit = onSubmit,
                focusRequestKey = focusRequestKey,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
    }
}

@Composable
private fun DictionarySearchBar(
    query: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequestKey: Any,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(start = 16.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchGlyph(modifier = Modifier.size(20.dp))
            Box(modifier = Modifier.weight(1f)) {
                val fieldForegroundColor = MaterialTheme.colorScheme.onSurface
                val fieldScrollState = rememberScrollState()
                val fieldState = rememberSyncedTextFieldState(
                    value = query,
                    onValueChange = onQueryChange,
                    scrollState = fieldScrollState,
                )
                LaunchedEffect(focusRequestKey, fieldState) {
                    focusRequester.requestFocus()
                    fieldState.selectAllText()
                    keyboardController?.show()
                }
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.dictionary_search_placeholder),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                BasicTextField(
                    state = fieldState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                                onSubmit()
                                true
                            } else {
                                false
                            }
                        },
                    enabled = !isSearching,
                    lineLimits = hoshiSingleLineTextFieldLineLimits(),
                    scrollState = fieldScrollState,
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = fieldForegroundColor),
                    cursorBrush = hoshiTextFieldCursorBrush(fieldForegroundColor),
                    keyboardOptions = dictionarySearchKeyboardOptions(),
                    onKeyboardAction = { onSubmit() },
                )
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(34.dp),
                ) {
                    ClearGlyph(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

private fun TextFieldState.selectAllText() {
    edit {
        selection = TextRange(0, length)
    }
}

@Composable
private fun SearchGlyph(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Rounded.Search,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun ClearGlyph(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Rounded.Cancel,
        contentDescription = stringResource(R.string.action_clear),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun DictionarySearchMessage(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Column(
        modifier = modifier.padding(top = 112.dp, start = 28.dp, end = 28.dp),
    ) {
        Text(text = text, color = MaterialTheme.colorScheme.error)
    }
}
