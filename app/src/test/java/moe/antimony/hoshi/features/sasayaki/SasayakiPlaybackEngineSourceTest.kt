package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiPlaybackEngineSourceTest {
    @Test
    fun androidPlaybackEngineWrapsFrameworkMediaPlayerOperations() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackEngine.kt").readText()
        val prepare = source.substringAfter("fun prepare(")
        val start = source.substringAfter("override fun start(rate: Float)")
            .substringBefore("override fun pause()")
        val setRate = source.substringAfter("override fun setRate(rate: Float)")
            .substringBefore("override fun seekTo(")

        assertTrue(source.contains("import android.media.AudioAttributes"))
        assertTrue(source.contains("import android.media.MediaPlayer"))
        assertTrue(source.contains("import android.media.PlaybackParams"))
        assertTrue(source.contains("sealed interface SasayakiPlaybackSource"))
        assertTrue(source.contains("data class ExternalUri(val uri: Uri)"))
        assertTrue(source.contains("data class PrivateFile(val file: File)"))
        assertTrue(source.contains("interface SasayakiPlaybackEngine"))
        assertTrue(source.contains("val durationMs: Int"))
        assertTrue(source.contains("val currentPositionMs: Int"))
        assertTrue(prepare.contains("MediaPlayer().apply"))
        assertTrue(prepare.contains("setAudioAttributes(audioAttributes())"))
        assertTrue(prepare.contains("setDataSource(context.applicationContext, source.uri)"))
        assertTrue(prepare.contains("setDataSource(source.file.absolutePath)"))
        assertTrue(prepare.contains("setOnCompletionListener { onCompletion() }"))
        assertTrue(prepare.contains("setOnSeekCompleteListener { onSeekComplete() }"))
        assertTrue(prepare.contains("prepare()"))
        assertTrue(prepare.contains("seekTo(startPositionMs.coerceAtLeast(0))"))
        assertTrue(start.contains("player.playbackParams = playbackParams(rate)"))
        assertTrue(start.contains("player.start()"))
        assertTrue(source.contains("override fun pause()"))
        assertTrue(source.contains("player.pause()"))
        assertTrue(setRate.contains("player.playbackParams = playbackParams(rate)"))
        assertTrue(source.contains("override fun seekTo(positionMs: Int)"))
        assertTrue(source.contains("player.seekTo(positionMs.coerceAtLeast(0))"))
        assertTrue(source.contains("override fun release()"))
        assertTrue(source.contains("player.release()"))
        assertTrue(source.contains("PlaybackParams().setSpeed(speed).setPitch(1f)"))
        assertTrue(source.contains("AudioAttributes.CONTENT_TYPE_SPEECH"))
        assertTrue(source.contains("AudioAttributes.USAGE_MEDIA"))
    }
}
