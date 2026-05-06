package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiMatch

import kotlin.math.max

class SasayakiCueNavigationController(matchData: SasayakiMatchData?) {
    private val timeline = CueTimeline(matchData)

    fun nextCueSeekTime(
        currentTime: Double,
        delay: Double,
    ): Double? {
        val playbackTime = currentTime - delay
        val anchor = timeline.cueAt(playbackTime)?.startTime ?: playbackTime
        val next = timeline.nextCue(after = anchor) ?: return null
        return next + delay
    }

    fun previousCueSeekTime(
        currentTime: Double,
        delay: Double,
    ): Double {
        val playbackTime = max(0.0, currentTime - delay)
        val anchor = timeline.cueAt(playbackTime)?.startTime ?: playbackTime
        val previous = timeline.previousCue(before = anchor) ?: 0.0
        return previous + delay
    }

    fun cueAtPlaybackTime(time: Double, delay: Double): SasayakiMatch? =
        timeline.cueAt(time - delay)

    fun findCue(chapterIndex: Int, offset: Int): SasayakiMatch? =
        timeline.findCue(chapterIndex = chapterIndex, offset = offset)
}
