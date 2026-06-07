package moe.antimony.hoshi.features.dictionary

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.manhhao.hoshi.LookupResult
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.ProcessTextLookupRequest
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.dictionary.LookupEngine
import moe.antimony.hoshi.features.audio.AudioRequestHandler
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.audio.AudioSettingsRepository
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.audio.WordAudioPlayer
import moe.antimony.hoshi.features.anki.AnkiViewModel
import moe.antimony.hoshi.features.reader.ReaderLookupPopupBridgeCallbackHolder
import moe.antimony.hoshi.features.reader.ReaderLookupPopupBridgeCallbacks
import moe.antimony.hoshi.features.reader.ReaderLookupPopupBridgeMessage
import moe.antimony.hoshi.features.reader.ReaderLookupPopupIframeSync
import moe.antimony.hoshi.features.reader.ReaderLookupPopupResourceHandler
import moe.antimony.hoshi.features.reader.ReaderLookupPopupViewport
import moe.antimony.hoshi.features.reader.ReaderLookupPopupWebBridge
import moe.antimony.hoshi.features.reader.ReaderPopupHistoryCounts
import moe.antimony.hoshi.features.reader.ReaderFontManager
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.ReaderSettingsRepository
import moe.antimony.hoshi.features.reader.readerLookupPopupFramePayloads
import moe.antimony.hoshi.features.reader.readerLookupPopupIframeUrl
import moe.antimony.hoshi.features.reader.usesDarkInterface
import moe.antimony.hoshi.features.reader.usesDarkSystemBarIcons
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults
import kotlin.math.min

internal class ProcessTextLookupDependencies @Inject constructor(
    val readerSettingsRepository: ReaderSettingsRepository,
    val dictionaryRepository: DictionaryRepository,
    val dictionarySettingsRepository: DictionarySettingsRepository,
    val audioSettingsRepository: AudioSettingsRepository,
    val localAudioRepository: LocalAudioRepository,
    val readerFontManager: ReaderFontManager,
)

@AndroidEntryPoint
class ProcessTextLookupActivity : ComponentActivity() {
    @Inject internal lateinit var dependencies: ProcessTextLookupDependencies

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = ProcessTextLookupRequest.fromIntent(intent) ?: run {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setFinishOnTouchOutside(true)

        setContent {
            var readerSettings by remember { mutableStateOf<ReaderSettings?>(null) }
            LaunchedEffect(dependencies) {
                dependencies.readerSettingsRepository.settings.collect { settings ->
                    readerSettings = settings
                }
            }
            val loadedReaderSettings = readerSettings ?: return@setContent
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            HoshiReaderTheme(
                darkTheme = loadedReaderSettings.usesDarkInterface(systemDark),
                eInkMode = loadedReaderSettings.eInkMode,
                useDarkSystemBarIcons = loadedReaderSettings.usesDarkSystemBarIcons(systemDark),
            ) {
                ProcessTextLookupOverlay(
                    query = request.query,
                    readerSettings = loadedReaderSettings,
                    dependencies = dependencies,
                    onClose = ::finish,
                )
            }
        }
    }
}

