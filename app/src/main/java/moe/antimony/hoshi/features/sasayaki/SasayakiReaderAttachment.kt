package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch

internal class SasayakiReaderAttachment {
    private var getCurrentChapterIndex: (() -> Int)? = null
    private var onCue: ((SasayakiMatch, Boolean) -> Unit)? = null
    private var onClearCue: (() -> Unit)? = null

    fun attach(
        getCurrentChapterIndex: () -> Int,
        onCue: (SasayakiMatch, Boolean) -> Unit,
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

    fun currentChapterIndex(): Int =
        getCurrentChapterIndex?.invoke() ?: 0

    fun cue(cue: SasayakiMatch, reveal: Boolean) {
        onCue?.invoke(cue, reveal)
    }

    fun clearCue() {
        onClearCue?.invoke()
    }
}
