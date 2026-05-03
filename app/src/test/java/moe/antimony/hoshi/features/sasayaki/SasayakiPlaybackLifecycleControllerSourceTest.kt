package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiPlaybackLifecycleControllerSourceTest {
    @Test
    fun lifecycleControllerOwnsPlaybackEngineAndTickScheduling() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackLifecycleController.kt").readText()

        assertTrue(source.contains("import android.os.Handler"))
        assertTrue(source.contains("private var engine: SasayakiPlaybackEngine? = null"))
        assertTrue(source.contains("fun attachEngine(engine: SasayakiPlaybackEngine)"))
        assertTrue(source.contains("this.engine = engine"))
        assertTrue(source.contains("handler.removeCallbacks(tickRunnable)"))
        assertTrue(source.contains("handler.post(tickRunnable)"))
        assertTrue(source.contains("engine?.release()"))
        assertTrue(source.contains("engine = null"))
    }

    @Test
    fun startPauseAndCompletionPreserveExistingOrder() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackLifecycleController.kt").readText()
        val setRate = source.substringAfter("fun setRateIfPlaying(rate: Float)")
            .substringBefore("fun start(")
        val start = source.substringAfter("fun start(")
            .substringBefore("fun pause(")
        val pause = source.substringAfter("fun pause(")
            .substringBefore("fun beginSeek(")
        val completion = source.substringAfter("fun markCompleted(")
            .substringBefore("fun updateTick(")

        assertTrue(setRate.contains("if (playbackState.isPlaying)"))
        assertTrue(setRate.contains("engine?.setRate(rate)"))
        assertTrue(start.contains("val engine = engine ?: return false"))
        assertTrue(start.contains("engine.start(rate)"))
        assertTrue(start.contains("markPlayedOnce()"))
        assertTrue(start.contains("playbackState.markPlaying()"))
        assertTrue(start.contains("afterMarkedPlaying()"))
        assertTrue(start.contains("restartTicking()"))
        assertTrue(start.indexOf("engine.start(rate)") < start.indexOf("markPlayedOnce()"))
        assertTrue(start.indexOf("markPlayedOnce()") < start.indexOf("playbackState.markPlaying()"))
        assertTrue(start.indexOf("playbackState.markPlaying()") < start.indexOf("afterMarkedPlaying()"))
        assertTrue(start.indexOf("afterMarkedPlaying()") < start.indexOf("restartTicking()"))
        assertTrue(pause.contains("engine?.pause()"))
        assertTrue(pause.contains("playbackState.markPaused()"))
        assertTrue(pause.contains("stopTicking()"))
        assertTrue(pause.contains("updateMediaSession()"))
        assertTrue(pause.contains("if (restoreTemporaryPosition)"))
        assertTrue(pause.contains("restoreTemporaryPositionIfNeeded()"))
        assertTrue(pause.indexOf("engine?.pause()") < pause.indexOf("playbackState.markPaused()"))
        assertTrue(pause.indexOf("playbackState.markPaused()") < pause.indexOf("stopTicking()"))
        assertTrue(pause.indexOf("stopTicking()") < pause.indexOf("updateMediaSession()"))
        assertTrue(completion.contains("playbackState.markCompleted()"))
        assertTrue(completion.contains("stopTicking()"))
        assertTrue(completion.contains("updateMediaSession()"))
        assertTrue(completion.indexOf("playbackState.markCompleted()") < completion.indexOf("stopTicking()"))
        assertTrue(completion.indexOf("stopTicking()") < completion.indexOf("updateMediaSession()"))
    }

    @Test
    fun seekAndTickStayDeferredThroughPlaybackState() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackLifecycleController.kt").readText()
        val seek = source.substringAfter("fun beginSeek(")
            .substringBefore("fun markCompleted(")
        val tick = source.substringAfter("fun updateTick(")
            .substringBefore("fun seekTo(")

        assertTrue(seek.contains("val engine = engine ?: return false"))
        assertTrue(seek.contains("playbackState.beginSeek("))
        assertTrue(seek.contains("stopTicking()"))
        assertTrue(seek.contains("if (playbackState.isPlaying)"))
        assertTrue(seek.contains("engine.pause()"))
        assertTrue(seek.contains("playbackState.markPaused()"))
        assertTrue(seek.contains("engine.seekTo((seconds * 1000.0).toInt())"))
        assertFalse(seek.contains("completeSeek()"))
        assertTrue(seek.indexOf("playbackState.beginSeek(") < seek.indexOf("stopTicking()"))
        assertTrue(seek.indexOf("stopTicking()") < seek.indexOf("engine.seekTo((seconds * 1000.0).toInt())"))
        assertTrue(tick.contains("if (playbackState.hasPendingSeek) return null"))
        assertTrue(tick.contains("val engine = engine ?: return null"))
        assertTrue(tick.contains("playbackState.updateTick("))
        assertTrue(tick.contains("currentPositionMs = engine.currentPositionMs"))
        assertTrue(tick.contains("durationMs = engine.durationMs"))
    }
}
