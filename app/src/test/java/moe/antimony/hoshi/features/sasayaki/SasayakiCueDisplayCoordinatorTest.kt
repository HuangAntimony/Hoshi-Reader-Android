package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SasayakiCueDisplayCoordinatorTest {
    private val cueA = SasayakiMatch("a", 10.0, 12.0, "a", 0, 0, 3)
    private val cueB = SasayakiMatch("b", 20.0, 22.0, "b", 0, 4, 3)
    private val otherChapterCue = SasayakiMatch("c", 30.0, 32.0, "c", 1, 0, 3)

    @Test
    fun displaysCueInCurrentChapterAndTracksCurrentCueStartTime() {
        val coordinator = SasayakiCueDisplayCoordinator()

        val action = coordinator.update(
            cue = cueA,
            currentChapterIndex = 0,
            autoScroll = true,
            hasPlayedOnce = true,
        )

        assertDisplay(action, cueA, reveal = true)
        assertEquals(10.0, coordinator.currentCueStartTime ?: -1.0, 0.0)
    }

    @Test
    fun suppressesSameCueUnlessForced() {
        val coordinator = SasayakiCueDisplayCoordinator()
        coordinator.update(cueA, currentChapterIndex = 0, autoScroll = true, hasPlayedOnce = true)

        assertTrue(
            coordinator.update(cueA, currentChapterIndex = 0, autoScroll = true, hasPlayedOnce = true)
                is SasayakiCueDisplayAction.None,
        )

        val forced = coordinator.update(
            cue = cueA,
            currentChapterIndex = 0,
            autoScroll = false,
            hasPlayedOnce = true,
            forceDisplay = true,
        )
        assertDisplay(forced, cueA, reveal = false)
    }

    @Test
    fun clearsOnlyWhenCueWasDisplayed() {
        val coordinator = SasayakiCueDisplayCoordinator()

        assertTrue(coordinator.update(null, 0, autoScroll = true, hasPlayedOnce = true) is SasayakiCueDisplayAction.None)

        coordinator.update(cueA, currentChapterIndex = 0, autoScroll = true, hasPlayedOnce = true)
        assertTrue(coordinator.update(null, 0, autoScroll = true, hasPlayedOnce = true) is SasayakiCueDisplayAction.Clear)
        assertNull(coordinator.currentCueStartTime)
        assertTrue(coordinator.clear() is SasayakiCueDisplayAction.None)
    }

    @Test
    fun requestsClearAndChapterLoadForCrossChapterAutoScrollAfterPlaybackStarted() {
        val coordinator = SasayakiCueDisplayCoordinator()
        coordinator.update(cueA, currentChapterIndex = 0, autoScroll = true, hasPlayedOnce = true)

        val action = coordinator.update(
            cue = otherChapterCue,
            currentChapterIndex = 0,
            autoScroll = true,
            hasPlayedOnce = true,
        )

        assertTrue(action is SasayakiCueDisplayAction.ClearAndLoadChapter)
        action as SasayakiCueDisplayAction.ClearAndLoadChapter
        assertSame(otherChapterCue, action.cue)
        assertEquals(1, action.chapterIndex)
        assertEquals(true, action.reveal)
        assertNull(coordinator.currentCueStartTime)
    }

    @Test
    fun clearsInsteadOfLoadingChapterWhenAutoScrollIsUnavailable() {
        val coordinator = SasayakiCueDisplayCoordinator()
        coordinator.update(cueA, currentChapterIndex = 0, autoScroll = true, hasPlayedOnce = true)

        assertTrue(
            coordinator.update(otherChapterCue, currentChapterIndex = 0, autoScroll = false, hasPlayedOnce = true)
                is SasayakiCueDisplayAction.Clear,
        )

        coordinator.update(cueA, currentChapterIndex = 0, autoScroll = true, hasPlayedOnce = true)
        assertTrue(
            coordinator.update(otherChapterCue, currentChapterIndex = 0, autoScroll = true, hasPlayedOnce = false)
                is SasayakiCueDisplayAction.Clear,
        )
    }

    @Test
    fun selectedPopupCueDisplaysOnlyInCurrentChapter() {
        val coordinator = SasayakiCueDisplayCoordinator()

        assertTrue(
            coordinator.displaySelectedCue(otherChapterCue, currentChapterIndex = 0, reveal = true)
                is SasayakiCueDisplayAction.None,
        )
        assertNull(coordinator.currentCueStartTime)

        val action = coordinator.displaySelectedCue(cueB, currentChapterIndex = 0, reveal = true)
        assertDisplay(action, cueB, reveal = true)
        assertEquals(20.0, coordinator.currentCueStartTime ?: -1.0, 0.0)
    }

    private fun assertDisplay(action: SasayakiCueDisplayAction, cue: SasayakiMatch, reveal: Boolean) {
        assertTrue(action is SasayakiCueDisplayAction.Display)
        val display = action as SasayakiCueDisplayAction.Display
        assertSame(cue, display.cue)
        assertEquals(reveal, display.reveal)
    }
}
