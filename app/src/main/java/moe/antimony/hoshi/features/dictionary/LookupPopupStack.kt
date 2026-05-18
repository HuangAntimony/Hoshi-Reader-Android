package moe.antimony.hoshi.features.dictionary

import moe.antimony.hoshi.epub.SasayakiMatch

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import moe.antimony.hoshi.dictionary.LookupEngine
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.anki.AnkiMiningContext
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import java.util.UUID

internal data class LookupPopupOptions(
    val isVertical: Boolean,
    val isFullWidth: Boolean = false,
    val width: Int = 320,
    val height: Int = 250,
    val swipeToDismiss: Boolean = false,
    val swipeThreshold: Int = 40,
    val reducedMotionScrolling: Boolean = false,
    val reducedMotionScrollPercent: Int = 100,
    val reducedMotionSwipeThreshold: Int = 40,
    val popupScale: Double = 1.0,
    val topInset: Double = 0.0,
    val bottomInset: Double = 0.0,
    val dictionarySettings: DictionarySettings = DictionarySettings(),
    val darkMode: Boolean = false,
    val eInkMode: Boolean = false,
    val audioSettings: AudioSettings = AudioSettings(),
    val popupActionBar: Boolean = false,
    val documentTitle: String? = null,
    val coverPath: String? = null,
)

internal data class LookupPopupItem(
    val id: String = UUID.randomUUID().toString(),
    val state: LookupPopupState,
    val clearSelectionSignal: Int = 0,
    val sasayakiCue: SasayakiMatch? = null,
)

internal fun clearPopupSelectionHighlights(popups: List<LookupPopupItem>): List<LookupPopupItem> =
    popups.map { popup -> popup.copy(clearSelectionSignal = popup.clearSelectionSignal + 1) }

