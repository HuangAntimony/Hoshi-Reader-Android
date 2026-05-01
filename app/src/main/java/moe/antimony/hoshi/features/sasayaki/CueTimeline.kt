package moe.antimony.hoshi.features.sasayaki

class CueTimeline(match: SasayakiMatchData? = null) {
    private val cues = match?.matches.orEmpty()

    fun nextCue(after: Double): Double? {
        var index = findCueIndex(after)
        if (index < cues.size && cues[index].startTime == after) {
            index += 1
        }
        return cues.getOrNull(index)?.startTime
    }

    fun previousCue(before: Double): Double? {
        val index = findCueIndex(before)
        return if (index > 0) cues[index - 1].startTime else null
    }

    fun cueAt(time: Double): SasayakiMatch? {
        val index = findCueIndex(time)
        if (index < cues.size && kotlin.math.abs(cues[index].startTime - time) <= 0.01) {
            return cues[index]
        }
        if (index == 0) return null
        val cue = cues[index - 1]
        return if (time <= cue.endTime) cue else null
    }

    fun findCue(chapterIndex: Int, offset: Int): SasayakiMatch? {
        var low = 0
        var high = cues.size
        while (low < high) {
            val mid = (low + high) / 2
            val cue = cues[mid]
            if (cue.chapterIndex < chapterIndex || (cue.chapterIndex == chapterIndex && cue.start + cue.length <= offset)) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        val cue = cues.getOrNull(low) ?: return null
        return if (cue.chapterIndex == chapterIndex && cue.start <= offset) cue else null
    }

    private fun findCueIndex(time: Double): Int {
        var low = 0
        var high = cues.size
        while (low < high) {
            val mid = (low + high) / 2
            if (cues[mid].startTime < time) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low
    }
}
