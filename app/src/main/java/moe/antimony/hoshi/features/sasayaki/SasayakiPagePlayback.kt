package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch

/** A transient service-owned boundary, independent of single-cue audition state. */
internal data class SasayakiPageRange(val start: Double, val end: Double) {
    fun audioStart(delay: Double, duration: Double): Double = clamp(start + delay, duration)
    fun audioEnd(delay: Double, duration: Double): Double = clamp(end + delay, duration)
    // Match the millisecond precision used by the playback engine and seek commands.
    fun contains(time: Double, delay: Double): Boolean =
        (time * 1000).toLong() >= ((start + delay) * 1000).toLong() &&
            (time * 1000).toLong() < ((end + delay) * 1000).toLong()
    private fun clamp(time: Double, duration: Double): Double =
        if (duration > 0.0) time.coerceIn(0.0, duration) else time.coerceAtLeast(0.0)
}

internal fun sasayakiPageRange(matches: List<SasayakiMatch>, chapterIndex: Int, cueIds: List<String>): SasayakiPageRange? {
    val ids = cueIds.toSet()
    val cues = matches.filter { it.chapterIndex == chapterIndex && it.id in ids }
    val first = cues.minByOrNull { it.startTime } ?: return null
    val last = cues.maxBy { it.startTime }
    var end = cues.maxOf { it.endTime }
    matches.filter { it.startTime > last.startTime }.minByOrNull { it.startTime }?.let { next ->
        end = minOf(end, maxOf(next.startTime - 0.02, last.startTime))
    }
    var start = first.startTime
    matches.filter { it.startTime < first.startTime }.maxByOrNull { it.startTime }?.let { previous ->
        if (first.start > if (previous.chapterIndex == first.chapterIndex) previous.start + previous.length else 0) {
            start = minOf(start, previous.endTime + 0.05)
        }
    }
    return SasayakiPageRange(start, end)
}
