package moe.antimony.hoshi.features.dictionary

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.WindowCompat
import de.manhhao.hoshi.LookupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.HoshiAppContainer
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.ProcessTextLookupRequest
import moe.antimony.hoshi.dictionary.LookupEngine
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.usesDarkInterface
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import kotlin.math.min

class ProcessTextLookupActivity : ComponentActivity() {
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
            val appContainer = remember { HoshiAppContainer(applicationContext) }
            var readerSettings by remember { mutableStateOf<ReaderSettings?>(null) }
            LaunchedEffect(appContainer) {
                appContainer.readerSettingsRepository.settings.collect { settings ->
                    readerSettings = settings
                }
            }
            val loadedReaderSettings = readerSettings ?: return@setContent
            CompositionLocalProvider(LocalHoshiAppContainer provides appContainer) {
                HoshiReaderTheme(
                    darkTheme = loadedReaderSettings.usesDarkInterface(
                        androidx.compose.foundation.isSystemInDarkTheme(),
                    ),
                    eInkMode = loadedReaderSettings.eInkMode,
                ) {
                    ProcessTextLookupOverlay(
                        query = request.query,
                        readerSettings = loadedReaderSettings,
                        appContainer = appContainer,
                        onClose = ::finish,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessTextLookupOverlay(
    query: String,
    readerSettings: ReaderSettings,
    appContainer: HoshiAppContainer,
    onClose: () -> Unit,
) {
    var popups by remember(query) { mutableStateOf<List<LookupPopupItem>>(emptyList()) }
    var error by remember(query) { mutableStateOf<Throwable?>(null) }
    val darkMode = MaterialTheme.colorScheme.background.luminanceForPopup() < 0.5f
    val density = LocalDensity.current
    val topInset = with(density) {
        WindowInsets.statusBars.getTop(this).toDp().value
    }

    LaunchedEffect(query, readerSettings) {
        runCatching {
            withContext(Dispatchers.IO) {
                appContainer.dictionaryRepository.rebuildLookupQuery()
                val dictionarySettings = appContainer.dictionarySettingsRepository.settings.first().normalized()
                val audioSettings = appContainer.audioSettingsRepository.settings.first()
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
            .background(ComposeColor.Transparent)
            .clickable(
                indication = null,
                interactionSource = null,
                onClick = onClose,
            ),
    ) {
        val displayedPopups = popups.mapIndexed { index, popup ->
            if (index == 0) {
                val centeredSelection = popup.state.selection.copy(
                    rect = ProcessTextLookupOverlayLayout.rootSelectionRect(
                        screenWidth = maxWidth.value.toDouble(),
                        screenHeight = maxHeight.value.toDouble(),
                        popupMaxWidth = popup.state.width.toDouble(),
                        popupMaxHeight = popup.state.height.toDouble(),
                        topInset = topInset.toDouble(),
                        bottomInset = popup.state.bottomInset,
                    ),
                )
                popup.copy(
                    state = popup.state.copy(
                        selection = centeredSelection,
                        topInset = topInset.toDouble(),
                    ),
                )
            } else {
                popup
            }
        }
        if (error == null) {
            LookupPopupAndroidStack(
                popups = displayedPopups,
                onPopupsChange = { next ->
                    if (next.isEmpty()) onClose() else popups = next
                },
                lookupChildPopup = { selection ->
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
                            dictionarySettings = displayedPopups.firstOrNull()?.state?.dictionarySettings
                                ?: DictionarySettings(),
                            topInset = topInset.toDouble(),
                            darkMode = darkMode,
                            eInkMode = readerSettings.eInkMode,
                            audioSettings = displayedPopups.firstOrNull()?.state?.audioSettings
                                ?: AudioSettings(),
                            popupActionBar = false,
                        ),
                    )
                },
                onRootPopupDismissed = {
                    onClose()
                    true
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
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
