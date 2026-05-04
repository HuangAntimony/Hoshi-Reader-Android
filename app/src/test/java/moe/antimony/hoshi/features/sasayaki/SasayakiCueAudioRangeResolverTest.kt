package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import org.junit.Assert.assertEquals
import org.junit.Test

class SasayakiCueAudioRangeResolverTest {
    @Test
    fun expandsCueAudioToAdjacentCuesContainedInSentence() {
        val target = cue(id = "b", start = 12.0, end = 13.0, text = "は")
        val matchData = SasayakiMatchData(
            matches = listOf(
                cue(id = "a", start = 10.0, end = 12.0, text = "僕"),
                target,
                cue(id = "c", start = 13.0, end = 15.5, text = "学校へ行った"),
                cue(id = "d", start = 20.0, end = 21.0, text = "次の文"),
            ),
            unmatched = 0,
        )

        val range = SasayakiCueAudioRangeResolver.resolve(
            matchData = matchData,
            cue = target,
            sentence = "「僕は学校へ行った。」",
            delay = 0.25,
        )

        assertEquals(10.25, range.startTime, 0.0001)
        assertEquals(15.75, range.endTime, 0.0001)
    }

    @Test
    fun expansionDoesNotCrossChapterBoundary() {
        val target = cue(id = "b", start = 12.0, end = 13.0, text = "は")
        val matchData = SasayakiMatchData(
            matches = listOf(
                cue(id = "a", start = 10.0, end = 12.0, text = "僕"),
                target,
                cue(id = "other", start = 13.0, end = 15.5, text = "学校へ行った", chapterIndex = 1),
            ),
            unmatched = 0,
        )

        val range = SasayakiCueAudioRangeResolver.resolve(
            matchData = matchData,
            cue = target,
            sentence = "僕は学校へ行った。",
            delay = 0.0,
        )

        assertEquals(10.0, range.startTime, 0.0001)
        assertEquals(13.0, range.endTime, 0.0001)
    }

    @Test
    fun fallsBackToCueRangeWhenCueIsMissingFromMatchData() {
        val target = cue(id = "missing", start = 12.0, end = 13.0, text = "は")
        val matchData = SasayakiMatchData(
            matches = listOf(cue(id = "a", start = 10.0, end = 12.0, text = "僕")),
            unmatched = 0,
        )

        val range = SasayakiCueAudioRangeResolver.resolve(
            matchData = matchData,
            cue = target,
            sentence = "僕は学校へ行った。",
            delay = -0.25,
        )

        assertEquals(11.75, range.startTime, 0.0001)
        assertEquals(12.75, range.endTime, 0.0001)
    }

    private fun cue(
        id: String,
        start: Double,
        end: Double,
        text: String,
        chapterIndex: Int = 0,
    ): SasayakiMatch = SasayakiMatch(
        id = id,
        startTime = start,
        endTime = end,
        text = text,
        chapterIndex = chapterIndex,
        start = 0,
        length = text.length,
    )
}
