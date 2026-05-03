package moe.antimony.hoshi.features.sasayaki

import kotlin.math.max

class SasayakiCueNavigationController(matchData: SasayakiMatchData?) {
    private val timeline = CueTimeline(matchData)

    fun nextCueSeekTime(
        currentCueStartTime: Double?,
        currentTime: Double,
        delay: Double,
    ): Double? {
        val next = timeline.nextCue(after = currentCueStartTime ?: currentTime - delay) ?: return null
        return next + delay
    }

    fun previousCueSeekTime(
        currentCueStartTime: Double?,
        currentTime: Double,
        delay: Double,
    ): Double {
        val previous = timeline.previousCue(before = currentCueStartTime ?: max(0.0, currentTime - delay)) ?: 0.0
        return previous + delay
    }

    fun cueAtPlaybackTime(time: Double, delay: Double): SasayakiMatch? =
        timeline.cueAt(time - delay)

    fun findCue(chapterIndex: Int, offset: Int): SasayakiMatch? =
        timeline.findCue(chapterIndex = chapterIndex, offset = offset)
}
