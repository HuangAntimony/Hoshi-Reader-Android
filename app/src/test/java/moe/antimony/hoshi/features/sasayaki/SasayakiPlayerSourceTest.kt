package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiPlayerSourceTest {
    @Test
    fun restoredAudioStaysPausedUntilExplicitPlaybackLikeIos() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val restoreAudio = source.substringAfter("private fun restoreAudio()")
            .substringBefore("private fun tick()")
        val setRate = source.substringAfter("fun setRate(value: Float)")
            .substringBefore("fun importAudio(")
        val startPlayback = source.substringAfter("private fun startPlayback()")
            .substringBefore("private fun seek(")

        assertFalse(restoreAudio.contains("playbackParams = playbackParams(rate)"))
        assertTrue(setRate.contains("if (isPlaying)"))
        assertTrue(setRate.contains("mediaPlayer?.playbackParams = playbackParams(value)"))
        assertTrue(startPlayback.contains("player.playbackParams = playbackParams(rate)"))
        assertTrue(startPlayback.contains("player.start()"))
        assertTrue(startPlayback.contains("hasPlayedOnce = true"))
    }

    @Test
    fun popupCuePlaybackMatchesIosStopSemantics() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val playCue = source.substringAfter("fun playCue(cue: SasayakiMatch, stop: Boolean)")
            .substringBefore("fun release()")
        val tick = source.substringAfter("private fun tick()")
            .substringBefore("private fun updateCue(")

        assertTrue(source.contains("private var stopPlaybackTime: Double? = null"))
        assertTrue(source.contains("fun findCue(chapterIndex: Int, offset: Int): SasayakiMatch?"))
        assertTrue(playCue.contains("stopPlaybackTime = null"))
        assertTrue(playCue.contains("if (isPlaying) pausePlayback()"))
        assertTrue(playCue.contains("cue.startTime + delay"))
        assertTrue(playCue.contains("stopPlaybackTime = if (stop) cue.endTime + delay else null"))
        assertTrue(tick.contains("stopPlaybackTime?.let"))
        assertTrue(tick.contains("pausePlayback()"))
    }
}
