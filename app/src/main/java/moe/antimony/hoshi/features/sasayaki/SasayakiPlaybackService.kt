package moe.antimony.hoshi.features.sasayaki

import android.content.Intent
import android.app.NotificationManager
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class SasayakiPlaybackService : MediaSessionService() {
    @Inject internal lateinit var runtime: SasayakiPlaybackServiceRuntime
    private lateinit var restrictedNotificationRenderer: SasayakiPlaybackNotificationRenderer

    override fun onCreate() {
        super.onCreate()
        restrictedNotificationRenderer = SasayakiPlaybackNotificationRenderer(
            context = this,
            notificationManager = getSystemService(NotificationManager::class.java),
            contentIntent = runtime::playbackReturnPendingIntent,
        )
        addSession(runtime.createSession())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action in SasayakiRestrictedNotificationActions) {
            when (intent?.action) {
                SasayakiNotificationPreviousCueAction -> runtime.previousFromSession()
                SasayakiNotificationTogglePlaybackAction -> runtime.toggleFromNotification()
                SasayakiNotificationNextCueAction -> runtime.nextFromSession()
            }
            runtime.currentSession()?.let { onUpdateNotification(it, startInForegroundRequired = false) }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        runtime.currentSession()

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (runtime.isBackgroundRestricted()) {
            restrictedNotificationRenderer.show(session)
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        } else {
            restrictedNotificationRenderer.cancel()
            super.onUpdateNotification(session, startInForegroundRequired)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (runtime.currentSession()?.player?.hasForegroundPlayback() != true) {
            super.onTaskRemoved(rootIntent)
        }
    }

    override fun onDestroy() {
        restrictedNotificationRenderer.cancel()
        runtime.release()
        super.onDestroy()
    }

    companion object {
        internal const val SessionId = "hoshi-sasayaki-playback"
    }
}

private val SasayakiRestrictedNotificationActions = setOf(
    SasayakiNotificationPreviousCueAction,
    SasayakiNotificationTogglePlaybackAction,
    SasayakiNotificationNextCueAction,
)

private fun Player.hasForegroundPlayback(): Boolean =
    isPlaying ||
        (
            playWhenReady &&
                (
                    playbackState == Player.STATE_READY ||
                        playbackState == Player.STATE_BUFFERING
                )
        )
