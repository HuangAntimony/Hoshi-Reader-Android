package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiAudioRestoreWorkflowCoordinatorSourceTest {
    @Test
    fun restoreWorkflowPreservesRestoreFailureAndSuccessSequencing() {
        val source = File(
            "src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiAudioRestoreWorkflowCoordinator.kt",
        ).readText()
        val restore = source.substringAfter("fun restore(")

        assertTrue(source.contains("class SasayakiAudioRestoreWorkflowCoordinator("))
        assertTrue(source.contains("private val audioRestore: SasayakiAudioRestoreController"))
        assertTrue(source.contains("private val audioRestoreCallbacks: SasayakiAudioRestoreCallbacksCoordinator"))
        assertTrue(source.contains("private val audioRestoreResult: SasayakiAudioRestoreResultCoordinator"))
        assertFalse(source.contains("mutableStateOf"))

        assertTrue(restore.contains("playback: SasayakiPlaybackData"))
        assertTrue(restore.contains("currentTime: () -> Double"))
        assertTrue(restore.contains("releaseExistingMediaSession: () -> Unit"))
        assertTrue(restore.contains("updateMediaSession: () -> Unit"))
        assertTrue(restore.contains("handleSeekComplete: () -> Unit"))
        assertTrue(restore.contains("startPlayback: () -> Unit"))
        assertTrue(restore.contains("pausePlayback: () -> Unit"))
        assertTrue(restore.contains("previousCue: () -> Unit"))
        assertTrue(restore.contains("nextCue: () -> Unit"))
        assertTrue(restore.contains("isPlaying: () -> Boolean"))
        assertTrue(restore.contains("updateCue: (Double) -> Unit"))
        assertTrue(restore.contains("val result = runCatching {"))
        assertTrue(restore.contains("audioRestore.restore("))
        assertTrue(restore.contains("playback = playback"))
        assertTrue(restore.contains("releaseExistingMediaSession = releaseExistingMediaSession"))
        assertTrue(restore.contains("callbacks = audioRestoreCallbacks.build("))
        assertTrue(restore.contains("handlePrepared = { durationMs ->"))
        assertTrue(restore.contains("currentTime = currentTime()"))
        assertTrue(restore.contains("updateMediaSession = updateMediaSession"))
        assertTrue(restore.contains("handleSeekComplete = handleSeekComplete"))
        assertTrue(restore.contains("startPlayback = startPlayback"))
        assertTrue(restore.contains("pausePlayback = pausePlayback"))
        assertTrue(restore.contains("previousCue = previousCue"))
        assertTrue(restore.contains("nextCue = nextCue"))
        assertTrue(restore.contains("isPlaying = isPlaying"))
        assertTrue(restore.contains("}.onFailure(audioRestoreResult::handleFailure).getOrNull() ?: return"))
        assertTrue(restore.contains("audioRestoreResult.handleSuccess("))
        assertTrue(restore.contains("result = result"))
        assertTrue(restore.contains("currentTime = currentTime()"))
        assertTrue(restore.contains("updateCue = updateCue"))
        assertTrue(restore.contains("updateMediaSession = updateMediaSession"))
        assertTrue(restore.indexOf("audioRestore.restore(") < restore.indexOf("onFailure(audioRestoreResult::handleFailure)"))
        assertTrue(restore.indexOf("onFailure(audioRestoreResult::handleFailure)") < restore.indexOf("audioRestoreResult.handleSuccess("))
    }
}
