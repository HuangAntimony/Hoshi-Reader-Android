package moe.antimony.hoshi.features.tts

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import moe.antimony.hoshi.MainActivity

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class TtsPlaybackService : MediaSessionService() {
    @Inject lateinit var controller: TtsPlaybackController
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private var player: ExoPlayer? = null
    override fun onCreate() {
        super.onCreate()
        val audio = ExoPlayer.Builder(this).setWakeMode(C.WAKE_MODE_LOCAL).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
        player = audio
        controller.attachPlayer(audio)
        val transport = TtsSessionPlayer(audio, { controller.state.value }, controller::play, controller::pause,
            controller::previous, controller::next, controller::stop)
        session = MediaSession.Builder(this, transport).setId("hoshi-tts-playback")
            .setSessionActivity(PendingIntent.getActivity(this, 24,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        addSession(requireNotNull(session))
        serviceScope.launch { controller.state.collect { transport.refresh() } }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired || controller.state.value.playing)
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!controller.state.value.playing) { controller.stop(); pauseAllPlayersAndStopSelf() }
        // Desired playback includes synthesis and image holds; a paused decoder is not a user pause.
    }
    override fun onDestroy() {
        serviceScope.cancel()
        controller.detachPlayer()
        session?.release(); session = null
        player?.release(); player = null
        super.onDestroy()
    }
}
