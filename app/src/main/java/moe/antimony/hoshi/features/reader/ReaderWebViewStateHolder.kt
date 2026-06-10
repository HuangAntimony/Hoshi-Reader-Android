package moe.antimony.hoshi.features.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.diagnostics.PerformanceLog

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

    var showHighlights by mutableStateOf(false)
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

    private var backHistory by mutableStateOf<List<ReaderChapterPosition>>(emptyList())
    private var forwardHistory by mutableStateOf<List<ReaderChapterPosition>>(emptyList())

    val backTargetPosition: ReaderChapterPosition? get() = backHistory.lastOrNull()
    val forwardTargetPosition: ReaderChapterPosition? get() = forwardHistory.lastOrNull()

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

    fun enterFocusModeForReaderInteraction() {
        focusMode = true
        showReaderMenu = false
    }

    fun exitFocusMode() {
        focusMode = false
    }

    fun handleBackNavigation(): Boolean {
        if (focusMode) {
            exitFocusMode()
            return false
        }
        return true
    }

    fun toggleFocusModeFromReaderTap(hasVisiblePopups: Boolean): Boolean {
        if (hasVisiblePopups) return false
        toggleFocusMode()
        return true
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

    fun openHighlightsFromMenu() {
        showReaderMenu = false
        showHighlights = true
    }

    fun dismissHighlights() {
        showHighlights = false
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

    fun recordContinuousScrollDisplayProgress(progress: Double, restoreEpoch: Int): ReaderChapterPosition? =
        recordContinuousScrollProgress(progress, restoreEpoch)

    fun canAcceptReaderNavigationInput(): Boolean =
        !isWebViewRestoring

    fun beginReaderNavigationInput(): Boolean {
        if (!canAcceptReaderNavigationInput()) return false
        enterFocusModeForReaderInteraction()
        return true
    }

    fun prepareReloadAtDisplayedPosition() {
        PerformanceLog.d(
            PerformanceLog.ReaderTag,
            "reader reload prepared at displayed position chapter=${readerPosition.displayedPosition.index} progress=${readerPosition.displayedPosition.progress}",
        )
        readerPosition = readerPosition.prepareReloadAtDisplayedPosition()
        markWebViewRestoring()
    }

    fun jumpTo(position: ReaderChapterPosition, fragment: String? = null): ReaderChapterPosition {
        PerformanceLog.d(
            PerformanceLog.ReaderTag,
            "reader chapter load requested from=${readerPosition.loadPosition.index} to=${position.index} progress=${position.progress} fragment=${fragment != null}",
        )
        readerPosition = readerPosition.jumpTo(position, fragment)
        markWebViewRestoring()
        return readerPosition.displayedPosition
    }

    fun jumpToWithHistory(position: ReaderChapterPosition, fragment: String? = null): ReaderChapterPosition {
        recordJumpOrigin()
        return jumpTo(position, fragment)
    }

    fun navigateBackInJumpHistory(): ReaderChapterPosition? {
        val target = backHistory.lastOrNull() ?: return null
        backHistory = backHistory.dropLast(1)
        forwardHistory = forwardHistory + readerPosition.displayedPosition
        return jumpTo(target)
    }

    fun navigateForwardInJumpHistory(): ReaderChapterPosition? {
        val target = forwardHistory.lastOrNull() ?: return null
        forwardHistory = forwardHistory.dropLast(1)
        backHistory = backHistory + readerPosition.displayedPosition
        return jumpTo(target)
    }

    fun clearForwardHistoryAfterManualMovement() {
        if (backHistory.isEmpty()) {
            forwardHistory = emptyList()
        }
    }

    fun markWebViewRestoring() {
        webViewRestoreEpoch += 1
        isWebViewRestoring = true
        PerformanceLog.d(
            PerformanceLog.ReaderTag,
            "reader WebView restore started epoch=$webViewRestoreEpoch chapter=${readerPosition.loadPosition.index} progress=${readerPosition.loadPosition.progress}",
        )
    }

    fun markWebViewRestored() {
        isWebViewRestoring = false
        PerformanceLog.d(
            PerformanceLog.ReaderTag,
            "reader WebView restore completed epoch=$webViewRestoreEpoch chapter=${readerPosition.loadPosition.index} displayedProgress=${readerPosition.displayedPosition.progress}",
        )
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
        PerformanceLog.d(
            PerformanceLog.ReaderTag,
            "reader viewport state changed old=$webViewViewportSize new=$size reload=${webViewViewportSize != IntSize.Zero}",
        )
        if (webViewViewportSize != IntSize.Zero) {
            prepareReloadAtDisplayedPosition()
        }
        webViewViewportSize = size
    }

    private fun recordJumpOrigin() {
        backHistory = backHistory + readerPosition.displayedPosition
        forwardHistory = emptyList()
    }
}

internal data class ReaderContentReloadKey(
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
    val paragraphSpacing: Double,
)

internal fun ReaderSettings.readerContentReloadKey(): ReaderContentReloadKey =
    ReaderContentReloadKey(
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
        paragraphSpacing = paragraphSpacing,
    )
