package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch

internal class SasayakiReaderAttachment {
    private var getCurrentChapterIndex: (() -> Int?)? = null
    private var onCue: ((SasayakiMatch, Boolean, SasayakiCueRevealSource) -> Unit)? = null
    private var onClearCue: (() -> Unit)? = null

    val isAttached: Boolean
        get() = onCue != null

    fun attach(
        getCurrentChapterIndex: () -> Int?,
        onCue: (SasayakiMatch, Boolean, SasayakiCueRevealSource) -> Unit,
        onClearCue: () -> Unit,
    ) {
        this.getCurrentChapterIndex = getCurrentChapterIndex
        this.onCue = onCue
        this.onClearCue = onClearCue
    }

    fun detach() {
        getCurrentChapterIndex = null
        onCue = null
        onClearCue = null
    }

    // A whole-book Reader can present a cue in any chapter. Preserve the detached service fallback.
    fun currentChapterIndex(): Int? {
        val attachedChapter = getCurrentChapterIndex ?: return 0
        return attachedChapter()
    }

    fun cue(cue: SasayakiMatch, reveal: Boolean, source: SasayakiCueRevealSource) {
        onCue?.invoke(cue, reveal, source)
    }

    fun clearCue() {
        onClearCue?.invoke()
    }
}
