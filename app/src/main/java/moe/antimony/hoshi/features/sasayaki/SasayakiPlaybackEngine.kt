package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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

class Media3SasayakiPlaybackEngine private constructor(
    private val player: ExoPlayer,
    private val onSeekComplete: () -> Unit,
) : SasayakiPlaybackEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSeekToken = 0
    private var completedSeekToken = 0

    val media3Player: Player
        get() = player

    override val durationMs: Int
        get() = durationMs(player)

    override val currentPositionMs: Int
        get() = player.currentPosition.toInt()

    override fun start(rate: Float) {
        player.playbackParameters = playbackParameters(rate)
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun setRate(rate: Float) {
        player.playbackParameters = playbackParameters(rate)
    }

    override fun seekTo(positionMs: Int) {
        val targetMs = positionMs.coerceAtLeast(0).toLong()
        val wasAlreadyAtTarget = kotlin.math.abs(player.currentPosition - targetMs) <= NoOpSeekToleranceMs
        val token = ++pendingSeekToken
        player.seekTo(targetMs)
        if (wasAlreadyAtTarget) {
            mainHandler.post { completePendingSeek(token) }
        }
    }

    override fun release() {
        player.release()
    }

    private fun completePendingSeekFromPlayer() {
        completePendingSeek(pendingSeekToken)
    }

    private fun completePendingSeek(token: Int) {
        if (token != pendingSeekToken || token <= completedSeekToken) return
        completedSeekToken = token
        onSeekComplete()
    }

    companion object {
        private const val NoOpSeekToleranceMs = 1L

        fun prepare(
            context: Context,
            source: SasayakiPlaybackSource,
            startPositionMs: Int,
            onPrepared: (Int) -> Unit,
            onCompletion: () -> Unit,
            onSeekComplete: () -> Unit,
            onError: (PlaybackException) -> Unit,
        ): Media3SasayakiPlaybackEngine {
            val player = ExoPlayer.Builder(context.applicationContext).build()
            val engine = Media3SasayakiPlaybackEngine(player, onSeekComplete)
            player.apply {
                setAudioAttributes(audioAttributes(), true)
                setMediaItem(mediaItem(source))
                addListener(
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_READY -> onPrepared(durationMs(player))
                                Player.STATE_ENDED -> onCompletion()
                            }
                        }

                        override fun onPositionDiscontinuity(
                            oldPosition: Player.PositionInfo,
                            newPosition: Player.PositionInfo,
                            reason: Int,
                        ) {
                            if (
                                reason == Player.DISCONTINUITY_REASON_SEEK ||
                                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                            ) {
                                engine.completePendingSeekFromPlayer()
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            onError(error)
                        }
                    },
                )
                prepare()
                seekTo(startPositionMs.coerceAtLeast(0).toLong())
            }
            return engine
        }

        private fun playbackParameters(speed: Float): PlaybackParameters =
            PlaybackParameters(speed, 1f)

        private fun durationMs(player: Player): Int {
            val duration = player.duration
            return duration.takeUnless { it == C.TIME_UNSET }?.toInt() ?: 0
        }

        private fun mediaItem(source: SasayakiPlaybackSource): MediaItem =
            when (source) {
                is SasayakiPlaybackSource.ExternalUri -> MediaItem.fromUri(source.uri)
                is SasayakiPlaybackSource.PrivateFile -> MediaItem.fromUri(Uri.fromFile(source.file))
            }

        private fun audioAttributes(): AudioAttributes =
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .setUsage(C.USAGE_MEDIA)
                .build()
    }
}
