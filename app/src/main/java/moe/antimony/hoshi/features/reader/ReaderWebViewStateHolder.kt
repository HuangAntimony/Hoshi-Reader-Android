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

    var showGoTo by mutableStateOf(false)
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

    fun openGoToFromMenu() {
        showReaderMenu = false
        showGoTo = true
    }

    fun dismissGoTo() {
        showGoTo = false
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
        readerPosition = readerPosition.prepareReloadAtDisplayedPosition()
        markWebViewRestoring()
    }

    fun jumpTo(position: ReaderChapterPosition, fragment: String? = null): ReaderChapterPosition {
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

    fun updateViewportSize(size: IntSize): Boolean {
        if (size == webViewViewportSize) return false
        val resizedExistingViewport = webViewViewportSize != IntSize.Zero
        if (resizedExistingViewport) {
            setLookupPopups(emptyList())
            prepareReloadAtDisplayedPosition()
        }
        webViewViewportSize = size
        return resizedExistingViewport
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
    val viewMode: ReaderViewMode,
    val visualNovelScreenMode: VisualNovelScreenMode,
    val visualNovelSentencesPerScreen: Int,
    val visualNovelPreserveDialogueBubbles: Boolean,
    val visualNovelMergeCrossScreenSasayakiCues: Boolean,
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
        viewMode = viewMode,
        visualNovelScreenMode = visualNovelScreenMode,
        visualNovelSentencesPerScreen = visualNovelSentencesPerScreen.coerceIn(1, 12),
        visualNovelPreserveDialogueBubbles = visualNovelPreserveDialogueBubbles,
        visualNovelMergeCrossScreenSasayakiCues = visualNovelMergeCrossScreenSasayakiCues,
        horizontalPadding = horizontalPadding,
        verticalPadding = verticalPadding,
        avoidPageBreak = avoidPageBreak,
        justifyText = justifyText,
        layoutAdvanced = layoutAdvanced,
        lineHeight = lineHeight,
        characterSpacing = characterSpacing,
        paragraphSpacing = paragraphSpacing,
    )