@Composable
private fun ProcessTextLookupOverlay(
    query: String,
    readerSettings: ReaderSettings,
    dependencies: ProcessTextLookupDependencies,
    onClose: () -> Unit,
) {
    var popups by remember(query) { mutableStateOf<List<LookupPopupItem>>(emptyList()) }
    var popupHistories by remember(query) { mutableStateOf<Map<String, ReaderPopupHistoryCounts>>(emptyMap()) }
    var error by remember(query) { mutableStateOf<Throwable?>(null) }
    var iframeHostWebView by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current
    val darkMode = MaterialTheme.colorScheme.background.luminanceForPopup() < 0.5f
    val density = LocalDensity.current
    val topInset = with(density) {
        WindowInsets.statusBars.getTop(this).toDp().value
    }
    val ankiViewModel: AnkiViewModel = hiltViewModel()
    val ankiUiState by ankiViewModel.uiState.collectAsStateWithLifecycle()
    val assets = remember(context) { LookupPopupAssets.load(context) }
    val fontFaceCss = dependencies.readerFontManager.popupFontFaceCss()
    val popupSettings = popups.firstOrNull()?.state
    val readerPopupIframeDocument = remember(
        popupSettings?.dictionaryStyles,
        popupSettings?.dictionarySettings,
        popupSettings?.audioSettings,
        readerSettings.popupSwipeThreshold,
        readerSettings.popupReducedMotionScrolling,
        readerSettings.popupReducedMotionScrollPercent,
        readerSettings.popupReducedMotionSwipeThreshold,
        readerSettings.popupScale,
        darkMode,
        readerSettings.eInkMode,
        ankiUiState.popupSettings,
        fontFaceCss,
    ) {
        LookupPopupHtml.renderIframeDocument(
            assets = null,
            dictionaryStyles = popupSettings?.dictionaryStyles.orEmpty(),
            settings = popupSettings?.dictionarySettings ?: DictionarySettings(),
            swipeToDismiss = true,
            swipeThreshold = readerSettings.popupSwipeThreshold,
            reducedMotionScrolling = readerSettings.popupReducedMotionScrolling,
            reducedMotionScrollPercent = readerSettings.popupReducedMotionScrollPercent,
            reducedMotionSwipeThreshold = readerSettings.popupReducedMotionSwipeThreshold,
            darkMode = darkMode,
            eInkMode = readerSettings.eInkMode,
            audioSettings = popupSettings?.audioSettings ?: AudioSettings(),
            ankiSettings = ankiUiState.popupSettings,
            fontFaceCss = fontFaceCss,
            popupScale = readerSettings.popupScale,
        )
    }
    val currentReaderPopupIframeDocument = rememberUpdatedState(readerPopupIframeDocument)
    val readerPopupIframeUrl = remember(readerPopupIframeDocument) {
        readerLookupPopupIframeUrl(readerPopupIframeDocument.hashCode())
    }
    val readerPopupResourceHandler = remember(
        context,
        assets,
        dependencies.readerFontManager,
        dependencies.localAudioRepository,
    ) {
        ReaderLookupPopupResourceHandler(
            context = context.applicationContext,
            assets = assets,
            fontManager = dependencies.readerFontManager,
            audioRequestHandler = AudioRequestHandler(dependencies.localAudioRepository),
            imageRequestHandler = DictionaryImageRequestHandler(),
            iframeDocument = { currentReaderPopupIframeDocument.value },
        )
    }
    val readerPopupBridgeHolder = remember { ReaderLookupPopupBridgeCallbackHolder() }

    LaunchedEffect(query, readerSettings, darkMode) {
        runCatching {
            withContext(Dispatchers.IO) {
                dependencies.dictionaryRepository.rebuildLookupQuery()
                val dictionarySettings = dependencies.dictionarySettingsRepository.settings.first().normalized()
                val audioSettings = dependencies.audioSettingsRepository.settings.first()
                val styles = currentDictionaryStyles()
                val selection = ReaderSelectionData(
                    text = query,
                    sentence = query,
                    rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0),
                    normalizedOffset = 0,
                    sentenceOffset = 0,
                )
                val results = LookupEngine.lookup(
                    query,
                    dictionarySettings.maxResults,
                    dictionarySettings.scanLength,
                )
                lookupPopupItem(
                    selection = selection,
                    results = results,
                    dictionaryStyles = styles,
                    dictionarySettings = dictionarySettings,
                    audioSettings = audioSettings,
                    readerSettings = readerSettings,
                    darkMode = darkMode,
                )
            }
        }.onSuccess { popup ->
            if (popup == null) {
                onClose()
            } else {
                popups = listOf(popup)
            }
        }.onFailure {
            error = it
            onClose()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor.Transparent),
    ) {
        val viewport = ReaderLookupPopupViewport(
            width = maxWidth.value.toDouble(),
            height = maxHeight.value.toDouble(),
        )
        val themedPopups = popups.withLookupPopupVisualOptions(
            darkMode = darkMode,
            eInkMode = readerSettings.eInkMode,
            audioSettings = popupSettings?.audioSettings ?: AudioSettings(),
            popupScale = readerSettings.popupScale,
        )
        val displayedPopups = processTextLookupDisplayedPopups(
            popups = themedPopups,
            viewport = viewport,
            topInset = topInset.toDouble(),
        )
        val iframePayloads = readerLookupPopupFramePayloads(
            popups = displayedPopups,
            histories = popupHistories,
            viewport = viewport,
            sasayakiWasPaused = false,
            sasayakiIsPlaying = false,
            iframeUrl = readerPopupIframeUrl,
            rootSelectionHighlight = null,
        )
        fun setIframePopups(next: List<LookupPopupItem>) {
            val activeIds = next.mapTo(mutableSetOf()) { it.id }
            popupHistories = popupHistories.filterKeys(activeIds::contains)
            if (next.isEmpty()) {
                onClose()
            } else {
                popups = next
            }
        }
        fun popupIndex(popupId: String): Int =
            popups.indexOfFirst { it.id == popupId }

        fun popupById(popupId: String): LookupPopupItem? =
            popups.firstOrNull { it.id == popupId }

        fun replyIframeMessage(popupId: String, messageId: String, bodyJson: String) {
            iframeHostWebView?.evaluateJavascript(
                """
                    window.hoshiReaderPopupHost &&
                    window.hoshiReaderPopupHost.resolveMessage(${org.json.JSONObject.quote(popupId)}, ${org.json.JSONObject.quote(messageId)}, $bodyJson)
                """.trimIndent(),
                null,
            )
        }
        fun highlightIframeSelection(popupId: String, highlightCount: Int) {
            iframeHostWebView?.evaluateJavascript(
                """
                    window.hoshiReaderPopupHost &&
                    window.hoshiReaderPopupHost.highlightSelection(${org.json.JSONObject.quote(popupId)}, $highlightCount)
                """.trimIndent(),
                null,
            )
        }
        fun lookupChildPopup(selection: ReaderSelectionData): Pair<LookupPopupItem, Int>? =
            createLookupPopupItem(
                selection = selection,
                options = LookupPopupOptions(
                    isVertical = false,
                    isFullWidth = false,
                    width = readerSettings.popupWidth,
                    height = readerSettings.popupHeight,
                    swipeToDismiss = true,
                    swipeThreshold = readerSettings.popupSwipeThreshold,
                    reducedMotionScrolling = readerSettings.popupReducedMotionScrolling,
                    reducedMotionScrollPercent = readerSettings.popupReducedMotionScrollPercent,
                    reducedMotionSwipeThreshold = readerSettings.popupReducedMotionSwipeThreshold,
                    popupScale = readerSettings.popupScale,
                    dictionarySettings = popupSettings?.dictionarySettings ?: DictionarySettings(),
                    topInset = topInset.toDouble(),
                    darkMode = darkMode,
                    eInkMode = readerSettings.eInkMode,
                    audioSettings = popupSettings?.audioSettings ?: AudioSettings(),
                    popupActionBar = false,
                ),
                dictionaryStyles = popupSettings?.dictionaryStyles,
            )
        fun handleReaderPopupBridgeMessage(message: ReaderLookupPopupBridgeMessage) {
            when (message) {
                is ReaderLookupPopupBridgeMessage.OpenLink -> context.openPopupExternalLink(message.url)
                is ReaderLookupPopupBridgeMessage.TapOutside -> {
                    val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                    setIframePopups(closeChildPopupsAndClearSelection(popups, index))
                }
                is ReaderLookupPopupBridgeMessage.SwipeDismiss -> {
                    val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                    if (index == 0) {
                        onClose()
                    } else {
                        setIframePopups(dismissPopupAt(popups, index))
                    }
                }
                is ReaderLookupPopupBridgeMessage.TextSelected -> {
                    val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                    val nextPopups = closeChildPopups(popups, index)
                    val lookup = lookupChildPopup(message.selection)
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
                    val popup = popupById(message.popupId) ?: return
                    val messageId = message.messageId ?: return
                    ankiViewModel.mineEntryAsync(message.payloadJson, popup.state.ankiContext) { mined ->
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
                    val popup = popupById(message.popupId) ?: return
                    val messageId = message.messageId ?: return
                    val settings = popup.state.dictionarySettings.normalized()
                    val results = dependencies.dictionaryRepository.lookup(
                        message.query,
                        settings.maxResults,
                        settings.scanLength,
                    )
                    if (results.isNotEmpty()) {
                        setIframePopups(
                            popups.map { existing ->
                                if (existing.id == message.popupId) {
                                    existing.copy(state = existing.state.copy(results = results))
                                } else {
                                    existing
                                }
                            },
                        )
                        val current = popupHistories[message.popupId] ?: ReaderPopupHistoryCounts()
                        popupHistories = popupHistories + (
                            message.popupId to current.copy(
                                backCount = current.backCount + 1,
                                forwardCount = 0,
                            )
                            )
                    }
                    replyIframeMessage(message.popupId, messageId, results.size.toString())
                }
                is ReaderLookupPopupBridgeMessage.GetEntry -> {
                    val entry = popupById(message.popupId)?.state?.results?.getOrNull(message.index)
                    replyIframeMessage(
                        popupId = message.popupId,
                        messageId = message.messageId ?: return,
                        bodyJson = entry?.let(LookupPopupHtml::entryJsonString) ?: "null",
                    )
                }
                is ReaderLookupPopupBridgeMessage.PopupScrolled -> {
                    val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                    setIframePopups(closeChildPopupsForScrolledParent(popups, index))
                }
                is ReaderLookupPopupBridgeMessage.NavigateBack -> {
                    val current = popupHistories[message.popupId] ?: return
                    if (current.backCount > 0) {
                        popupHistories = popupHistories + (
                            message.popupId to current.copy(
                                backCount = current.backCount - 1,
                                forwardCount = current.forwardCount + 1,
                            )
                            )
                    }
                }
                is ReaderLookupPopupBridgeMessage.NavigateForward -> {
                    val current = popupHistories[message.popupId] ?: return
                    if (current.forwardCount > 0) {
                        popupHistories = popupHistories + (
                            message.popupId to current.copy(
                                backCount = current.backCount + 1,
                                forwardCount = current.forwardCount - 1,
                            )
                            )
                    }
                }
                is ReaderLookupPopupBridgeMessage.ContentReady,
                is ReaderLookupPopupBridgeMessage.ScrollState,
                is ReaderLookupPopupBridgeMessage.SasayakiReplayCue,
                is ReaderLookupPopupBridgeMessage.SasayakiTogglePlayback,
                is ReaderLookupPopupBridgeMessage.SasayakiPlayForward,
                -> Unit
            }
        }
        readerPopupBridgeHolder.callbacks = ReaderLookupPopupBridgeCallbacks(::handleReaderPopupBridgeMessage)
        if (error == null) {
            ProcessTextLookupIframeHost(
                callbackHolder = readerPopupBridgeHolder,
                resourceHandler = readerPopupResourceHandler,
                onWebViewChanged = { iframeHostWebView = it },
                modifier = Modifier.fillMaxSize(),
            )
            ReaderLookupPopupIframeSync(
                webView = iframeHostWebView,
                payloads = iframePayloads,
                rootHighlight = null,
            )
        }
    }
}

