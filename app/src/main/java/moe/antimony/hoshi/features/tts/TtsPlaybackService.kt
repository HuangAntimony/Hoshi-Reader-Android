package moe.antimony.hoshi.features.tts

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.LocaleList
import androidx.annotation.OptIn
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.antimony.hoshi.features.playback.ReadingMediaArtwork
import moe.antimony.hoshi.features.playback.ReadingMediaArtworkRepository
import moe.antimony.hoshi.MainActivity
import moe.antimony.hoshi.ui.appLanguageContext

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class TtsPlaybackService : MediaSessionService() {
    @Inject lateinit var controller: TtsPlaybackController
    @Inject internal lateinit var artworkRepository: ReadingMediaArtworkRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private var player: ExoPlayer? = null
    private var notificationLocales: LocaleList? = null
    private var artwork: ReadingMediaArtwork? = null
    override fun onCreate() {
        super.onCreate()
        val audio = ExoPlayer.Builder(this).setWakeMode(C.WAKE_MODE_LOCAL).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
        player = audio
        controller.attachPlayer(audio)
        lateinit var transport: TtsSessionPlayer
        val artwork = ReadingMediaArtwork(serviceScope, artworkRepository::load) { transport.refresh() }
        this.artwork = artwork
        transport = TtsSessionPlayer(audio, { controller.state.value }, controller::play, controller::pause,
            controller::previous, controller::next, controller::stop, {
                val state = controller.state.value
                artwork.dataFor(state.bookId, state.bookCoverFile?.absolutePath)
            })
        session = MediaSession.Builder(this, transport).setId("hoshi-tts-playback")
            .setSessionActivity(PendingIntent.getActivity(this, 24,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        addSession(requireNotNull(session))
        serviceScope.launch { controller.state.collect { transport.refresh() } }
        serviceScope.launch {
            controller.state.map { Triple(it.bookId, it.bookCoverFile, it.bookMetadataRevision) }
                .distinctUntilChanged().collect { (bookId, file, revision) ->
                    val source = artworkRepository.source(file)
                    val current = controller.state.value
                    if (current.bookId == bookId && current.bookMetadataRevision == revision) {
                        artwork.update(bookId, source)
                    }
                }
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val languageContext = appLanguageContext()
        val locales = languageContext.resources.configuration.locales
        if (notificationLocales != locales) {
            notificationLocales = locales
            val provider = DefaultMediaNotificationProvider.Builder(languageContext).build()
            val channelInfo = provider.notificationChannelInfo
            val manager = getSystemService(NotificationManager::class.java)
            manager.getNotificationChannel(channelInfo.id)?.let { channel ->
                channel.name = channelInfo.name
                manager.createNotificationChannel(channel)
            }
            setMediaNotificationProvider(provider)
        }
        super.onUpdateNotification(session, startInForegroundRequired || controller.state.value.playing)
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!controller.state.value.playing) { controller.stop(); pauseAllPlayersAndStopSelf() }
        // Desired playback includes synthesis and image holds; a paused decoder is not a user pause.
    }
    override fun onDestroy() {
        serviceScope.cancel()
        artwork?.clear()
        artwork = null
        controller.detachPlayer()
        session?.release(); session = null
        player?.release(); player = null
        super.onDestroy()
    }
}
