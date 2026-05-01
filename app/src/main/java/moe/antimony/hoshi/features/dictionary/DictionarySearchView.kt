package moe.antimony.hoshi.features.dictionary

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.manhhao.hoshi.LookupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.dictionary.LookupEngine
import moe.antimony.hoshi.features.audio.AudioRequestHandler
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.audio.AudioSettingsStore
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.audio.WordAudioPlayer
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.webview.disableNativeOverscrollStretch

private const val DictionarySearchTopSpacerPx = 118
private const val DictionaryPopupTopInset = 118.0
private const val DictionaryPopupBottomInset = 150.0

internal data class DictionarySearchRenderState(
    val lastQuery: String,
    val html: String,
    val results: List<LookupResult>,
    val hasResults: Boolean,
    val dictionaryStyles: Map<String, String>,
)

internal object DictionarySearchContent {
    fun runLookup(
        query: String,
        lookup: (String) -> List<LookupResult>,
        dictionaryStyles: Map<String, String> = emptyMap(),
        dictionarySettings: DictionarySettings = DictionarySettings(),
        darkMode: Boolean = false,
        audioSettings: AudioSettings = AudioSettings(),
    ): DictionarySearchRenderState {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return DictionarySearchRenderState(
                lastQuery = "",
                html = "",
                results = emptyList(),
                hasResults = false,
                dictionaryStyles = emptyMap(),
            )
        }
        val results = lookup(trimmed)
        if (results.isEmpty()) {
            return DictionarySearchRenderState(
                lastQuery = trimmed,
                html = "",
                results = emptyList(),
                hasResults = false,
                dictionaryStyles = emptyMap(),
            )
        }
        return DictionarySearchRenderState(
            lastQuery = trimmed,
            html = LookupPopupHtml.render(
                results = results,
                dictionaryStyles = dictionaryStyles,
                topSpacerPx = DictionarySearchTopSpacerPx,
                settings = dictionarySettings,
                darkMode = darkMode,
                audioSettings = audioSettings,
            ),
            results = results,
            hasResults = true,
            dictionaryStyles = dictionaryStyles,
        )
    }
}

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
    topInset = DictionaryPopupTopInset,
    bottomInset = DictionaryPopupBottomInset,
    dictionarySettings = dictionarySettings,
    darkMode = darkMode,
    audioSettings = audioSettings,
)

