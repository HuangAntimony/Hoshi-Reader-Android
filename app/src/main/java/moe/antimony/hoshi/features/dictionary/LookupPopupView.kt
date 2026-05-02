package moe.antimony.hoshi.features.dictionary

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Start
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import de.manhhao.hoshi.LookupResult
import moe.antimony.hoshi.features.audio.AudioRequestHandler
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.audio.WordAudioPlayer
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.sasayaki.SasayakiMatch
import moe.antimony.hoshi.webview.disableNativeOverscrollStretch

private const val SasayakiPopupControlsTotalHeightValue = 37.0
private val SasayakiPopupControlsHeight = 36.dp
private val SasayakiPopupControlSize = 32.dp
private val SasayakiPopupControlIconSize = 20.dp

data class LookupPopupState(
    val selection: ReaderSelectionData,
    val results: List<LookupResult>,
    val dictionaryStyles: Map<String, String> = emptyMap(),
    val dictionarySettings: DictionarySettings = DictionarySettings(),
    val isVertical: Boolean = true,
    val isFullWidth: Boolean = false,
    val width: Int = 320,
    val height: Int = 250,
    val swipeToDismiss: Boolean = false,
    val swipeThreshold: Int = 40,
    val topInset: Double = 0.0,
    val bottomInset: Double = 0.0,
    val darkMode: Boolean = false,
    val eInkMode: Boolean = false,
    val audioSettings: AudioSettings = AudioSettings(),
)

