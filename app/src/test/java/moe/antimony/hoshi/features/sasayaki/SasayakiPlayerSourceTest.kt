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
}
