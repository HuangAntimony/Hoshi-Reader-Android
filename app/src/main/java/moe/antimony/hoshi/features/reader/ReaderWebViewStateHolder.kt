package moe.antimony.hoshi.features.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import moe.antimony.hoshi.features.dictionary.LookupPopupItem

internal class ReaderWebViewStateHolder(
    initialSettings: ReaderSettings,
    initialPosition: ReaderChapterPosition,
) {
    var effectiveSettings by mutableStateOf(initialSettings)
        private set

    var showAppearance by mutableStateOf(false)
        private set

    var showChapters by mutableStateOf(false)
        private set

    var showSasayaki by mutableStateOf(false)
        private set

    var showStatistics by mutableStateOf(false)
        private set

    var showReaderMenu by mutableStateOf(false)
        private set

    var focusMode by mutableStateOf(false)
        private set

    var lookupPopups by mutableStateOf<List<LookupPopupItem>>(emptyList())
        private set

    var readerPosition by mutableStateOf(ReaderPositionState(initialPosition))
        private set

    var webViewViewportSize by mutableStateOf(IntSize.Zero)
        private set

    var isWebViewRestoring by mutableStateOf(true)
        private set

    var webViewRestoreEpoch by mutableStateOf(0)
        private set

    var sasayakiWasPausedByLookup by mutableStateOf(false)
        private set

    fun syncSettings(settings: ReaderSettings) {
        val shouldReloadContent = effectiveSettings.readerContentReloadKey() != settings.readerContentReloadKey()
        effectiveSettings = settings
        if (shouldReloadContent) {
            prepareReloadAtDisplayedPosition()
        }
    }

    fun applySettings(settings: ReaderSettings) {
        syncSettings(settings)
    }

    fun showReaderMenu() {
        showReaderMenu = true
    }

    fun toggleReaderMenu() {
        showReaderMenu = !showReaderMenu
    }

    fun dismissReaderMenu() {
        showReaderMenu = false
    }

    fun toggleFocusMode() {
        focusMode = !focusMode
        if (focusMode) {
            showReaderMenu = false
        }
    }

    fun openChaptersFromMenu() {
        showReaderMenu = false
        showChapters = true
    }

    fun dismissChapters() {
        showChapters = false
    }

    fun openAppearanceFromMenu() {
        showReaderMenu = false
        showAppearance = true
    }

    fun dismissAppearance() {
        showAppearance = false
    }

    fun openSasayakiFromMenu() {
        showReaderMenu = false
        showSasayaki = true
    }

    fun dismissSasayaki() {
        showSasayaki = false
    }

    fun openStatisticsFromMenu() {
        showReaderMenu = false
        showStatistics = true
    }

    fun dismissStatistics() {
        showStatistics = false
    }

    fun setLookupPopups(
        popups: List<LookupPopupItem>,
        resumeSasayakiAfterLookup: () -> Unit = {},
    ) {
        lookupPopups = popups
        if (popups.isEmpty() && sasayakiWasPausedByLookup) {
            sasayakiWasPausedByLookup = false
            resumeSasayakiAfterLookup()
        }
    }

    fun markSasayakiPausedByLookup() {
        sasayakiWasPausedByLookup = true
    }

    fun clearSasayakiPauseState() {
        sasayakiWasPausedByLookup = false
    }

    fun shouldPauseSasayakiForLookup(
        enabled: Boolean,
        autoPause: Boolean,
        isPlaying: Boolean,
    ): Boolean {
        if (enabled && autoPause && isPlaying) {
            markSasayakiPausedByLookup()
            return true
        }
        if (!autoPause) {
            clearSasayakiPauseState()
        }
        return false
    }

    fun recordDisplayedProgress(progress: Double): ReaderChapterPosition {
        readerPosition = readerPosition.recordPageProgress(progress)
        return readerPosition.displayedPosition
    }

    fun recordContinuousScrollProgress(progress: Double, restoreEpoch: Int): ReaderChapterPosition? {
        if (isWebViewRestoring || restoreEpoch != webViewRestoreEpoch) return null
        return recordDisplayedProgress(progress)
    }

    fun prepareReloadAtDisplayedPosition() {
        readerPosition = readerPosition.prepareReloadAtDisplayedPosition()
        markWebViewRestoring()
    }

    fun jumpTo(position: ReaderChapterPosition, fragment: String? = null): ReaderChapterPosition {
        readerPosition = readerPosition.jumpTo(position, fragment)
        markWebViewRestoring()
        return readerPosition.displayedPosition
    }

    fun markWebViewRestoring() {
        webViewRestoreEpoch += 1
        isWebViewRestoring = true
    }

    fun markWebViewRestored() {
        isWebViewRestoring = false
    }

    fun goToNextChapter(lastIndex: Int): ReaderChapterPosition? {
        val next = readerPosition.loadPosition.nextOrNull(lastIndex) ?: return null
        return jumpTo(next)
    }

    fun goToPreviousChapter(): ReaderChapterPosition? {
        val previous = readerPosition.loadPosition.previousOrNull() ?: return null
        return jumpTo(previous)
    }

    fun updateViewportSize(size: IntSize) {
        if (size == webViewViewportSize) return
        if (webViewViewportSize != IntSize.Zero) {
            prepareReloadAtDisplayedPosition()
        }
        webViewViewportSize = size
    }
}

internal data class ReaderContentReloadKey(
    val theme: ReaderTheme,
    val eInkMode: Boolean,
    val systemLightSepia: Boolean,
    val sepiaInvertInDark: Boolean,
    val verticalWriting: Boolean,
    val selectedFont: String,
    val fontSize: Int,
    val hideFurigana: Boolean,
    val continuousMode: Boolean,
    val horizontalPadding: Int,
    val verticalPadding: Int,
    val avoidPageBreak: Boolean,
    val justifyText: Boolean,
    val layoutAdvanced: Boolean,
    val lineHeight: Double,
    val characterSpacing: Double,
)

internal fun ReaderSettings.readerContentReloadKey(): ReaderContentReloadKey =
    ReaderContentReloadKey(
        theme = theme,
        eInkMode = eInkMode,
        systemLightSepia = systemLightSepia,
        sepiaInvertInDark = sepiaInvertInDark,
        verticalWriting = verticalWriting,
        selectedFont = selectedFont,
        fontSize = fontSize,
        hideFurigana = hideFurigana,
        continuousMode = continuousMode,
        horizontalPadding = horizontalPadding,
        verticalPadding = verticalPadding,
        avoidPageBreak = avoidPageBreak,
        justifyText = justifyText,
        layoutAdvanced = layoutAdvanced,
        lineHeight = lineHeight,
        characterSpacing = characterSpacing,
    )
