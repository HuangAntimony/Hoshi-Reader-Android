package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch

sealed interface SasayakiCueDisplayAction {
    data object None : SasayakiCueDisplayAction
    data object Clear : SasayakiCueDisplayAction
    data class Display(
        val cue: SasayakiMatch,
        val reveal: Boolean,
        val source: SasayakiCueRevealSource,
    ) : SasayakiCueDisplayAction
    data class ClearAndDisplay(
        val cue: SasayakiMatch,
        val reveal: Boolean,
        val source: SasayakiCueRevealSource,
    ) : SasayakiCueDisplayAction
}

class SasayakiCueDisplayCoordinator {
    private var currentCue: SasayakiMatch? = null
    private var quietCrossChapterCue: SasayakiMatch? = null

    val currentCueStartTime: Double?
        get() = currentCue?.startTime

    fun update(
        cue: SasayakiMatch?,
        currentChapterIndex: Int,
        autoScroll: Boolean,
        hasPlayedOnce: Boolean,
        source: SasayakiCueRevealSource = SasayakiCueRevealSource.DirectJump,
        forceDisplay: Boolean = false,
    ): SasayakiCueDisplayAction {
        if (cue == null) return clear()
        if (
            !forceDisplay && source == SasayakiCueRevealSource.NaturalPlayback &&
            cue == quietCrossChapterCue && cue.chapterIndex != currentChapterIndex
        ) return SasayakiCueDisplayAction.None
        quietCrossChapterCue = null
        if (!forceDisplay && cue == currentCue) return SasayakiCueDisplayAction.None
        if (cue.chapterIndex == currentChapterIndex) {
            currentCue = cue
            return SasayakiCueDisplayAction.Display(
                cue = cue,
                reveal = autoScroll && hasPlayedOnce,
                source = source,
            )
        }
        return if (autoScroll && hasPlayedOnce) {
            currentCue = null
            SasayakiCueDisplayAction.ClearAndDisplay(
                cue = cue,
                reveal = true,
                source = source,
            )
        } else {
            clear()
        }
    }

    fun refresh(cue: SasayakiMatch?, currentChapterIndex: Int): SasayakiCueDisplayAction {
        if (cue == null) return clear()
        if (cue.chapterIndex != currentChapterIndex) {
            val action = clear()
            // A data refresh must not turn into a chapter jump on the next playback tick.
            quietCrossChapterCue = cue
            return action
        }
        return update(
            cue,
            currentChapterIndex,
            autoScroll = false,
            hasPlayedOnce = false,
            source = SasayakiCueRevealSource.MatchRefresh,
        )
    }

    fun displaySelectedCue(
        cue: SasayakiMatch,
        currentChapterIndex: Int,
        reveal: Boolean,
    ): SasayakiCueDisplayAction {
        if (cue.chapterIndex != currentChapterIndex) return SasayakiCueDisplayAction.None
        quietCrossChapterCue = null
        currentCue = cue
        return SasayakiCueDisplayAction.Display(
            cue = cue,
            reveal = reveal,
            source = SasayakiCueRevealSource.DirectJump,
        )
    }

    fun clear(): SasayakiCueDisplayAction {
        quietCrossChapterCue = null
        if (currentCue == null) return SasayakiCueDisplayAction.None
        currentCue = null
        return SasayakiCueDisplayAction.Clear
    }
}
