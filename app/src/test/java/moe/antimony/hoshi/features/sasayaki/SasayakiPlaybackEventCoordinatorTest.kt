package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiPlaybackEventCoordinatorTest {
    @Test
    fun explicitCueSeekRevealsTargetCueBeforeFirstPlayback() {
        val targetCue = SasayakiMatch("target", 12.0, 13.5, "target", 0, 0, 6)
        val playbackState = SasayakiPlaybackStateCoordinator(initialPosition = 0.0)
        playbackState.beginSeek(
            seconds = targetCue.startTime,
            startPlayback = false,
            updateCue = true,
            savePosition = false,
            displayCue = null,
            revealCue = true,
        )
        val actions = mutableListOf<SasayakiCueDisplayAction>()
        val coordinator = SasayakiPlaybackEventCoordinator(
            playbackState = playbackState,
            playbackLifecycle = SasayakiPlaybackLifecycleController(
                playbackState = playbackState,
                tickScheduler = NoopTickScheduler,
            ),
            playbackPersistence = SasayakiPlaybackPersistenceState(
                playbackRepository = NoopPlaybackRepository,
                audioSourceRepository = SasayakiAudioRepository(File("book-root")),
                initialPlayback = null,
                persistenceScope = CoroutineScope(Dispatchers.Unconfined),
            ),
            cueNavigation = SasayakiCueNavigationController(
                SasayakiMatchData(matches = listOf(targetCue), unmatched = 0),
            ),
            cueDisplay = SasayakiCueDisplayCoordinator(),
        )

        coordinator.handleSeekComplete(
            hasAudio = true,
            hasMatch = true,
            delay = 0.0,
            currentChapterIndex = 0,
            autoScroll = true,
            hasPlayedOnce = false,
            startPlayback = {},
            applyCueDisplayAction = { actions += it },
        )

        assertEquals(1, actions.size)
        val display = actions.single()
        assertTrue(display is SasayakiCueDisplayAction.Display)
        display as SasayakiCueDisplayAction.Display
        assertSame(targetCue, display.cue)
        assertTrue(display.reveal)
    }

    private object NoopTickScheduler : SasayakiTickScheduler {
        override fun postTick() = Unit
        override fun stopTicking() = Unit
    }

    private object NoopPlaybackRepository : SasayakiPlaybackRepository {
        override suspend fun load() = null
        override suspend fun save(playback: moe.antimony.hoshi.epub.SasayakiPlaybackData) = Unit
    }
}
