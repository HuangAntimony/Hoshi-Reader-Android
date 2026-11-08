package moe.antimony.hoshi.features.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.di.ApplicationScope
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
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
@Singleton
class WordAudioPlayer @Inject constructor(
    @ApplicationContext context: Context,
    private val localAudioRepository: LocalAudioRepository,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }
    private var player: ExoPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var loadJob: Job? = null
    private var generation = 0L

    fun play(url: String, mode: AudioPlaybackMode) {
        stop()
        val request = generation
        loadJob = applicationScope.launch {
            try {
                val localFile = LocalAudioResolver.parseAudioUrl(url)
                val data = localFile?.let { localAudioRepository.loadAudio(it) ?: error("Local audio not found.") }
                if (request != generation || !requestFocus(mode)) return@launch
                val nextPlayer = ExoPlayer.Builder(appContext).build()
                player = nextPlayer
                nextPlayer.apply {
                    setAudioAttributes(audioAttributes(), false)
                    addListener(
                        object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_ENDED) {
                                    if (player === nextPlayer) this@WordAudioPlayer.stop()
                                }
                            }

                            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                                    if (player === nextPlayer) this@WordAudioPlayer.stop()
                                }
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                if (player === nextPlayer) this@WordAudioPlayer.stop()
                            }
                        },
                    )
                }
                if (localFile != null) {
                    val mediaItem = MediaItem.Builder()
                        .setUri("memory://hoshi-local-audio/${localFile.file.urlEncode()}")
                        .setMimeType(LocalAudioResolver.mimeType(localFile.file))
                        .build()
                    val mediaSource = ProgressiveMediaSource.Factory {
                        ByteArrayDataSource(requireNotNull(data))
                    }.createMediaSource(mediaItem)
                    nextPlayer.setMediaSource(mediaSource)
                } else {
                    nextPlayer.setMediaItem(MediaItem.fromUri(url))
                }
                nextPlayer.prepare()
                nextPlayer.playWhenReady = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.audio_local_database_read_failed), error)
                if (request == generation) stop()
            }
        }
    }

    fun stop() {
        generation++
        loadJob?.cancel()
        loadJob = null
        player?.release()
        player = null
        abandonFocus()
    }

    private fun requestFocus(mode: AudioPlaybackMode): Boolean {
        if (mode == AudioPlaybackMode.Mix) return true
        // Ducking is a best-effort system focus request; apps that opt out of automatic
        // ducking may keep their own volume or pause instead.
        val gain = when (mode) {
            AudioPlaybackMode.Interrupt -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioPlaybackMode.Duck -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioPlaybackMode.Mix -> AudioManager.AUDIOFOCUS_NONE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(platformAudioAttributes())
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (granted) focusRequest = request
            granted
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, gain) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: return
            audioManager.abandonAudioFocusRequest(request)
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
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
        fun get(context: Context): WordAudioPlayer =
            EntryPointAccessors.fromApplication(context.applicationContext, WordAudioPlayerEntryPoint::class.java).wordAudioPlayer()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WordAudioPlayerEntryPoint {
    fun wordAudioPlayer(): WordAudioPlayer
}
