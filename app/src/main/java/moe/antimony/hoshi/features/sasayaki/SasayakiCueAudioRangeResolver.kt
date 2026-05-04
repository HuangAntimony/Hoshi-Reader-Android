package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.filteredReaderText
import kotlin.math.max

internal data class SasayakiCueAudioRange(
    val startTime: Double,
    val endTime: Double,
)

internal object SasayakiCueAudioRangeResolver {
    fun resolve(
        matchData: SasayakiMatchData?,
        cue: SasayakiMatch,
        sentence: String,
        delay: Double,
    ): SasayakiCueAudioRange {
        val expanded = expandCue(matchData = matchData, cue = cue, sentence = sentence)
        val start = max(0.0, expanded.startTime + delay)
        val end = max(start, expanded.endTime + delay)
        return SasayakiCueAudioRange(startTime = start, endTime = end)
    }

    private fun expandCue(
        matchData: SasayakiMatchData?,
        cue: SasayakiMatch,
        sentence: String,
    ): SasayakiCueAudioRange {
        val cues = matchData
            ?.matches
            ?.filter { it.chapterIndex == cue.chapterIndex }
            ?: return cue.audioRange()
        val index = cues.indexOfFirst { it.id == cue.id }
        if (index < 0) return cue.audioRange()

        var start = index
        var end = index
        val filteredSentence = sentence.filteredReaderText()
        while (start > cues.indices.first && filteredSentence.contains(cues[start - 1].text.filteredReaderText())) {
            start -= 1
        }
        while (end < cues.indices.last && filteredSentence.contains(cues[end + 1].text.filteredReaderText())) {
            end += 1
        }
        return SasayakiCueAudioRange(startTime = cues[start].startTime, endTime = cues[end].endTime)
    }

    private fun SasayakiMatch.audioRange(): SasayakiCueAudioRange =
        SasayakiCueAudioRange(startTime = startTime, endTime = endTime)
}
