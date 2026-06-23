package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SasayakiPlaybackStateCoordinatorTest {
    @Test
    fun initializesUiObservedPlaybackStateFromLastPosition() {
        val coordinator = SasayakiPlaybackStateCoordinator(initialPosition = 12.5)

        assertEquals(12.5, coordinator.currentTime, 0.0)
        assertEquals(0.0, coordinator.duration, 0.0)
        assertFalse(coordinator.isPlaying)

        coordinator.markPlaying()
        assertTrue(coordinator.isPlaying)
        coordinator.markPaused()
        assertFalse(coordinator.isPlaying)
        coordinator.markPlaying()
        coordinator.markCompleted()
        assertFalse(coordinator.isPlaying)
    }

    @Test
    fun pendingSeekCompletesOnlyAfterEngineCallback() {
        val coordinator = SasayakiPlaybackStateCoordinator(initialPosition = 0.0)
        val cue = SasayakiMatch("cue", 10.0, 11.0, "text", 0, 0, 4)

        assertNull(coordinator.completeSeek())
        coordinator.beginSeek(
            seconds = 24.5,
            startPlayback = true,
            updateCue = false,
            savePosition = false,
            displayCue = cue,
        )

        assertTrue(coordinator.hasPendingSeek)
        val seek = coordinator.completeSeek()

        assertFalse(coordinator.hasPendingSeek)
        assertEquals(24.5, coordinator.currentTime, 0.0)
        assertEquals(24.5, seek?.seconds ?: -1.0, 0.0)
        assertTrue(seek?.startPlayback == true)
        assertFalse(seek?.updateCue == true)
        assertFalse(seek?.savePosition == true)
        assertSame(cue, seek?.displayCue)
    }

    @Test
    fun tickUpdatesTimeDurationAndRequestsWholeSecondSaves() {
        val coordinator = SasayakiPlaybackStateCoordinator(initialPosition = 0.0)

        val first = coordinator.updateTick(currentPositionMs = 12_125, durationMs = 60_000)
        assertEquals(12.125, coordinator.currentTime, 0.0)
        assertEquals(60.0, coordinator.duration, 0.0)
        assertTrue(first.shouldSavePosition)
        assertFalse(first.shouldStopPlayback)

        val sameSecond = coordinator.updateTick(currentPositionMs = 12_875, durationMs = 61_000)
        assertEquals(12.875, coordinator.currentTime, 0.0)
        assertEquals(61.0, coordinator.duration, 0.0)
        assertFalse(sameSecond.shouldSavePosition)

        val nextSecond = coordinator.updateTick(currentPositionMs = 13_000, durationMs = -1)
        assertEquals(0.0, coordinator.duration, 0.0)
        assertTrue(nextSecond.shouldSavePosition)
    }

    @Test
    fun playerPositionUpdatesPausedNotificationSeeksAndWholeSecondSaves() {
        val coordinator = SasayakiPlaybackStateCoordinator(initialPosition = 0.0)

        val first = coordinator.updatePlayerPosition(currentPositionMs = 42_500, durationMs = 120_000)
        assertEquals(42.5, coordinator.currentTime, 0.0)
        assertEquals(120.0, coordinator.duration, 0.0)
        assertTrue(first)

        val sameSecond = coordinator.updatePlayerPosition(currentPositionMs = 42_800, durationMs = 120_000)
        assertEquals(42.8, coordinator.currentTime, 0.0)
        assertFalse(sameSecond)
    }

    @Test
    fun temporaryPlaybackReturnSuppressesSaveUntilRestoredPositionSecond() {
        val coordinator = SasayakiPlaybackStateCoordinator(initialPosition = 0.0)

        coordinator.setTemporaryPlaybackReturnPosition(24.5)
        val previewTick = coordinator.updateTick(currentPositionMs = 25_000, durationMs = 90_000)
        assertFalse(previewTick.shouldSavePosition)

        assertEquals(24.5, coordinator.restoreTemporaryPlaybackPositionIfNeeded() ?: -1.0, 0.0)
        assertEquals(24.5, coordinator.currentTime, 0.0)

        val restoredSecondTick = coordinator.updateTick(currentPositionMs = 24_900, durationMs = 90_000)
        assertFalse(restoredSecondTick.shouldSavePosition)
    }

    @Test
    fun stopPlaybackTimeRequestsOnePauseWhenPlaying() {
        val coordinator = SasayakiPlaybackStateCoordinator(initialPosition = 0.0)

        coordinator.setStopPlaybackTime(1.5)
        var tick = coordinator.updateTick(currentPositionMs = 1_600, durationMs = 10_000)
        assertFalse(tick.shouldStopPlayback)

        coordinator.markPlaying()
        coordinator.setStopPlaybackTime(2.0)
        tick = coordinator.updateTick(currentPositionMs = 2_100, durationMs = 10_000)
        assertTrue(tick.shouldStopPlayback)

        val nextTick = coordinator.updateTick(currentPositionMs = 2_200, durationMs = 10_000)
        assertFalse(nextTick.shouldStopPlayback)
    }

    @Test
    fun clearAudioStateResetsPlaybackStateAndPendingWork() {
        val coordinator = SasayakiPlaybackStateCoordinator(initialPosition = 10.0)
        coordinator.markPlaying()
        coordinator.setStopPlaybackTime(12.0)
        coordinator.setTemporaryPlaybackReturnPosition(8.0)
        coordinator.beginSeek(
            seconds = 11.0,
            startPlayback = true,
            updateCue = true,
            savePosition = true,
            displayCue = null,
        )

        coordinator.clearAudioState()

        assertEquals(0.0, coordinator.currentTime, 0.0)
        assertEquals(0.0, coordinator.duration, 0.0)
        assertFalse(coordinator.isPlaying)
        assertFalse(coordinator.hasPendingSeek)
        assertNull(coordinator.restoreTemporaryPlaybackPositionIfNeeded())
        assertTrue(coordinator.updateTick(currentPositionMs = 0, durationMs = 0).shouldSavePosition)
    }
}
