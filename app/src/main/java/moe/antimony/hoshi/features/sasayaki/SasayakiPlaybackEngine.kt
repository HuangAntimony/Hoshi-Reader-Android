package moe.antimony.hoshi.features.sasayaki

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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

internal interface Media3SasayakiPlayerHandle {
    val player: Player
    val durationMs: Int
    val currentPositionMs: Int

    fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean)
    fun setMediaItem(mediaItem: MediaItem)
    fun addListener(listener: Player.Listener)
    fun removeListener(listener: Player.Listener)
    fun prepare()
    fun seekTo(positionMs: Long)
    fun setRate(rate: Float)
    fun play()
    fun pause()
    fun stop()
    fun clearMediaItems()
    fun release()
}

internal class ExoPlayerSasayakiPlayerHandle(
    private val exoPlayer: ExoPlayer,
) : Media3SasayakiPlayerHandle {
    override val player: Player
        get() = exoPlayer
    override val durationMs: Int
        get() = media3DurationMs(exoPlayer)
    override val currentPositionMs: Int
        get() = exoPlayer.currentPosition.toInt()

    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) {
        exoPlayer.setAudioAttributes(audioAttributes, handleAudioFocus)
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        exoPlayer.setMediaItem(mediaItem)
    }

    override fun addListener(listener: Player.Listener) {
        exoPlayer.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        exoPlayer.removeListener(listener)
    }

    override fun prepare() {
        exoPlayer.prepare()
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    override fun setRate(rate: Float) {
        exoPlayer.playbackParameters = playbackParameters(rate)
    }

    override fun play() {
        exoPlayer.play()
    }

    override fun pause() {
        exoPlayer.pause()
    }

    override fun stop() {
        exoPlayer.stop()
    }

    override fun clearMediaItems() {
        exoPlayer.clearMediaItems()
    }

    override fun release() {
        exoPlayer.release()
    }
}

class Media3SasayakiPlaybackEngine private constructor(
    private val player: Media3SasayakiPlayerHandle,
    private val onSeekComplete: () -> Unit,
    private val postSeekCompletion: (() -> Unit) -> Unit,
    private val releasePlayer: (Media3SasayakiPlayerHandle) -> Unit,
) : SasayakiPlaybackEngine {
    private var pendingSeekToken = 0
    private var completedSeekToken = 0
    private var listener: Player.Listener? = null

    override val durationMs: Int
        get() = player.durationMs

    override val currentPositionMs: Int
        get() = player.currentPositionMs

    override fun start(rate: Float) {
        player.setRate(rate)
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun setRate(rate: Float) {
        player.setRate(rate)
    }

    override fun seekTo(positionMs: Int) {
        val targetMs = positionMs.coerceAtLeast(0).toLong()
        val wasAlreadyAtTarget =
            kotlin.math.abs(player.currentPositionMs.toLong() - targetMs) <= NoOpSeekToleranceMs
        val token = ++pendingSeekToken
        player.seekTo(targetMs)
        if (wasAlreadyAtTarget) {
            postSeekCompletion { completePendingSeek(token) }
        }
    }

    override fun release() {
        listener?.let(player::removeListener)
        listener = null
        releasePlayer(player)
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

        internal fun prepare(
            player: Media3SasayakiPlayerHandle,
            mediaItem: MediaItem,
            startPositionMs: Int,
            onPrepared: (Int) -> Unit,
            onCompletion: () -> Unit,
            onSeekComplete: () -> Unit,
            onError: (PlaybackException) -> Unit,
            postSeekCompletion: (() -> Unit) -> Unit,
            releasePlayer: (Media3SasayakiPlayerHandle) -> Unit,
        ): Media3SasayakiPlaybackEngine {
            val engine = Media3SasayakiPlaybackEngine(
                player = player,
                onSeekComplete = onSeekComplete,
                postSeekCompletion = postSeekCompletion,
                releasePlayer = releasePlayer,
            )
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> onPrepared(player.durationMs)
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
            }
            engine.listener = listener
            player.apply {
                setAudioAttributes(sasayakiMedia3AudioAttributes(), true)
                setMediaItem(mediaItem)
                addListener(listener)
                prepare()
                seekTo(startPositionMs.coerceAtLeast(0).toLong())
            }
            return engine
        }
    }
}

private fun playbackParameters(speed: Float): PlaybackParameters =
    PlaybackParameters(speed, 1f)

private fun media3DurationMs(player: Player): Int {
    val duration = player.duration
    return duration.takeUnless { it == C.TIME_UNSET }?.toInt() ?: 0
}

internal fun sasayakiMedia3AudioAttributes(): AudioAttributes =
    AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
        .setUsage(C.USAGE_MEDIA)
        .build()

internal fun sasayakiMediaMetadata(
    title: String?,
    artworkFile: File?,
): MediaMetadata {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
    if (artworkFile != null && artworkFile.isFile) {
        metadata.setArtworkData(artworkFile.readBytes(), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
    }
    return metadata.build()
}

internal fun sasayakiMediaItem(
    source: SasayakiPlaybackSource,
    title: String?,
    artworkFile: File?,
): MediaItem =
    MediaItem.Builder()
        .setUri(
            when (source) {
                is SasayakiPlaybackSource.ExternalUri -> source.uri
                is SasayakiPlaybackSource.PrivateFile -> Uri.fromFile(source.file)
            },
        )
        .setMediaMetadata(
            sasayakiMediaMetadata(title = title, artworkFile = artworkFile),
        )
        .build()
