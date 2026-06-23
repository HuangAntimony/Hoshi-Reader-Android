package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Test

class SasayakiTemporaryPlaybackRestoreCoordinatorTest {
    @Test
    fun restoreNoOpsWhenThereIsNoTemporaryReturnPosition() {
        val harness = restoreHarness()

        harness.coordinator.restoreIfNeeded(
            updateCue = { harness.events += "cue:$it" },
        )

        assertEquals(emptyList<String>(), harness.events)
    }

    @Test
    fun restoreSeeksEngineThenRefreshesCue() {
        val harness = restoreHarness()
        harness.playbackState.setTemporaryPlaybackReturnPosition(14.25)

        harness.coordinator.restoreIfNeeded(
            updateCue = { harness.events += "cue:$it" },
        )

        assertEquals(14.25, harness.playbackState.currentTime, 0.0)
        assertEquals(listOf("engine-seek:14250", "cue:14.25"), harness.events)
    }

    private fun restoreHarness(): RestoreHarness {
        val events = mutableListOf<String>()
        val playbackState = SasayakiPlaybackStateCoordinator(initialPosition = 0.0)
        val playbackLifecycle = SasayakiPlaybackLifecycleController(
            playbackState = playbackState,
            tickScheduler = FakeTickScheduler,
        )
        playbackLifecycle.attachEngine(FakePlaybackEngine(events))
        return RestoreHarness(
            playbackState = playbackState,
            coordinator = SasayakiTemporaryPlaybackRestoreCoordinator(
                playbackState = playbackState,
                playbackLifecycle = playbackLifecycle,
            ),
            events = events,
        )
    }

    private data class RestoreHarness(
        val playbackState: SasayakiPlaybackStateCoordinator,
        val coordinator: SasayakiTemporaryPlaybackRestoreCoordinator,
        val events: MutableList<String>,
    )

    private data object FakeTickScheduler : SasayakiTickScheduler {
        override fun postTick() = Unit

        override fun stopTicking() = Unit
    }

    private class FakePlaybackEngine(
        private val events: MutableList<String>,
    ) : SasayakiPlaybackEngine {
        override var durationMs: Int = 0
        override var currentPositionMs: Int = 0

        override fun start(rate: Float) = Unit

        override fun pause() = Unit

        override fun setRate(rate: Float) = Unit

        override fun seekTo(positionMs: Int) {
            events += "engine-seek:$positionMs"
        }

        override fun release() = Unit
    }
}