internal fun processTextLookupDisplayedPopups(
    popups: List<LookupPopupItem>,
    viewport: ReaderLookupPopupViewport,
    topInset: Double,
): List<LookupPopupItem> =
    popups.mapIndexed { index, popup ->
        if (index != 0) {
            popup
        } else {
            val centeredSelection = popup.state.selection.copy(
                rect = ProcessTextLookupOverlayLayout.rootSelectionRect(
                    screenWidth = viewport.width,
                    screenHeight = viewport.height,
                    popupMaxWidth = popup.state.width.toDouble(),
                    popupMaxHeight = popup.state.height.toDouble(),
                    topInset = topInset,
                    bottomInset = popup.state.bottomInset,
                ),
            )
            popup.copy(
                state = popup.state.copy(
                    selection = centeredSelection,
                    topInset = topInset,
                ),
            )
        }
    }

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ProcessTextLookupIframeHost(
    callbackHolder: ReaderLookupPopupBridgeCallbackHolder,
    resourceHandler: ReaderLookupPopupResourceHandler,
    onWebViewChanged: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnWebViewChanged = rememberUpdatedState(onWebViewChanged)
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
                loadDataWithBaseURL(
                    "https://appassets.androidplatform.net/process-text/iframe-host.html",
                    lookupPopupIframeHostHtml(dismissTopPopupOnOutsideTap = true),
                    "text/html",
                    "UTF-8",
                    null,
                )
                currentOnWebViewChanged.value(this)
            }
        },
        update = { webView ->
            webView.webViewClient = LookupPopupIframeWebViewClient(resourceHandler)
            currentOnWebViewChanged.value(webView)
        },
    )
}

