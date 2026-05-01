package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SasayakiTimelineTest {
    private val match = SasayakiMatchData(
        matches = listOf(
            SasayakiMatch("a", 10.0, 12.5, "alpha", 0, 0, 5),
            SasayakiMatch("b", 15.0, 18.0, "bravo", 0, 6, 5),
            SasayakiMatch("c", 22.0, 25.0, "charlie", 1, 0, 7),
        ),
        unmatched = 0,
    )

    @Test
    fun findsCueAtStartOrInsideCueWindowLikeIos() {
        val timeline = CueTimeline(match)

        assertEquals("a", timeline.cueAt(10.0)?.id)
        assertEquals("a", timeline.cueAt(12.5)?.id)
        assertNull(timeline.cueAt(13.0))
        assertEquals("b", timeline.cueAt(15.005)?.id)
    }

    @Test
    fun nextAndPreviousCueUseCueStartsLikeIos() {
        val timeline = CueTimeline(match)

        assertEquals(15.0, timeline.nextCue(after = 10.0) ?: -1.0, 0.0)
        assertEquals(10.0, timeline.previousCue(before = 15.0) ?: -1.0, 0.0)
        assertNull(timeline.previousCue(before = 10.0))
    }

    @Test
    fun findsCueContainingReaderOffset() {
        val timeline = CueTimeline(match)

        assertEquals("b", timeline.findCue(chapterIndex = 0, offset = 8)?.id)
        assertNull(timeline.findCue(chapterIndex = 0, offset = 12))
        assertEquals("c", timeline.findCue(chapterIndex = 1, offset = 1)?.id)
    }
}
