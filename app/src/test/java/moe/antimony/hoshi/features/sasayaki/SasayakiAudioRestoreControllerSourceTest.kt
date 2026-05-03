package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiAudioRestoreControllerSourceTest {
    @Test
    fun audioRestoreControllerOwnsSourceResolutionEnginePrepareAndSessionCreation() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiAudioRestoreController.kt").readText()
        val restore = source.substringAfter("fun restore(")

        assertTrue(source.contains("data class SasayakiAudioRestoreCallbacks("))
        assertTrue(source.contains("data class SasayakiAudioRestoreResult("))
        assertTrue(source.contains("private val audioSourceRepository: SasayakiAudioRepository"))
        assertTrue(source.contains("private val playbackLifecycle: SasayakiPlaybackLifecycleController"))
        assertTrue(restore.contains("audioSourceRepository.playbackSource(playback) ?: return null"))
        assertTrue(restore.contains("AndroidSasayakiPlaybackEngine.prepare("))
        assertTrue(restore.contains("context = appContext"))
        assertTrue(restore.contains("source = source"))
        assertTrue(restore.contains("startPositionMs = (playback.lastPosition * 1000.0).toInt()"))
        assertTrue(restore.contains("onCompletion = callbacks.onCompletion"))
        assertTrue(restore.contains("onSeekComplete = callbacks.onSeekComplete"))
        assertTrue(restore.contains("playbackLifecycle.attachEngine(engine)"))
        assertTrue(restore.contains("releaseExistingMediaSession()"))
        assertTrue(restore.contains("AndroidSasayakiMediaSessionHandle("))
        assertTrue(restore.contains("title = bookTitle ?: bookRoot.name"))
        assertTrue(restore.contains("artworkFile = bookCoverFile"))
        assertTrue(restore.contains("durationMs = engine.durationMs"))
        assertTrue(restore.indexOf("AndroidSasayakiPlaybackEngine.prepare(") < restore.indexOf("playbackLifecycle.attachEngine(engine)"))
        assertTrue(restore.indexOf("playbackLifecycle.attachEngine(engine)") < restore.indexOf("releaseExistingMediaSession()"))
        assertTrue(restore.indexOf("releaseExistingMediaSession()") < restore.indexOf("AndroidSasayakiMediaSessionHandle("))
    }

    @Test
    fun audioRestoreControllerDoesNotOwnPlaybackUiOrPersistence() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiAudioRestoreController.kt").readText()

        assertFalse(source.contains("mutableStateOf"))
        assertFalse(source.contains("savePlayback()"))
        assertFalse(source.contains("updateCue("))
        assertFalse(source.contains("hasAudio ="))
        assertFalse(source.contains("errorMessage ="))
        assertFalse(source.contains("playbackRepository"))
    }
}
