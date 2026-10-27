package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import org.junit.Test

class SasayakiPlaybackCommandCoordinatorTest {
    @Test
    fun restartEndsTemporarySingleCuePlaybackAndSavesNormalContinuousPosition() {
        val harness = commandHarness(SasayakiMatchData(
            listOf(SasayakiMatch("current", 10.0, 12.0, "current", 0, 0, 7)), 0,
        ))
        harness.state.setStopPlaybackTime(12.5)
        harness.state.setTemporaryPlaybackReturnPosition(2.0)

        assertTrue(harness.commands.restartCurrentCue(currentTime = 11.5, delay = 0.5))
        val seek = requireNotNull(harness.state.completeSeek())
        assertEquals(10.5, seek.seconds, 0.0)
        assertTrue(seek.startPlayback)
        assertTrue(seek.savePosition)
        assertTrue(seek.revealCue)
        assertNull(harness.state.restoreTemporaryPlaybackPositionIfNeeded())
        harness.state.markPlaying()
        val tick = harness.state.updateTick(13_000, 30_000)
        assertFalse(tick.shouldStopPlayback)
        assertTrue(tick.shouldSavePosition)
    }

    @Test
    fun skipForwardSeeksByConfiguredSecondsAndClampsToDuration() {
        val harness = commandHarness()

        harness.commands.skipForward(
            currentTime = 28.0,
            duration = 30.0,
            seconds = 10,
            isPlaying = false,
        )

        assertEquals(30.0, harness.state.completeSeek()?.seconds ?: -1.0, 0.0)
    }

    @Test
    fun skipBackwardSeeksByConfiguredSecondsAndClampsToZero() {
        val harness = commandHarness()

        harness.commands.skipBackward(
            currentTime = 3.0,
            seconds = 5,
            isPlaying = false,
        )

        assertEquals(0.0, harness.state.completeSeek()?.seconds ?: -1.0, 0.0)
    }

    private fun commandHarness(matchData: SasayakiMatchData? = null): CommandHarness {
        val state = SasayakiPlaybackStateCoordinator(initialPosition = 0.0)
        val lifecycle = SasayakiPlaybackLifecycleController(
            playbackState = state,
            tickScheduler = NoOpTickScheduler,
        )
        lifecycle.attachEngine(NoOpPlaybackEngine)
        return CommandHarness(
            state = state,
            commands = SasayakiPlaybackCommandCoordinator(
                playbackState = state,
                playbackLifecycle = lifecycle,
                cueNavigation = SasayakiCueNavigationController(matchData),
            ),
        )
    }

    private data class CommandHarness(
        val state: SasayakiPlaybackStateCoordinator,
        val commands: SasayakiPlaybackCommandCoordinator,
    )

    private object NoOpTickScheduler : SasayakiTickScheduler {
        override fun postTick() = Unit
        override fun stopTicking() = Unit
    }

    private object NoOpPlaybackEngine : SasayakiPlaybackEngine {
        override val durationMs: Int = 0
        override val currentPositionMs: Int = 0

        override fun start(rate: Float) = Unit
        override fun pause() = Unit
        override fun setRate(rate: Float) = Unit
        override fun seekTo(positionMs: Int) = Unit
        override fun release() = Unit
    }
}
