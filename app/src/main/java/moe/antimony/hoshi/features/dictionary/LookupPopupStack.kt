package moe.antimony.hoshi.features.dictionary

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import moe.antimony.hoshi.dictionary.LookupEngine
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.sasayaki.SasayakiMatch
import java.util.UUID

internal data class LookupPopupOptions(
    val isVertical: Boolean,
    val isFullWidth: Boolean = false,
    val width: Int = 320,
    val height: Int = 250,
    val swipeToDismiss: Boolean = false,
    val swipeThreshold: Int = 40,
    val topInset: Double = 0.0,
    val bottomInset: Double = 0.0,
    val dictionarySettings: DictionarySettings = DictionarySettings(),
    val darkMode: Boolean = false,
    val eInkMode: Boolean = false,
    val audioSettings: AudioSettings = AudioSettings(),
)

internal data class LookupPopupItem(
    val id: String = UUID.randomUUID().toString(),
    val state: LookupPopupState,
    val clearSelectionSignal: Int = 0,
    val sasayakiCue: SasayakiMatch? = null,
)

internal fun createLookupPopupItem(
    selection: ReaderSelectionData,
    options: LookupPopupOptions,
    dictionaryStyles: Map<String, String>? = null,
): Pair<LookupPopupItem, Int>? {
    val settings = options.dictionarySettings.normalized()
    val styles = dictionaryStyles ?: currentDictionaryStyles()
    val results = runCatching {
        LookupEngine.lookup(selection.text, settings.maxResults, settings.scanLength)
    }.getOrDefault(emptyList())
    val first = results.firstOrNull() ?: return null
    return LookupPopupItem(
        state = LookupPopupState(
            selection = selection,
            results = results,
            dictionaryStyles = styles,
            dictionarySettings = settings,
            isVertical = options.isVertical,
            isFullWidth = options.isFullWidth,
            width = options.width,
            height = options.height,
            swipeToDismiss = options.swipeToDismiss,
            swipeThreshold = options.swipeThreshold,
            topInset = options.topInset,
            bottomInset = options.bottomInset,
            darkMode = options.darkMode,
            eInkMode = options.eInkMode,
            audioSettings = options.audioSettings,
        ),
    ) to first.matched.codePointCount(0, first.matched.length)
}

internal fun currentDictionaryStyles(): Map<String, String> =
    runCatching {
        LookupEngine.getStyles().associate { it.dictName to it.styles }
    }.getOrDefault(emptyMap())

internal fun closeChildPopups(
    popups: List<LookupPopupItem>,
    parentIndex: Int,
): List<LookupPopupItem> = popups.take(parentIndex + 1)

internal fun dismissPopupAt(
    popups: List<LookupPopupItem>,
    index: Int,
): List<LookupPopupItem> =
    if (index == 0) {
        emptyList()
    } else {
        closeChildPopups(popups, index - 1).mapIndexed { popupIndex, popup ->
            if (popupIndex == index - 1) {
                popup.copy(clearSelectionSignal = popup.clearSelectionSignal + 1)
            } else {
                popup
            }
        }
    }

@Composable
internal fun LookupPopupStackView(
    popups: List<LookupPopupItem>,
    onPopupsChange: (List<LookupPopupItem>) -> Unit,
    lookupChildPopup: (ReaderSelectionData) -> Pair<LookupPopupItem, Int>?,
    modifier: Modifier = Modifier,
    onRootPopupDismissed: () -> Unit = {},
    sasayakiWasPaused: Boolean = false,
    sasayakiIsPlaying: Boolean = false,
    onSasayakiReplayCue: (SasayakiMatch) -> Unit = {},
    onSasayakiTogglePlayback: () -> Unit = {},
    onSasayakiPauseStateCleared: () -> Unit = {},
    onSasayakiPlayForward: (SasayakiMatch) -> Unit = {},
) {
    popups.forEachIndexed { index, popup ->
        key(popup.id) {
            LookupPopupView(
                state = popup.state,
                sasayakiCue = popup.sasayakiCue,
                sasayakiWasPaused = sasayakiWasPaused,
                sasayakiIsPlaying = sasayakiIsPlaying,
                clearSelectionSignal = popup.clearSelectionSignal,
                onTapOutside = {
                    onPopupsChange(closeChildPopups(popups, index))
                },
                onSwipeDismiss = {
                    if (index == 0) onRootPopupDismissed()
                    onPopupsChange(dismissPopupAt(popups, index))
                },
                onTextSelected = { selection ->
                    val nextPopups = closeChildPopups(popups, index)
                    lookupChildPopup(selection)?.let { (childPopup, highlightCount) ->
                        onPopupsChange(nextPopups + childPopup)
                        highlightCount
                    }
                },
                onSasayakiReplayCue = onSasayakiReplayCue,
                onSasayakiTogglePlayback = onSasayakiTogglePlayback,
                onSasayakiPauseStateCleared = onSasayakiPauseStateCleared,
                onSasayakiPlayForward = onSasayakiPlayForward,
                modifier = modifier
                    .fillMaxSize()
                    .zIndex(2f + index),
            )
        }
    }
}
