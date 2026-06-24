package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch

sealed interface SasayakiCueDisplayAction {
    data object None : SasayakiCueDisplayAction
    data object Clear : SasayakiCueDisplayAction
    data class Display(val cue: SasayakiMatch, val reveal: Boolean) : SasayakiCueDisplayAction
    data class ClearAndLoadChapter(val cue: SasayakiMatch, val reveal: Boolean) : SasayakiCueDisplayAction {
        val chapterIndex: Int get() = cue.chapterIndex
    }
}

class SasayakiCueDisplayCoordinator {
    private var currentCue: SasayakiMatch? = null

    val currentCueStartTime: Double?
        get() = currentCue?.startTime

    fun update(
        cue: SasayakiMatch?,
        currentChapterIndex: Int,
        autoScroll: Boolean,
        hasPlayedOnce: Boolean,
        forceDisplay: Boolean = false,
    ): SasayakiCueDisplayAction {
        if (cue == null) return clear()
        if (!forceDisplay && cue.id == currentCue?.id) return SasayakiCueDisplayAction.None
        if (cue.chapterIndex == currentChapterIndex) {
            currentCue = cue
            return SasayakiCueDisplayAction.Display(cue, reveal = autoScroll && hasPlayedOnce)
        }
        return if (autoScroll && hasPlayedOnce) {
            currentCue = null
            SasayakiCueDisplayAction.ClearAndLoadChapter(cue = cue, reveal = true)
        } else {
            clear()
        }
    }

    fun displaySelectedCue(
        cue: SasayakiMatch,
        currentChapterIndex: Int,
        reveal: Boolean,
    ): SasayakiCueDisplayAction {
        if (cue.chapterIndex != currentChapterIndex) return SasayakiCueDisplayAction.None
        currentCue = cue
        return SasayakiCueDisplayAction.Display(cue, reveal = reveal)
    }

    fun clear(): SasayakiCueDisplayAction {
        if (currentCue == null) return SasayakiCueDisplayAction.None
        currentCue = null
        return SasayakiCueDisplayAction.Clear
    }
}
