package moe.antimony.hoshi.features.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource

@OptIn(UnstableApi::class)
class WordAudioPlayer private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: ExoPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    fun play(url: String, mode: AudioPlaybackMode) {
        stop()
        if (!requestFocus(mode)) return
        val nextPlayer = ExoPlayer.Builder(appContext).build().apply {
            setAudioAttributes(audioAttributes(), true)
            addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            stop()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        stop()
                    }
                },
            )
        }
        runCatching {
            val localFile = LocalAudioResolver.parseAudioUrl(url)
            if (localFile != null) {
                val data = LocalAudioRepository.fromContext(appContext).loadAudio(localFile)
                    ?: error("Local audio not found.")
                val mediaSource = ProgressiveMediaSource.Factory {
                    ByteArrayDataSource(data)
                }.createMediaSource(MediaItem.fromUri("memory://hoshi-local-audio"))
                nextPlayer.setMediaSource(mediaSource)
            } else {
                nextPlayer.setMediaItem(MediaItem.fromUri(url))
            }
            nextPlayer.prepare()
            nextPlayer.playWhenReady = true
            player = nextPlayer
        }.onFailure {
            nextPlayer.release()
            abandonFocus()
        }
    }

    fun stop() {
        player?.release()
        player = null
        abandonFocus()
    }

    private fun requestFocus(mode: AudioPlaybackMode): Boolean {
        if (mode == AudioPlaybackMode.Mix) return true
        val gain = when (mode) {
            AudioPlaybackMode.Interrupt -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioPlaybackMode.Duck -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioPlaybackMode.Mix -> AudioManager.AUDIOFOCUS_NONE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(platformAudioAttributes())
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, gain) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        val request = focusRequest
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
            audioManager.abandonAudioFocusRequest(request)
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun audioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

    private fun platformAudioAttributes(): android.media.AudioAttributes =
        android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    companion object {
        @Volatile
        private var instance: WordAudioPlayer? = null

        fun get(context: Context): WordAudioPlayer =
            instance ?: synchronized(this) {
                instance ?: WordAudioPlayer(context).also { instance = it }
            }
    }
}
