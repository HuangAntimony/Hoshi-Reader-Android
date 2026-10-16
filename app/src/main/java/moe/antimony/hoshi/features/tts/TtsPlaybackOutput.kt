package moe.antimony.hoshi.features.tts

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.io.File
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface TtsPlaybackOutput {
    var onEnded: () -> Unit
    var onPaused: () -> Unit
    var onError: (Throwable) -> Unit
    suspend fun connect()
    fun prepare(files: List<File>, title: String, sentence: String)
    fun setPlaybackRate(rate: Float)
    fun play()
    fun pause()
    fun clear()
    fun disconnect()
}

@OptIn(UnstableApi::class)
internal class AndroidTtsPlaybackOutput(private val context: Context) : TtsPlaybackOutput {
    override var onEnded: () -> Unit = {}
    override var onPaused: () -> Unit = {}
    override var onError: (Throwable) -> Unit = {}
    private var player: ExoPlayer? = null
    private var connection: MediaController? = null
    private var desiredRate = 1f

    fun attach(next: ExoPlayer) {
        player = next
        next.playbackParameters = PlaybackParameters(desiredRate, 1f)
        next.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                // Inspect the current playlist after batched events, not an old state callback
                // queued before a seek/clear. Paused completion still belongs to the controller.
                if (this@AndroidTtsPlaybackOutput.player === player &&
                    events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_ENDED) onEnded()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) onPaused()
            }
            override fun onPlayerError(error: PlaybackException) = onError(error)
        })
    }
    fun detach() { player = null; connection?.release(); connection = null }
    override suspend fun connect() {
        if (player != null && connection != null) return
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, TtsPlaybackService::class.java))).buildAsync()
        connection = suspendCancellableCoroutine { continuation ->
            future.addListener({
                try { val result = future.get(); if (continuation.isActive) continuation.resume(result) else result.release() }
                catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }, ContextCompat.getMainExecutor(context))
            continuation.invokeOnCancellation { MediaController.releaseFuture(future) }
        }
    }
    override fun prepare(files: List<File>, title: String, sentence: String) {
        requireNotNull(player).apply {
            setMediaItems(files.map { file -> MediaItem.Builder().setUri(file.toURI().toString()).setMediaId(file.name)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(sentence).build()).build() })
            prepare()
        }
    }
    override fun setPlaybackRate(rate: Float) {
        desiredRate = normalizeTtsPlaybackRate(rate)
        player?.playbackParameters = PlaybackParameters(desiredRate, 1f)
    }
    override fun play() { requireNotNull(player).play() }
    override fun pause() { player?.pause() }
    override fun clear() { player?.stop(); player?.clearMediaItems() }
    override fun disconnect() { connection?.release(); connection = null }
}
