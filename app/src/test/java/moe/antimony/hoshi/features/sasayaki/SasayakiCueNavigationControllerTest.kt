package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiMatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SasayakiCueNavigationControllerTest {
    private val match = SasayakiMatchData(
        matches = listOf(
            SasayakiMatch("a", 10.0, 12.5, "alpha", 0, 0, 5),
            SasayakiMatch("b", 15.0, 18.0, "bravo", 0, 6, 5),
            SasayakiMatch("c", 22.0, 25.0, "charlie", 1, 0, 7),
        ),
        unmatched = 0,
    )

    @Test
    fun nextCueUsesActivePlaybackCueWhenAvailableAndAddsDelay() {
        val navigation = SasayakiCueNavigationController(match)

        assertEquals(
            15.5,
            navigation.nextCueSeekTime(
                currentTime = 10.5,
                delay = 0.5,
            ) ?: -1.0,
            0.0,
        )
    }

    @Test
    fun nextCueFallsBackToCurrentPlaybackTimeMinusDelay() {
        val navigation = SasayakiCueNavigationController(match)

        assertEquals(
            22.25,
            navigation.nextCueSeekTime(
                currentTime = 16.75,
                delay = 0.25,
            ) ?: -1.0,
            0.0,
        )
        assertNull(
            navigation.nextCueSeekTime(
                currentTime = 22.25,
                delay = 0.25,
            ),
        )
    }

    @Test
    fun previousCueUsesDisplayedCueAndFallsBackToStartPlusDelay() {
        val navigation = SasayakiCueNavigationController(match)

        assertEquals(
            10.25,
            navigation.previousCueSeekTime(
                currentTime = 15.25,
                delay = 0.25,
            ),
            0.0,
        )
        assertEquals(
            0.5,
            navigation.previousCueSeekTime(
                currentTime = 0.25,
                delay = 0.5,
            ),
            0.0,
        )
    }

    @Test
    fun cueNavigationUsesPlaybackTimeInsteadOfStaleDisplayedCue() {
        val navigation = SasayakiCueNavigationController(
            SasayakiMatchData(
                matches = listOf(
                    SasayakiMatch("2019", 7967.135, 7971.115, "old", 12, 0, 3),
                    SasayakiMatch("2020", 7971.759, 7975.799, "previous", 12, 3, 8),
                    SasayakiMatch("2021", 7976.847, 7979.335, "current", 12, 11, 7),
                    SasayakiMatch("2022", 7980.655, 7983.475, "next", 12, 18, 4),
                ),
                unmatched = 0,
            ),
        )

        assertEquals(
            7971.759,
            navigation.previousCueSeekTime(
                currentTime = 7976.847,
                delay = 0.0,
            ),
            0.0,
        )
        assertEquals(
            7980.655,
            navigation.nextCueSeekTime(
                currentTime = 7976.847,
                delay = 0.0,
            ) ?: -1.0,
            0.0,
        )
    }

    @Test
    fun cueAtPlaybackTimeSubtractsDelayBeforeLookup() {
        val navigation = SasayakiCueNavigationController(match)

        assertEquals("b", navigation.cueAtPlaybackTime(time = 15.5, delay = 0.5)?.id)
        assertNull(navigation.cueAtPlaybackTime(time = 14.98, delay = 0.0))
    }

    @Test
    fun selectedTextLookupDelegatesToTimeline() {
        val navigation = SasayakiCueNavigationController(match)

        assertEquals("a", navigation.findCue(chapterIndex = 0, offset = 0)?.id)
        assertEquals("b", navigation.findCue(chapterIndex = 0, offset = 8)?.id)
        assertNull(navigation.findCue(chapterIndex = 0, offset = 11))
        assertEquals("c", navigation.findCue(chapterIndex = 1, offset = 1)?.id)
    }
}
