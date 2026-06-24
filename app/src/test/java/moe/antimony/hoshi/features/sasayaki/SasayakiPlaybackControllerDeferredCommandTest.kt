package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SasayakiPlaybackControllerDeferredCommandTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun skipForwardPreparesAudioBeforeSeekingWhenEngineIsNotLoadedYet() {
        val harness = controllerHarness()

        assertTrue(harness.controller.hasAudio)
        assertFalse(harness.preparer.prepared)

        harness.controller.skipForward(10)

        assertTrue(harness.preparer.prepared)
        assertEquals(listOf("seek:13500"), harness.engine.events)
    }

    @Test
    fun nextCuePreparesAudioBeforeSeekingWhenEngineIsNotLoadedYet() {
        val harness = controllerHarness(
            matchData = SasayakiMatchData(
                matches = listOf(
                    SasayakiMatch("next", 10.0, 12.0, "next", 0, 0, 4),
                ),
                unmatched = 0,
            ),
        )

        harness.controller.nextCue()

        assertTrue(harness.preparer.prepared)
        assertEquals(listOf("seek:10000"), harness.engine.events)
    }

    @Test
    fun nextCueFallsBackToFixedSkipWhenMatchDataIsMissing() {
        val harness = controllerHarness()

        harness.controller.nextCue()

        assertTrue(harness.preparer.prepared)
        assertEquals(listOf("seek:18500"), harness.engine.events)
    }

    @Test
    fun updatingMatchDataChangesCueNavigationWithoutRestoringInitialPlaybackPosition() {
        val harness = controllerHarness(initialPosition = 3.5)

        harness.controller.seekTo(21.0)
        assertTrue(harness.preparer.prepared)
        harness.engine.events.clear()

        harness.controller.updateMatchData(
            SasayakiMatchData(
                matches = listOf(
                    SasayakiMatch("new", 24.0, 26.0, "new", 0, 0, 3),
                ),
                unmatched = 0,
            ),
        )
        harness.controller.nextCue()

        assertTrue(harness.preparer.prepared)
        assertEquals(listOf("seek:24000"), harness.engine.events)
    }

    @Test
    fun previousCueFallsBackToFixedSkipWhenMatchDataIsMissing() {
        val harness = controllerHarness(
            initialPosition = 20.0,
        )

        harness.controller.previousCue()

        assertTrue(harness.preparer.prepared)
        assertEquals(listOf("seek:5000"), harness.engine.events)
    }

    @Test
    fun resumeAfterAutoPageHoldDoesNotRedispatchCurrentCue() {
        val displayedCues = mutableListOf<String>()
        val cue = SasayakiMatch("current", 3.0, 5.0, "current", 0, 0, 7)
        val harness = controllerHarness(
            matchData = SasayakiMatchData(matches = listOf(cue), unmatched = 0),
            initialPosition = 3.5,
            onCue = { match, reveal, _ -> displayedCues += "${match.id}:$reveal" },
        )
        harness.controller.togglePlayback()
        displayedCues.clear()
        harness.engine.events.clear()

        assertTrue(harness.controller.pauseForAutoPageHold())
        harness.controller.resumeAfterAutoPageHold()

        assertEquals(emptyList<String>(), displayedCues)
        assertEquals(listOf("pause", "start:1.0"), harness.engine.events)
    }

    @Test
    fun explicitPauseDuringAutoPageHoldPreventsAutomaticResume() {
        val harness = controllerHarness()
        harness.controller.togglePlayback()
        harness.engine.events.clear()

        assertTrue(harness.controller.pauseForAutoPageHold())
        harness.controller.pausePlayback(restoreTemporaryPosition = true)
        harness.controller.resumeAfterAutoPageHold()

        assertEquals(listOf("pause", "pause"), harness.engine.events)
        assertFalse(harness.controller.isPlaying)
    }

    @Test
    fun nextCueDuringAutoPageHoldContinuesPlaybackAfterSeek() {
        val harness = controllerHarness(
            matchData = SasayakiMatchData(
                matches = listOf(
                    SasayakiMatch("next", 10.0, 12.0, "next", 0, 0, 4),
                ),
                unmatched = 0,
            ),
        )
        harness.controller.togglePlayback()
        harness.engine.events.clear()

        assertTrue(harness.controller.pauseForAutoPageHold())
        harness.controller.nextCue()

        assertEquals(listOf("pause", "seek:10000", "start:1.0"), harness.engine.events)
        assertTrue(harness.controller.isPlaying)
    }

    @Test
    fun progressSeekDuringAutoPageHoldContinuesPlaybackAfterSeek() {
        val harness = controllerHarness()
        harness.controller.togglePlayback()
        harness.engine.events.clear()

        assertTrue(harness.controller.pauseForAutoPageHold())
        harness.controller.seekTo(21.0)

        assertEquals(listOf("pause", "seek:21000", "start:1.0"), harness.engine.events)
        assertTrue(harness.controller.isPlaying)
    }

    private fun controllerHarness(
        matchData: SasayakiMatchData? = null,
        initialPosition: Double = 3.5,
        onCue: (SasayakiMatch, Boolean, SasayakiCueRevealSource) -> Unit = { _, _, _ -> },
    ): ControllerHarness {
        val bookRoot = temporaryFolder.newFolder("book")
        val audioFile = bookRoot.resolve("Sasayaki/sasayaki_audio.m4b").also { file ->
            file.parentFile?.mkdirs()
            file.writeText("audio")
        }
        val engine = FakePlaybackEngine()
        val preparer = FakePlaybackPreparer(engine)
        val controller = SasayakiPlaybackController(
            context = FakeContext(temporaryFolder.newFolder("cache")),
            bookRoot = bookRoot,
            playbackRepository = NoOpPlaybackRepository,
            bookTitle = "Book",
            bookCoverFile = null,
            matchData = matchData,
            initialPlayback = SasayakiPlaybackData(
                lastPosition = initialPosition,
                audioFileName = audioFile.name,
            ),
            persistenceScope = CoroutineScope(Dispatchers.Unconfined),
            persistenceDispatcher = Dispatchers.Unconfined,
            getCurrentChapterIndex = { 0 },
            onCue = onCue,
            onClearCue = {},
            playbackPreparer = preparer,
            onPlaybackStartRequested = { onReady -> onReady() },
            restoreAudioOnCreate = false,
            tickScheduler = NoOpTickScheduler,
        )
        return ControllerHarness(controller = controller, preparer = preparer, engine = engine)
    }

    private data class ControllerHarness(
        val controller: SasayakiPlaybackController,
        val preparer: FakePlaybackPreparer,
        val engine: FakePlaybackEngine,
    )

    private class FakeContext(
        private val cacheDirectory: File,
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this

        override fun getCacheDir(): File = cacheDirectory
    }

    private object NoOpPlaybackRepository : SasayakiPlaybackRepository {
        override suspend fun load(): SasayakiPlaybackData? = null

        override suspend fun save(playback: SasayakiPlaybackData) = Unit
    }

    private object NoOpTickScheduler : SasayakiTickScheduler {
        override fun postTick() = Unit

        override fun stopTicking() = Unit
    }

    private class FakePlaybackPreparer(
        private val engine: FakePlaybackEngine,
    ) : SasayakiPlaybackPreparer {
        var prepared = false
            private set

        override fun prepare(
            source: SasayakiPlaybackSource,
            startPositionMs: Int,
            title: String,
            artworkFile: File?,
            callbacks: SasayakiAudioRestoreCallbacks,
        ): SasayakiPreparedPlayback {
            prepared = true
            engine.currentPositionMs = startPositionMs
            engine.callbacks = callbacks
            callbacks.onPrepared(engine.durationMs)
            return SasayakiPreparedPlayback(engine = engine, durationMs = engine.durationMs)
        }
    }

    private class FakePlaybackEngine : SasayakiPlaybackEngine {
        val events = mutableListOf<String>()
        override var durationMs: Int = 30_000
        override var currentPositionMs: Int = 0
        var callbacks: SasayakiAudioRestoreCallbacks? = null

        override fun start(rate: Float) {
            events += "start:$rate"
        }

        override fun pause() {
            events += "pause"
        }

        override fun setRate(rate: Float) {
            events += "rate:$rate"
        }

        override fun seekTo(positionMs: Int) {
            currentPositionMs = positionMs
            events += "seek:$positionMs"
            callbacks?.onSeekComplete()
        }

        override fun release() {
            events += "release"
        }
    }
}
