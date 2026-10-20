package moe.antimony.hoshi.features.tts

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

@OptIn(UnstableApi::class)
class TtsSessionPlayerTest {
    @Test fun synthesisRemainsPlayingAndTransportOnlyNavigatesSentences() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val decoder = ExoPlayer.Builder(context).build()
            var state = TtsPlaybackState(bookId = "fixture", sentences = listOf(
                TtsSentence(0, 0, 2, "一。"), TtsSentence(0, 2, 2, "二。"), TtsSentence(0, 4, 2, "三。"),
            ), index = 1, started = true, playing = true, preparing = true)
            var next = 0
            var previous = 0
            var pauses = 0
            val transport = TtsSessionPlayer(decoder, { state }, {}, { pauses++ }, { previous++ }, { next++ }, {})
            try {
                assertTrue(transport.playWhenReady)
                assertEquals(Player.STATE_BUFFERING, transport.playbackState)
                assertEquals(C.TIME_UNSET, transport.duration)
                assertFalse(transport.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
                assertFalse(transport.isCommandAvailable(Player.COMMAND_SEEK_FORWARD))
                assertFalse(transport.isCommandAvailable(Player.COMMAND_SEEK_BACK))
                transport.seekToNextMediaItem()
                transport.seekToPreviousMediaItem()
                transport.pause()
                assertEquals(1, next)
                assertEquals(1, previous)
                assertEquals(1, pauses)
                state = state.copy(playing = false, preparing = false)
                transport.refresh()
                assertFalse(transport.playWhenReady)
            } finally { transport.release(); decoder.release() }
        }
    }
    @Test fun chapterNeighborsExposeSentenceCommandsAtLocalListBoundaries() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val decoder = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
            var state = TtsPlaybackState(bookId = "book", sentences = listOf(TtsSentence(1, 0, 2, "文。")),
                previousChapter = 0, nextChapter = 2, started = true)
            val transport = TtsSessionPlayer(decoder, { state }, {}, {}, {}, {}, {})
            try {
                assertTrue(transport.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
                assertTrue(transport.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
                state = state.copy(previousChapter = null, nextChapter = null)
                transport.refresh()
                assertFalse(transport.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
                assertFalse(transport.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
            } finally { transport.release(); decoder.release() }
        }
    }

    @Test fun reloadingBookKeepsSessionIdleUntilASentenceIsExplicitlyStarted() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val decoder = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
            val sentences = listOf(TtsSentence(0, 0, 2, "一。"))
            var state = TtsPlaybackState(bookId = "first", sentences = sentences,
                started = true, playing = true, preparing = true)
            val transport = TtsSessionPlayer(decoder, { state }, {}, {}, {}, {}, {})
            try {
                assertEquals(Player.STATE_BUFFERING, transport.playbackState)
                assertTrue(transport.isLoading)
                // The service may still be bound after leaving Reader or stopping narration.
                state = TtsPlaybackState()
                transport.refresh()
                assertEquals(Player.STATE_IDLE, transport.playbackState)
                assertFalse(transport.isLoading)
                // Preparing a panel position must not establish playback intent.
                state = TtsPlaybackState(bookId = "second", preparing = true)
                transport.refresh()
                assertEquals(Player.STATE_IDLE, transport.playbackState)
                assertFalse(transport.isLoading)
                assertTrue(transport.currentTimeline.isEmpty)
                assertFalse(transport.playWhenReady)
                state = state.copy(sentences = sentences, preparing = false)
                transport.refresh()
                assertEquals(Player.STATE_IDLE, transport.playbackState)
                assertFalse(transport.isLoading)
                state = state.copy(started = true, playing = true, preparing = true)
                transport.refresh()
                assertEquals(Player.STATE_BUFFERING, transport.playbackState)
                assertTrue(transport.isLoading)
                assertTrue(transport.playWhenReady)
            } finally { transport.release(); decoder.release() }
        }
    }

}
