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

    var showReaderMenu by mutableStateOf(false)
        private set

    var lookupPopups by mutableStateOf<List<LookupPopupItem>>(emptyList())
        private set

    var readerPosition by mutableStateOf(ReaderPositionState(initialPosition))
        private set

    var webViewViewportSize by mutableStateOf(IntSize.Zero)
        private set

    var sasayakiWasPausedByLookup by mutableStateOf(false)
        private set

    fun syncSettings(settings: ReaderSettings) {
        effectiveSettings = settings
    }

    fun applySettings(settings: ReaderSettings) {
        readerPosition = readerPosition.prepareReloadAtDisplayedPosition()
        effectiveSettings = settings
    }

    fun showReaderMenu() {
        showReaderMenu = true
    }

    fun dismissReaderMenu() {
        showReaderMenu = false
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

    fun prepareReloadAtDisplayedPosition() {
        readerPosition = readerPosition.prepareReloadAtDisplayedPosition()
    }

    fun jumpTo(position: ReaderChapterPosition, fragment: String? = null): ReaderChapterPosition {
        readerPosition = readerPosition.jumpTo(position, fragment)
        return readerPosition.displayedPosition
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