@Composable
fun DictionarySearchView(
    readerSettings: ReaderSettings,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val assets = remember(context) { LookupPopupAssets.load(context) }
    val repository = remember { DictionaryRepository(context.filesDir, context.cacheDir) }
    val dictionarySettingsStore = remember { DictionarySettingsStore(context) }
    val audioSettingsStore = remember { AudioSettingsStore(context) }
    var query by remember { mutableStateOf("") }
    var html by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<LookupResult>>(emptyList()) }
    var hasSearched by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var dictionaryStyles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var dictionarySettings by remember { mutableStateOf(dictionarySettingsStore.load()) }
    var audioSettings by remember { mutableStateOf(audioSettingsStore.load()) }
    var popups by remember { mutableStateOf<List<LookupPopupItem>>(emptyList()) }
    var resultClearSelectionSignal by remember { mutableStateOf(0) }
    val popupDarkMode = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val popupOptions = dictionarySearchPopupOptions(
        readerSettings = readerSettings,
        dictionarySettings = dictionarySettings,
        darkMode = popupDarkMode,
        audioSettings = audioSettings,
    )
    val lookupPopup = { selection: moe.antimony.hoshi.features.reader.ReaderSelectionData ->
        createLookupPopupItem(
            selection = selection,
            options = popupOptions,
            dictionaryStyles = dictionaryStyles,
        )
    }

    fun runLookup() {
        scope.launch {
            isSearching = true
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.rebuildLookupQuery()
                    val styles = currentDictionaryStyles()
                    val settings = dictionarySettingsStore.load()
                    val loadedAudioSettings = audioSettingsStore.load()
                    DictionarySearchContent.runLookup(
                        query = query,
                        lookup = { LookupEngine.lookup(it, settings.maxResults, settings.scanLength) },
                        dictionaryStyles = styles,
                        dictionarySettings = settings,
                        darkMode = popupDarkMode,
                        audioSettings = loadedAudioSettings,
                    )
                }
            }.onSuccess { state ->
                html = state.html
                searchResults = state.results
                dictionaryStyles = state.dictionaryStyles
                dictionarySettings = dictionarySettingsStore.load()
                audioSettings = audioSettingsStore.load()
                popups = emptyList()
                resultClearSelectionSignal = 0
                hasSearched = true
            }.onFailure {
                html = ""
                searchResults = emptyList()
                dictionaryStyles = emptyMap()
                popups = emptyList()
                resultClearSelectionSignal = 0
                hasSearched = true
                errorMessage = it.localizedMessage ?: "Lookup failed."
            }
            isSearching = false
        }
    }

    LaunchedEffect(Unit) {
        dictionarySettings = dictionarySettingsStore.load()
        audioSettings = audioSettingsStore.load()
        withContext(Dispatchers.IO) {
            runCatching { repository.rebuildLookupQuery() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            html.isNotBlank() -> DictionaryResultWebView(
                html = html,
                results = searchResults,
                assets = assets,
                audioSettings = audioSettings,
                clearSelectionSignal = resultClearSelectionSignal,
                callbacks = PopupWebViewCallbacks(
                    onTapOutside = { popups = emptyList() },
                    onSwipeDismiss = { popups = emptyList() },
                    onTextSelected = { selection ->
                        popups = emptyList()
                        lookupPopup(selection)?.let { (popup, highlightCount) ->
                            popups = listOf(popup)
                            highlightCount
                        }
                    },
                    onPlayWordAudio = { url, mode ->
                        WordAudioPlayer.get(context).play(url, mode)
                    },
                ),
                modifier = Modifier.fillMaxSize(),
            )
            errorMessage != null -> DictionarySearchMessage(
                text = requireNotNull(errorMessage),
                modifier = Modifier.fillMaxSize(),
            )
            hasSearched && !isSearching -> DictionarySearchMessage(
                text = "",
                modifier = Modifier.fillMaxSize(),
            )
        }
        DictionarySearchBar(
            query = query,
            isSearching = isSearching,
            onQueryChange = { query = it },
            onSubmit = ::runLookup,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
        LookupPopupStackView(
            popups = popups,
            onPopupsChange = { popups = it },
            lookupChildPopup = lookupPopup,
            onRootPopupDismissed = { resultClearSelectionSignal += 1 },
            modifier = Modifier.fillMaxSize(),
        )
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
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
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
    audioSettings: AudioSettings,
    clearSelectionSignal: Int,
    callbacks: PopupWebViewCallbacks,
    modifier: Modifier = Modifier,
) {
    val callbackHolder = remember { PopupWebViewCallbackHolder(callbacks) }
    callbackHolder.callbacks = callbacks
    val lookupResultsHolder = remember { PopupLookupResultsHolder(results) }
    lookupResultsHolder.results = results
    var loadedHtml by remember { mutableStateOf<String?>(null) }
    var appliedClearSelectionSignal by remember { mutableStateOf(clearSelectionSignal) }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            val audioRequestHandler = AudioRequestHandler(
                LocalAudioRepository.fromContext(context),
            )
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                isVerticalScrollBarEnabled = false
                disableNativeOverscrollStretch()
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(
                    PopupWebViewBridge(
                        webView = this,
                        callbackHolder = callbackHolder,
                        lookupResultsHolder = lookupResultsHolder,
                    ),
                    "HoshiPopup",
                )
                webViewClient = PopupMessageWebViewClient(callbackHolder, audioRequestHandler, assets)
            }
        },
        update = { webView ->
            callbackHolder.callbacks = callbacks
            webView.webViewClient = PopupMessageWebViewClient(
                callbackHolder,
                AudioRequestHandler(
                    LocalAudioRepository.fromContext(webView.context),
                ),
                assets,
            )
            if (loadedHtml != html) {
                loadedHtml = html
                webView.loadDataWithBaseURL(
                    "https://hoshi.local/dictionary/",
                    html,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            if (appliedClearSelectionSignal != clearSelectionSignal) {
                appliedClearSelectionSignal = clearSelectionSignal
                webView.evaluateJavascript("window.hoshiSelection.clearSelection()", null)
            }
        },
    )
}
