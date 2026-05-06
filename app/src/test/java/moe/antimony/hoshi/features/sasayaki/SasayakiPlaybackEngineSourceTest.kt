package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiPlaybackEngineSourceTest {
    @Test
    fun androidPlaybackEngineUsesMedia3ExoPlayerOperations() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackEngine.kt").readText()
        val prepare = source.substringAfter("fun prepare(")
        val start = source.substringAfter("override fun start(rate: Float)")
            .substringBefore("override fun pause()")
        val setRate = source.substringAfter("override fun setRate(rate: Float)")
            .substringBefore("override fun seekTo(")

        assertFalse(source.contains("import android.media.MediaPlayer"))
        assertFalse(source.contains("import android.media.PlaybackParams"))
        assertTrue(source.contains("import androidx.media3.common.AudioAttributes"))
        assertTrue(source.contains("import androidx.media3.common.C"))
        assertTrue(source.contains("import androidx.media3.common.MediaItem"))
        assertTrue(source.contains("import androidx.media3.common.PlaybackException"))
        assertTrue(source.contains("import androidx.media3.common.PlaybackParameters"))
        assertTrue(source.contains("import androidx.media3.common.Player"))
        assertTrue(source.contains("import androidx.media3.exoplayer.ExoPlayer"))
        assertTrue(source.contains("sealed interface SasayakiPlaybackSource"))
        assertTrue(source.contains("data class ExternalUri(val uri: Uri)"))
        assertTrue(source.contains("data class PrivateFile(val file: File)"))
        assertTrue(source.contains("interface SasayakiPlaybackEngine"))
        assertTrue(source.contains("val durationMs: Int"))
        assertTrue(source.contains("val currentPositionMs: Int"))
        assertTrue(prepare.contains("ExoPlayer.Builder(context.applicationContext).build()"))
        assertTrue(prepare.contains("setAudioAttributes(audioAttributes(), true)"))
        assertTrue(prepare.contains("setMediaItem(mediaItem(source))"))
        assertTrue(prepare.contains("addListener("))
        assertTrue(prepare.contains("Player.STATE_READY"))
        assertTrue(prepare.contains("Player.STATE_ENDED"))
        assertTrue(prepare.contains("onPrepared(durationMs(player))"))
        assertTrue(prepare.contains("onCompletion()"))
        assertTrue(prepare.contains("override fun onPlayerError(error: PlaybackException)"))
        assertTrue(prepare.contains("onError(error)"))
        assertTrue(prepare.contains("Player.DISCONTINUITY_REASON_SEEK"))
        assertTrue(prepare.contains("onSeekComplete()"))
        assertTrue(prepare.contains("prepare()"))
        assertTrue(prepare.contains("seekTo(startPositionMs.coerceAtLeast(0).toLong())"))
        assertTrue(start.contains("player.playbackParameters = playbackParameters(rate)"))
        assertTrue(start.contains("player.play()"))
        assertTrue(source.contains("override fun pause()"))
        assertTrue(source.contains("player.pause()"))
        assertTrue(setRate.contains("player.playbackParameters = playbackParameters(rate)"))
        assertTrue(source.contains("override fun seekTo(positionMs: Int)"))
        assertTrue(source.contains("player.seekTo(positionMs.coerceAtLeast(0).toLong())"))
        assertTrue(source.contains("override fun release()"))
        assertTrue(source.contains("player.release()"))
        assertTrue(source.contains("PlaybackParameters(speed, 1f)"))
        assertTrue(source.contains("C.AUDIO_CONTENT_TYPE_SPEECH"))
        assertTrue(source.contains("C.USAGE_MEDIA"))
        assertTrue(source.contains("duration.takeUnless { it == C.TIME_UNSET }?.toInt() ?: 0"))
        assertTrue(source.contains("MediaItem.fromUri(source.uri)"))
        assertTrue(source.contains("MediaItem.fromUri(Uri.fromFile(source.file))"))
    }
}
