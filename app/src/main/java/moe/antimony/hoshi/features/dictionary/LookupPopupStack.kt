package moe.antimony.hoshi.features.dictionary

import de.manhhao.hoshi.LookupResult
import moe.antimony.hoshi.epub.SasayakiMatch
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

internal data class LookupPopupState(
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
    val reducedMotionScrolling: Boolean = false,
    val reducedMotionScrollPercent: Int = 100,
    val reducedMotionSwipeThreshold: Int = 40,
    val popupScale: Double = 1.0,
    val topInset: Double = 0.0,
    val bottomInset: Double = 0.0,
    val darkMode: Boolean = false,
    val eInkMode: Boolean = false,
    val audioSettings: AudioSettings = AudioSettings(),
    val popupActionBar: Boolean = false,
    val ankiContext: AnkiMiningContext = AnkiMiningContext(sentence = selection.sentence),
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
