package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiAudioRestoreCallbacksCoordinatorSourceTest {
    @Test
    fun restoreCallbacksCoordinatorOwnsMediaSessionCallbackWiring() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiAudioRestoreCallbacksCoordinator.kt").readText()
        val build = source.substringAfter("fun build(")

        assertTrue(source.contains("private val playbackLifecycle: SasayakiPlaybackLifecycleController"))
        assertTrue(source.contains("private val playbackCommands: SasayakiPlaybackCommandCoordinator"))
        assertTrue(build.contains("SasayakiAudioRestoreCallbacks("))
        assertTrue(build.contains("onPrepared = handlePrepared"))
        assertTrue(build.contains("onCompletion = {"))
        assertTrue(build.contains("playbackLifecycle.markCompleted(updateMediaSession = updateMediaSession)"))
        assertTrue(build.contains("onSeekComplete = handleSeekComplete"))
        assertTrue(build.contains("onError = handleError"))
        assertTrue(build.contains("onPlay = startPlayback"))
        assertTrue(build.contains("onPause = pausePlayback"))
        assertTrue(build.contains("onSkipToPrevious = previousCue"))
        assertTrue(build.contains("onSkipToNext = nextCue"))
        assertTrue(build.contains("playbackCommands.mediaSessionSeek("))
        assertTrue(build.contains("positionMs = positionMs"))
        assertTrue(build.contains("isPlaying = isPlaying()"))
        assertFalse(source.contains("mutableStateOf"))
    }
}
