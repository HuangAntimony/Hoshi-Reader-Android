package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch

internal class SasayakiReaderAttachment {
    private var getCurrentChapterIndex: (() -> Int)? = null
    private var onCue: ((SasayakiMatch, Boolean) -> Unit)? = null
    private var onClearCue: (() -> Unit)? = null
    private var onLoadChapter: ((SasayakiMatch, Boolean) -> Unit)? = null

    fun attach(
        getCurrentChapterIndex: () -> Int,
        onCue: (SasayakiMatch, Boolean) -> Unit,
        onClearCue: () -> Unit,
        onLoadChapter: (SasayakiMatch, Boolean) -> Unit,
    ) {
        this.getCurrentChapterIndex = getCurrentChapterIndex
        this.onCue = onCue
        this.onClearCue = onClearCue
        this.onLoadChapter = onLoadChapter
    }

    fun detach() {
        getCurrentChapterIndex = null
        onCue = null
        onClearCue = null
        onLoadChapter = null
    }

    fun currentChapterIndex(): Int =
        getCurrentChapterIndex?.invoke() ?: 0

    fun cue(cue: SasayakiMatch, reveal: Boolean) {
        onCue?.invoke(cue, reveal)
    }

    fun clearCue() {
        onClearCue?.invoke()
    }

    fun loadChapter(cue: SasayakiMatch, reveal: Boolean) {
        onLoadChapter?.invoke(cue, reveal)
    }
}
