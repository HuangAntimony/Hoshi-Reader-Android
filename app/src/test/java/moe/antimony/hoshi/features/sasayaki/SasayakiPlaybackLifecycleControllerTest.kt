package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SasayakiPlaybackLifecycleControllerTest {
    @Test
    fun startAttachesEngineMarksPlaybackAndStartsTickingInOrder() {
        val harness = lifecycleHarness()

        assertFalse(
            harness.controller.start(
                rate = 1.25f,
                beforeStart = { harness.events += "foreground" },
                markPlayedOnce = { harness.events += "mark-played" },
                afterMarkedPlaying = { harness.events += "after-playing:${harness.playbackState.isPlaying}" },
            ),
        )
        assertEquals(emptyList<String>(), harness.events)

        harness.controller.attachEngine(harness.engine)

        assertTrue(
            harness.controller.start(
                rate = 1.25f,
                beforeStart = { harness.events += "foreground" },
                markPlayedOnce = { harness.events += "mark-played" },
                afterMarkedPlaying = { harness.events += "after-playing:${harness.playbackState.isPlaying}" },
            ),
        )

        assertTrue(harness.playbackState.isPlaying)
        assertEquals(
            listOf("foreground", "engine-start:1.25", "mark-played", "after-playing:true", "scheduler-post"),
            harness.events,
        )
    }

    @Test
    fun pauseStopsTickingAndRestoresTemporaryPositionWhenRequested() {
        val harness = lifecycleHarness()
        harness.controller.attachEngine(harness.engine)
        harness.controller.start(rate = 1f, beforeStart = {}, markPlayedOnce = {}, afterMarkedPlaying = {})
        harness.events.clear()

        harness.controller.pause(
            restoreTemporaryPosition = true,
            restoreTemporaryPositionIfNeeded = { harness.events += "restore-temporary" },
        )

        assertFalse(harness.playbackState.isPlaying)
        assertEquals(
            listOf("engine-pause", "scheduler-stop", "restore-temporary"),
            harness.events,
        )
    }

    @Test
    fun externalPlayerStartMarksPlaybackWithoutStartingEngineAgain() {
        val harness = lifecycleHarness()

        harness.controller.syncPlayerPlaybackActive(
            active = true,
            markPlayedOnce = { harness.events += "mark-played" },
            afterMarkedPlaying = { harness.events += "after-playing:${harness.playbackState.isPlaying}" },
            restoreTemporaryPositionIfNeeded = { harness.events += "restore-temporary" },
        )

        assertTrue(harness.playbackState.isPlaying)
        assertEquals(
            listOf("mark-played", "after-playing:true", "scheduler-post"),
            harness.events,
        )
    }

    @Test
    fun externalPlayerPauseStopsTickingAndRestoresTemporaryPosition() {
        val harness = lifecycleHarness()
        harness.controller.syncPlayerPlaybackActive(
            active = true,
            markPlayedOnce = {},
            afterMarkedPlaying = {},
            restoreTemporaryPositionIfNeeded = {},
        )
        harness.events.clear()

        harness.controller.syncPlayerPlaybackActive(
            active = false,
            markPlayedOnce = { harness.events += "mark-played" },
            afterMarkedPlaying = { harness.events += "after-playing" },
            restoreTemporaryPositionIfNeeded = { harness.events += "restore-temporary" },
        )

        assertFalse(harness.playbackState.isPlaying)
        assertEquals(
            listOf("scheduler-stop", "restore-temporary"),
            harness.events,
        )
    }

    @Test
    fun rateChangeUpdatesAttachedEngineEvenWhenPaused() {
        val harness = lifecycleHarness()
        harness.controller.attachEngine(harness.engine)

        harness.controller.setRate(1.5f)

        assertEquals(listOf("engine-rate:1.5"), harness.events)
    }

    @Test
    fun seekDefersTickUntilEngineReportsCompletion() {
        val harness = lifecycleHarness()
        harness.controller.attachEngine(harness.engine)
        harness.controller.start(rate = 1f, beforeStart = {}, markPlayedOnce = {}, afterMarkedPlaying = {})
        harness.events.clear()

        assertTrue(
            harness.controller.beginSeek(
                seconds = 12.5,
                startPlayback = true,
                updateCue = true,
                savePosition = true,
                displayCue = null,
            ),
        )

        assertFalse(harness.playbackState.isPlaying)
        assertTrue(harness.playbackState.hasPendingSeek)
        assertNull(harness.controller.updateTick())
        assertEquals(listOf("scheduler-stop", "engine-pause", "engine-seek:12500"), harness.events)

        val completed = harness.playbackState.completeSeek()
        assertEquals(12.5, completed?.seconds ?: -1.0, 0.0)
    }

    @Test
    fun tickReadsEngineTimeOnlyWhenEngineIsAvailableAndNoSeekIsPending() {
        val harness = lifecycleHarness()

        assertNull(harness.controller.updateTick())

        harness.engine.currentPositionMs = 2_500
        harness.engine.durationMs = 10_000
        harness.controller.attachEngine(harness.engine)
        val tick = harness.controller.updateTick()

        assertEquals(2.5, harness.playbackState.currentTime, 0.0)
        assertEquals(10.0, harness.playbackState.duration, 0.0)
        assertTrue(tick?.shouldSavePosition == true)
    }

    @Test
    fun releaseStopsTickingReleasesEngineAndDetachesIt() {
        val harness = lifecycleHarness()
        harness.controller.attachEngine(harness.engine)

        harness.controller.releaseEngine()

        assertEquals(listOf("scheduler-stop", "engine-release"), harness.events)
        assertNull(harness.controller.updateTick())
    }

    private fun lifecycleHarness(): LifecycleHarness {
        val events = mutableListOf<String>()
        val playbackState = SasayakiPlaybackStateCoordinator(initialPosition = 0.0)
        val engine = FakePlaybackEngine(events)
        val scheduler = FakeTickScheduler(events)
        val controller = SasayakiPlaybackLifecycleController(
            playbackState = playbackState,
            tickScheduler = scheduler,
        )
        return LifecycleHarness(
            playbackState = playbackState,
            engine = engine,
            controller = controller,
            events = events,
        )
    }

    private data class LifecycleHarness(
        val playbackState: SasayakiPlaybackStateCoordinator,
        val engine: FakePlaybackEngine,
        val controller: SasayakiPlaybackLifecycleController,
        val events: MutableList<String>,
    )

    private class FakeTickScheduler(
        private val events: MutableList<String>,
    ) : SasayakiTickScheduler {
        override fun postTick() {
            events += "scheduler-post"
        }

        override fun stopTicking() {
            events += "scheduler-stop"
        }
    }

    private class FakePlaybackEngine(
        private val events: MutableList<String>,
    ) : SasayakiPlaybackEngine {
        override var durationMs: Int = 0
        override var currentPositionMs: Int = 0

        override fun start(rate: Float) {
            events += "engine-start:$rate"
        }

        override fun pause() {
            events += "engine-pause"
        }

        override fun setRate(rate: Float) {
            events += "engine-rate:$rate"
        }

        override fun seekTo(positionMs: Int) {
            events += "engine-seek:$positionMs"
        }

        override fun release() {
            events += "engine-release"
        }
    }
}
