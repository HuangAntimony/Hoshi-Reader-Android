package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import java.io.File

sealed interface SasayakiPlaybackSource {
    data class ExternalUri(val uri: Uri) : SasayakiPlaybackSource
    data class PrivateFile(val file: File) : SasayakiPlaybackSource
}

interface SasayakiPlaybackEngine {
    val durationMs: Int
    val currentPositionMs: Int

    fun start(rate: Float)
    fun pause()
    fun setRate(rate: Float)
    fun seekTo(positionMs: Int)
    fun release()
}

class AndroidSasayakiPlaybackEngine private constructor(
    private val player: MediaPlayer,
) : SasayakiPlaybackEngine {
    override val durationMs: Int
        get() = player.duration

    override val currentPositionMs: Int
        get() = player.currentPosition

    override fun start(rate: Float) {
        player.playbackParams = playbackParams(rate)
        player.start()
    }

    override fun pause() {
        player.pause()
    }

    override fun setRate(rate: Float) {
        player.playbackParams = playbackParams(rate)
    }

    override fun seekTo(positionMs: Int) {
        player.seekTo(positionMs.coerceAtLeast(0))
    }

    override fun release() {
        player.release()
    }

    companion object {
        fun prepare(
            context: Context,
            source: SasayakiPlaybackSource,
            startPositionMs: Int,
            onCompletion: () -> Unit,
            onSeekComplete: () -> Unit,
        ): SasayakiPlaybackEngine {
            val player = MediaPlayer().apply {
                setAudioAttributes(audioAttributes())
                when (source) {
                    is SasayakiPlaybackSource.ExternalUri -> setDataSource(context.applicationContext, source.uri)
                    is SasayakiPlaybackSource.PrivateFile -> setDataSource(source.file.absolutePath)
                }
                setOnCompletionListener { onCompletion() }
                setOnSeekCompleteListener { onSeekComplete() }
                prepare()
                seekTo(startPositionMs.coerceAtLeast(0))
            }
            return AndroidSasayakiPlaybackEngine(player)
        }

        private fun playbackParams(speed: Float): PlaybackParams =
            PlaybackParams().setSpeed(speed).setPitch(1f)

        private fun audioAttributes(): AudioAttributes =
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
    }
}
