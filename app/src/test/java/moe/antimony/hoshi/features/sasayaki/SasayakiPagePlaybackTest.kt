package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch
import org.junit.Assert.*
import org.junit.Test

class SasayakiPagePlaybackTest {
    private fun cue(id: String, time: Double, end: Double, start: Int, chapter: Int = 0) =
        SasayakiMatch(id, time, end, id, chapter, start, 2)

    @Test fun overlappingCuesStopBeforeNextStartAndIncludePrecedingTextGap() {
        val matches = listOf(cue("prev", 1.0, 2.0, 0), cue("a", 4.0, 10.0, 6), cue("b", 6.0, 7.0, 8), cue("next", 8.0, 9.0, 10))
        val range = requireNotNull(sasayakiPageRange(matches, 0, listOf("a", "b")))
        assertEquals(2.05, range.start, 0.0001)
        assertEquals(7.98, range.end, 0.0001)
        assertEquals(4.05, range.audioStart(2.0, 20.0), 0.0001)
        assertEquals(0.0, range.audioStart(-5.0, 20.0), 0.0001)
        assertEquals(6.0, range.audioEnd(2.0, 6.0), 0.0001)
    }

    @Test fun chapterAndCueMembershipAreRequiredAndNoCuePageHasNoSeekTarget() {
        val matches = listOf(cue("a", 1.0, 2.0, 0))
        assertNull(sasayakiPageRange(matches, 0, emptyList()))
        assertNull(sasayakiPageRange(matches, 1, listOf("a")))
        assertNull(sasayakiPageRange(matches, 0, listOf("missing")))
        assertEquals(SasayakiPageRange(1.0, 2.0), sasayakiPageRange(matches, 0, listOf("a")))
    }
}
