package moe.antimony.hoshi.features.dictionary

import android.annotation.SuppressLint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import de.manhhao.hoshi.LookupResult
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.features.audio.AudioRequestHandler
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.audio.WordAudioPlayer
import moe.antimony.hoshi.features.anki.AnkiMiningContext
import moe.antimony.hoshi.features.anki.AnkiViewModel
import moe.antimony.hoshi.features.reader.ReaderFontManager
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults
import kotlin.math.abs

private const val DictionaryPopupTopInset = 118.0
private const val DictionaryPopupBottomInset = 0.0

internal fun dictionarySearchCursorColor(foregroundColor: Color): Color = foregroundColor

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
    topInset = DictionaryPopupTopInset,
    bottomInset = DictionaryPopupBottomInset,
    dictionarySettings = dictionarySettings,
    darkMode = darkMode,
    eInkMode = readerSettings.eInkMode,
    audioSettings = audioSettings,
)

@Composable
fun DictionarySearchView(
    readerSettings: ReaderSettings,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiAppContainer.current
    val assets = remember(context) { LookupPopupAssets.load(context) }
    val searchViewModel: DictionarySearchViewModel = viewModel(
        factory = remember(appContainer) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DictionarySearchViewModel(appContainer.dictionarySearchRepository()) as T
            }
        },
    )
    val ankiViewModel: AnkiViewModel = viewModel(
        factory = remember(appContainer) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AnkiViewModel(appContainer.ankiRepository) as T
            }
        },
    )
    val uiState by searchViewModel.uiState.collectAsState()
    val ankiUiState by ankiViewModel.uiState.collectAsState()
    var rootHighlightRects by remember { mutableStateOf<List<ReaderSelectionRect>>(emptyList()) }
    val localAudioRepository = appContainer.localAudioRepository
    val fontManager = appContainer.readerFontManager
    val fontFaceCss = fontManager.popupFontFaceCss()
    val popupDarkMode = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val actionButtonTintColor = when {
        readerSettings.eInkMode && popupDarkMode -> Color.White
        readerSettings.eInkMode -> Color.Black
        popupDarkMode -> Color(0xFFEBEBF5)
        else -> Color(0x993C3C43)
    }
    val popupOptions = dictionarySearchPopupOptions(
        readerSettings = readerSettings,
        dictionarySettings = uiState.dictionarySettings,
        darkMode = popupDarkMode,
        audioSettings = uiState.audioSettings,
    )
    val resultHtml = remember(
        uiState.lastQuery,
        uiState.results,
        uiState.dictionaryStyles,
        uiState.dictionarySettings,
        popupDarkMode,
        readerSettings.eInkMode,
        uiState.audioSettings,
        ankiUiState.popupSettings,
        assets,
        fontFaceCss,
    ) {
        DictionarySearchContent.renderExistingResults(
            lastQuery = uiState.lastQuery,
            results = uiState.results,
            assets = assets,
            dictionaryStyles = uiState.dictionaryStyles,
            dictionarySettings = uiState.dictionarySettings,
            darkMode = popupDarkMode,
            eInkMode = readerSettings.eInkMode,
            audioSettings = uiState.audioSettings,
            ankiSettings = ankiUiState.popupSettings,
            fontFaceCss = fontFaceCss,
            popupScale = readerSettings.popupScale,
        ).html
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
    val runLookup = {
        rootHighlightRects = emptyList()
        searchViewModel.runLookup(
            assets = assets,
            darkMode = popupDarkMode,
            eInkMode = readerSettings.eInkMode,
            ankiSettings = ankiUiState.popupSettings,
            fontFaceCss = fontFaceCss,
            popupScale = readerSettings.popupScale,
        )
    }
    val lookupPopup = { selection: moe.antimony.hoshi.features.reader.ReaderSelectionData ->
        searchViewModel.createPopup(
            selection = selection,
            options = popupOptions,
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            uiState.html.isNotBlank() -> DictionaryResultWebView(
                html = resultHtml,
                results = uiState.results,
                assets = assets,
                fontManager = fontManager,
                audioSettings = uiState.audioSettings,
                popupScale = readerSettings.popupScale,
                actionButtonTintColor = actionButtonTintColor,
                localAudioRepository = localAudioRepository,
                clearSelectionSignal = uiState.resultClearSelectionSignal,
                backSignal = uiState.backSignal,
                forwardSignal = uiState.forwardSignal,
                callbacks = PopupWebViewCallbacks(
                    onTapOutside = {
                        rootHighlightRects = emptyList()
                        searchViewModel.closePopups()
                    },
                    onSwipeDismiss = {
                        rootHighlightRects = emptyList()
                        searchViewModel.closePopups()
                    },
                    onOpenLink = context::openPopupExternalLink,
                    onTextSelected = { selection ->
                        searchViewModel.openRootPopup(selection, popupOptions)
                    },
                    onSelectionRectsLoaded = { rects ->
                        rootHighlightRects = rects
                    },
                    onLookupRedirect = searchViewModel::lookupRedirect,
                    onLookupRedirected = searchViewModel::recordLookupRedirected,
                    onPlayWordAudio = { url, mode ->
                        WordAudioPlayer.get(context).play(url, mode)
                    },
                    onMineEntry = { payload ->
                        ankiViewModel.mineEntry(
                            payload,
                            AnkiMiningContext(
                                sentence = uiState.lastQuery.ifBlank { uiState.query },
                            ),
                        )
                    },
                    onDuplicateCheck = ankiViewModel::duplicateCheckAsync,
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .observeDictionaryHistorySwipe(
                        backCount = uiState.backCount,
                        forwardCount = uiState.forwardCount,
                        onBack = searchViewModel::navigateBack,
                        onForward = searchViewModel::navigateForward,
                    ),
            )
            uiState.errorMessage != null -> DictionarySearchMessage(
                text = requireNotNull(uiState.errorMessage),
                modifier = Modifier.fillMaxSize(),
            )
            uiState.hasSearched && !uiState.isSearching -> DictionarySearchMessage(
                text = "",
                modifier = Modifier.fillMaxSize(),
            )
        }
        DictionarySearchBar(
            query = uiState.query,
            isSearching = uiState.isSearching,
            onQueryChange = searchViewModel::updateQuery,
            onSubmit = runLookup,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
        LookupPopupAndroidStack(
            popups = themedPopups,
            onPopupsChange = { next ->
                if (next.isEmpty()) rootHighlightRects = emptyList()
                searchViewModel.setPopups(next)
            },
            lookupChildPopup = lookupPopup,
            onRootPopupDismissed = {
                rootHighlightRects = emptyList()
                searchViewModel.dismissRootPopup()
                true
            },
            rootHighlightRects = rootHighlightRects,
            rootHighlightDarkMode = popupDarkMode,
            rootHighlightEInkMode = readerSettings.eInkMode,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun Modifier.observeDictionaryHistorySwipe(
    backCount: Int,
    forwardCount: Int,
    onBack: () -> Unit,
    onForward: () -> Unit,
): Modifier = pointerInput(backCount, forwardCount) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
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
private fun DictionarySearchBar(
    query: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 16.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchGlyph(modifier = Modifier.size(20.dp))
            Box(modifier = Modifier.weight(1f)) {
                val fieldForegroundColor = MaterialTheme.colorScheme.onSurface
                if (query.isEmpty()) {
                    Text(
                        text = "Search",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
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
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = fieldForegroundColor),
                    cursorBrush = SolidColor(dictionarySearchCursorColor(fieldForegroundColor)),
                    keyboardOptions = dictionarySearchKeyboardOptions(),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
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
        contentDescription = "Clear",
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DictionaryResultWebView(
    html: String,
    results: List<LookupResult>,
    assets: LookupPopupAssets,
    fontManager: ReaderFontManager,
    audioSettings: AudioSettings,
    popupScale: Double,
    actionButtonTintColor: Color,
    localAudioRepository: LocalAudioRepository,
    clearSelectionSignal: Int,
    backSignal: Int,
    forwardSignal: Int,
    callbacks: PopupWebViewCallbacks,
    modifier: Modifier = Modifier,
) {
    val callbackHolder = remember { PopupWebViewCallbackHolder(callbacks) }
    callbackHolder.callbacks = callbacks
    val lookupResultsHolder = remember { PopupLookupResultsHolder(results) }
    var loadedHtml by remember { mutableStateOf<String?>(null) }
    var appliedClearSelectionSignal by remember { mutableStateOf(clearSelectionSignal) }
    var appliedBackSignal by remember { mutableStateOf(backSignal) }
    var appliedForwardSignal by remember { mutableStateOf(forwardSignal) }
    var appliedPopupScale by remember { mutableStateOf(popupScale) }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            val audioRequestHandler = AudioRequestHandler(
                localAudioRepository,
            )
            PopupActionButtonWebView(context).apply {
                applyHoshiWebViewSecurityDefaults()
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(
                    PopupWebViewBridge(
                        webView = this,
                        callbackHolder = callbackHolder,
                        lookupResultsHolder = lookupResultsHolder,
                    ),
                    "HoshiPopup",
                )
                webViewClient = PopupMessageWebViewClient(
                    callbackHolder = callbackHolder,
                    audioRequestHandler = audioRequestHandler,
                    assets = assets,
                    fontManager = fontManager,
                )
            }
        },
        update = { webView ->
            callbackHolder.callbacks = callbacks
            webView.webViewClient = PopupMessageWebViewClient(
                callbackHolder,
                AudioRequestHandler(
                    localAudioRepository,
                ),
                assets,
                fontManager,
            )
            if (loadedHtml != html) {
                lookupResultsHolder.results = results
                loadedHtml = html
                (webView as? PopupActionButtonWebView)?.clearActionButtons()
                webView.loadDataWithBaseURL(
                    "https://hoshi.local/dictionary/",
                    html,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            (webView as? PopupActionButtonWebView)?.setActionButtonTint(actionButtonTintColor.toArgb())
            if (appliedPopupScale != popupScale) {
                appliedPopupScale = popupScale
                webView.evaluateJavascript(
                    "document.documentElement.style.zoom = '${popupScale.coerceIn(0.8, 1.5)}'; if (typeof syncButtonFrames === 'function') requestAnimationFrame(syncButtonFrames)",
                    null,
                )
            }
            if (appliedClearSelectionSignal != clearSelectionSignal) {
                appliedClearSelectionSignal = clearSelectionSignal
                webView.evaluateJavascript("window.hoshiSelection.clearSelection()", null)
            }
            if (appliedBackSignal != backSignal) {
                appliedBackSignal = backSignal
                webView.evaluateJavascript("window.navigateBack(); if (typeof syncButtonFrames === 'function') requestAnimationFrame(syncButtonFrames)", null)
            }
            if (appliedForwardSignal != forwardSignal) {
                appliedForwardSignal = forwardSignal
                webView.evaluateJavascript("window.navigateForward(); if (typeof syncButtonFrames === 'function') requestAnimationFrame(syncButtonFrames)", null)
            }
        },
    )
}