private fun lookupPopupItem(
    selection: ReaderSelectionData,
    results: List<LookupResult>,
    dictionaryStyles: Map<String, String>,
    dictionarySettings: DictionarySettings,
    audioSettings: AudioSettings,
    readerSettings: ReaderSettings,
    darkMode: Boolean,
): LookupPopupItem? {
    if (results.isEmpty()) return null
    return LookupPopupItem(
        state = LookupPopupState(
            selection = selection,
            results = results,
            dictionaryStyles = dictionaryStyles,
            dictionarySettings = dictionarySettings,
            isVertical = false,
            isFullWidth = false,
            width = readerSettings.popupWidth,
            height = readerSettings.popupHeight,
            swipeToDismiss = true,
            swipeThreshold = readerSettings.popupSwipeThreshold,
            reducedMotionScrolling = readerSettings.popupReducedMotionScrolling,
            reducedMotionScrollPercent = readerSettings.popupReducedMotionScrollPercent,
            reducedMotionSwipeThreshold = readerSettings.popupReducedMotionSwipeThreshold,
            popupScale = readerSettings.popupScale,
            topInset = 0.0,
            darkMode = darkMode,
            eInkMode = readerSettings.eInkMode,
            audioSettings = audioSettings,
            popupActionBar = false,
        ),
    )
}

internal object ProcessTextLookupOverlayLayout {
    fun rootSelectionRect(
        screenWidth: Double,
        screenHeight: Double,
        popupMaxWidth: Double,
        popupMaxHeight: Double,
        topInset: Double,
        bottomInset: Double,
    ): ReaderSelectionRect {
        val popupWidth = min(screenWidth - ScreenBorderPadding * 2.0, popupMaxWidth)
        val popupHeight = min(screenHeight - ScreenBorderPadding * 2.0, popupMaxHeight)
        val availableHeight = screenHeight - topInset - bottomInset
        val safeCenterY = topInset + availableHeight / 2.0
        return ReaderSelectionRect(
            x = screenWidth / 2.0 - popupWidth / 2.0,
            y = safeCenterY - popupHeight / 2.0 - PopupPadding - SyntheticSelectionSize,
            width = SyntheticSelectionSize,
            height = SyntheticSelectionSize,
        )
    }

    private const val PopupPadding = 4.0
    private const val ScreenBorderPadding = 6.0
    private const val SyntheticSelectionSize = 1.0
}

private fun androidx.compose.ui.graphics.Color.luminanceForPopup(): Float =
    red * 0.2126f + green * 0.7152f + blue * 0.0722f