internal fun createLookupPopupItem(
    selection: ReaderSelectionData,
    options: LookupPopupOptions,
    dictionaryStyles: Map<String, String>? = null,
    lookup: (String, Int, Int) -> List<de.manhhao.hoshi.LookupResult> = LookupEngine::lookup,
): Pair<LookupPopupItem, Int>? {
    val settings = options.dictionarySettings.normalized()
    val styles = dictionaryStyles ?: currentDictionaryStyles()
    val results = runCatching {
        lookup(selection.text, settings.maxResults, settings.scanLength)
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
            reducedMotionScrolling = options.reducedMotionScrolling,
            reducedMotionScrollPercent = options.reducedMotionScrollPercent,
            reducedMotionSwipeThreshold = options.reducedMotionSwipeThreshold,
            popupScale = options.popupScale,
            topInset = options.topInset,
            bottomInset = options.bottomInset,
            darkMode = options.darkMode,
            eInkMode = options.eInkMode,
            audioSettings = options.audioSettings,
            popupActionBar = options.popupActionBar,
            ankiContext = AnkiMiningContext(
                sentence = selection.sentence,
                documentTitle = options.documentTitle,
                coverPath = options.coverPath,
                sentenceOffset = selection.sentenceOffset,
            ),
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

internal fun List<LookupPopupItem>.withLookupPopupVisualOptions(
    darkMode: Boolean,
    eInkMode: Boolean,
    audioSettings: AudioSettings,
    popupScale: Double = 1.0,
): List<LookupPopupItem> =
    map { popup ->
        popup.copy(
            state = popup.state.copy(
                darkMode = darkMode,
                eInkMode = eInkMode,
                audioSettings = audioSettings,
                popupScale = popupScale,
            ),
        )
    }

internal fun List<LookupPopupItem>.withRootSelectionOffset(
    offsetX: Double,
    offsetY: Double,
): List<LookupPopupItem> {
    if (isEmpty() || offsetX == 0.0 && offsetY == 0.0) return this
    return mapIndexed { index, popup ->
        if (index != 0) {
            popup
        } else {
            val rect = popup.state.selection.rect
            popup.copy(
                state = popup.state.copy(
                    selection = popup.state.selection.copy(
                        rect = rect.copy(
                            x = rect.x + offsetX,
                            y = rect.y + offsetY,
                        ),
                    ),
                ),
            )
        }
    }
}

internal fun closeChildPopupsForScrolledParent(
    popups: List<LookupPopupItem>,
    parentIndex: Int,
): List<LookupPopupItem> =
    if (parentIndex >= popups.lastIndex) {
        popups
    } else {
        closeChildPopups(popups, parentIndex).mapIndexed { index, popup ->
            if (index == parentIndex) {
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
    onRootPopupDismissed: () -> Boolean = { false },
    isPopupVisible: (LookupPopupItem, Int) -> Boolean = { _, _ -> true },
    isPopupActive: (LookupPopupItem, Int) -> Boolean = { _, _ -> true },
    onPopupContentReady: (String) -> Unit = {},
    warmRootShell: Boolean = false,
    sasayakiWasPaused: Boolean = false,
    sasayakiIsPlaying: Boolean = false,
    onSasayakiReplayCue: (SasayakiMatch) -> Unit = {},
    onSasayakiTogglePlayback: () -> Unit = {},
    onSasayakiPauseStateCleared: () -> Unit = {},
    onSasayakiPlayForward: (SasayakiMatch) -> Unit = {},
    onPrepareSasayakiAudio: (SasayakiMatch, String) -> String? = { _, _ -> null },
    rootSelectionOffsetX: Double = 0.0,
    rootSelectionOffsetY: Double = 0.0,
) {
    val displayPopups = popups.withRootSelectionOffset(rootSelectionOffsetX, rootSelectionOffsetY)
    displayPopups.forEachIndexed { index, popup ->
        key(if (warmRootShell && index == 0) "warm-root-popup" else popup.id) {
            val popupActive = isPopupActive(popup, index)
            val popupVisible = isPopupVisible(popup, index)
            LookupPopupView(
                state = popup.state,
                sasayakiCue = popup.sasayakiCue,
                sasayakiWasPaused = sasayakiWasPaused,
                sasayakiIsPlaying = sasayakiIsPlaying,
                clearSelectionSignal = popup.clearSelectionSignal,
                onTapOutside = {
                    if (popupActive) {
                        onPopupsChange(closeChildPopups(popups, index))
                    }
                },
                onSwipeDismiss = {
                    if (popupActive) {
                        val rootDismissHandled = index == 0 && onRootPopupDismissed()
                        if (!rootDismissHandled) {
                            onPopupsChange(dismissPopupAt(popups, index))
                        }
                    }
                },
                onTextSelected = { selection ->
                    if (!popupActive) {
                        null
                    } else {
                        val nextPopups = closeChildPopups(popups, index)
                        lookupChildPopup(selection)?.let { (childPopup, highlightCount) ->
                            onPopupsChange(nextPopups + childPopup)
                            highlightCount
                        }
                    }
                },
                onPopupScrolled = {
                    if (popupActive) {
                        val nextPopups = closeChildPopupsForScrolledParent(popups, index)
                        if (nextPopups != popups) {
                            onPopupsChange(nextPopups)
                        }
                    }
                },
                onSasayakiReplayCue = onSasayakiReplayCue,
                onSasayakiTogglePlayback = onSasayakiTogglePlayback,
                onSasayakiPauseStateCleared = onSasayakiPauseStateCleared,
                onSasayakiPlayForward = onSasayakiPlayForward,
                onPrepareSasayakiAudio = onPrepareSasayakiAudio,
                isContentVisible = popupVisible,
                isPopupActive = popupActive,
                onContentReady = { onPopupContentReady(popup.id) },
                warmShell = warmRootShell && index == 0,
                contentResetKey = if (warmRootShell && index == 0) popup.id else null,
                modifier = modifier
                    .fillMaxSize()
                    .zIndex(2f + index),
            )
        }
    }
}