@Composable
fun LookupPopupView(
    state: LookupPopupState,
    onSwipeDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sasayakiCue: SasayakiMatch? = null,
    sasayakiWasPaused: Boolean = false,
    sasayakiIsPlaying: Boolean = false,
    clearSelectionSignal: Int = 0,
    onTapOutside: () -> Unit = onSwipeDismiss,
    onTextSelected: (ReaderSelectionData) -> Int? = { null },
    onSasayakiReplayCue: (SasayakiMatch) -> Unit = {},
    onSasayakiTogglePlayback: () -> Unit = {},
    onSasayakiPauseStateCleared: () -> Unit = {},
    onSasayakiPlayForward: (SasayakiMatch) -> Unit = {},
) {
    if (state.results.isEmpty()) return
    val context = LocalContext.current
    val assets = remember(context) { LookupPopupAssets.load(context) }
    val html = remember(
        state.results,
        state.dictionaryStyles,
        state.dictionarySettings,
        state.swipeToDismiss,
        state.swipeThreshold,
        state.darkMode,
        state.eInkMode,
        state.audioSettings,
    ) {
        LookupPopupHtml.render(
            results = state.results,
            dictionaryStyles = state.dictionaryStyles,
            settings = state.dictionarySettings,
            swipeToDismiss = state.swipeToDismiss,
            swipeThreshold = state.swipeThreshold,
            darkMode = state.darkMode,
            eInkMode = state.eInkMode,
            audioSettings = state.audioSettings,
        )
    }
    var contentReady by remember(html) { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val frame = LookupPopupLayout(
            selectionRect = state.selection.rect,
            screenWidth = maxWidth.value.toDouble(),
            screenHeight = maxHeight.value.toDouble(),
            maxWidth = state.width.toDouble(),
            maxHeight = state.height.toDouble(),
            isVertical = state.isVertical,
            isFullWidth = state.isFullWidth,
            topInset = state.topInset,
            bottomInset = state.bottomInset,
        ).calculate()
        val frameX = frame.centerX - frame.width / 2
        val frameY = frame.centerY - frame.height / 2
        val hasSasayakiControls = sasayakiCue != null
        val controlsHeight = if (hasSasayakiControls) SasayakiPopupControlsTotalHeightValue else 0.0
        val popupBackground = if (state.darkMode) Color.Black else Color.White
        val popupBorder = when {
            state.eInkMode && state.darkMode -> Color.White
            state.eInkMode -> Color.Black
            state.darkMode -> Color(0xFF3A3A3C)
            else -> Color(0xFFD1D1D6)
        }
        val controlContentColor = when {
            state.eInkMode && state.darkMode -> Color.White
            state.eInkMode -> Color.Black
            state.darkMode -> Color(0xFFEBEBF5)
            else -> Color(0x993C3C43)
        }
        Surface(
            modifier = Modifier
                .absoluteOffset(
                    x = frameX.dp,
                    y = frameY.dp,
                )
                .width(frame.width.dp)
                .height(frame.height.dp)
                .alpha(if (contentReady) 1f else 0f)
                .zIndex(2f),
            shape = if (state.eInkMode) RectangleShape else RoundedCornerShape(8.dp),
            color = popupBackground,
            border = BorderStroke(1.dp, popupBorder),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (sasayakiCue != null) {
                    SasayakiPopupControls(
                        isPlaying = sasayakiIsPlaying,
                        wasPaused = sasayakiWasPaused,
                        onReplay = {
                            WordAudioPlayer.get(context).stop()
                            onSasayakiReplayCue(sasayakiCue)
                        },
                        onTogglePlayback = {
                            WordAudioPlayer.get(context).stop()
                            if (sasayakiWasPaused) {
                                onSasayakiPauseStateCleared()
                            } else {
                                onSasayakiTogglePlayback()
                            }
                        },
                        onPlayForward = {
                            WordAudioPlayer.get(context).stop()
                            onSasayakiPlayForward(sasayakiCue)
                            onSwipeDismiss()
                        },
                        contentColor = controlContentColor,
                        dividerColor = popupBorder,
                    )
                }
                LookupPopupWebView(
                    html = html,
                    results = state.results,
                    assets = assets,
                    audioSettings = state.audioSettings,
                    darkMode = state.darkMode,
                    selectionOffsetX = frameX,
                    selectionOffsetY = frameY + controlsHeight,
                    clearSelectionSignal = clearSelectionSignal,
                    callbacks = PopupWebViewCallbacks(
                        onTapOutside = onTapOutside,
                        onSwipeDismiss = onSwipeDismiss,
                        onTextSelected = onTextSelected,
                        onPlayWordAudio = { url, mode ->
                            WordAudioPlayer.get(context).play(url, mode)
                        },
                        onContentReady = {
                            contentReady = true
                        },
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SasayakiPopupControls(
    isPlaying: Boolean,
    wasPaused: Boolean,
    onReplay: () -> Unit,
    onTogglePlayback: () -> Unit,
    onPlayForward: () -> Unit,
    contentColor: Color,
    dividerColor: Color,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(SasayakiPopupControlsHeight)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SasayakiPopupControlButton(onClick = onReplay) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Replay Sasayaki Cue",
                    tint = contentColor,
                    modifier = Modifier.size(SasayakiPopupControlIconSize),
                )
            }
            SasayakiPopupControlButton(onClick = onTogglePlayback) {
                Icon(
                    imageVector = if (isPlaying || wasPaused) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying || wasPaused) "Pause Sasayaki" else "Play Sasayaki",
                    tint = contentColor,
                    modifier = Modifier.size(SasayakiPopupControlIconSize),
                )
            }
            SasayakiPopupControlButton(onClick = onPlayForward) {
                Icon(
                    imageVector = Icons.Rounded.Start,
                    contentDescription = "Play From Sasayaki Cue",
                    tint = contentColor,
                    modifier = Modifier.size(SasayakiPopupControlIconSize),
                )
            }
        }
        HorizontalDivider(color = dividerColor)
    }
}

@Composable
private fun SasayakiPopupControlButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(SasayakiPopupControlSize)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LookupPopupWebView(
    html: String,
    results: List<LookupResult>,
    assets: LookupPopupAssets,
    audioSettings: AudioSettings,
    darkMode: Boolean,
    selectionOffsetX: Double,
    selectionOffsetY: Double,
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
        modifier = modifier
            .fillMaxSize()
            .background(if (darkMode) Color.Black else Color.White),
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
                isHorizontalScrollBarEnabled = false
                disableNativeOverscrollStretch()
                setBackgroundColor(if (darkMode) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                addJavascriptInterface(
                    PopupWebViewBridge(
                        webView = this,
                        callbackHolder = callbackHolder,
                        lookupResultsHolder = lookupResultsHolder,
                        selectionOffsetX = selectionOffsetX,
                        selectionOffsetY = selectionOffsetY,
                    ),
                    "HoshiPopup",
                )
                webViewClient = PopupMessageWebViewClient(callbackHolder, audioRequestHandler, assets)
            }
        },
        update = { webView ->
            callbackHolder.callbacks = callbacks
            webView.setBackgroundColor(if (darkMode) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            if (loadedHtml != html) {
                loadedHtml = html
                webView.loadDataWithBaseURL(
                    "https://hoshi.local/popup/",
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
