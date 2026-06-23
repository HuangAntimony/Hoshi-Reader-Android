package moe.antimony.hoshi.features.sasayaki

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
    private lateinit var oemRestrictedNotificationRenderer: SasayakiOemRestrictedPlaybackNotificationRenderer

    override fun onCreate() {
        super.onCreate()
        oemRestrictedNotificationRenderer = SasayakiOemRestrictedPlaybackNotificationRenderer(
            context = this,
            notificationManager = getSystemService(NotificationManager::class.java),
            contentIntent = runtime::playbackReturnPendingIntent,
        )
        addSession(runtime.createSession())
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        runtime.currentSession()

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (runtime.requiresOemRestrictedPlaybackNotificationFallback()) {
            oemRestrictedNotificationRenderer.show(session)
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        } else {
            oemRestrictedNotificationRenderer.cancel()
            super.onUpdateNotification(session, startInForegroundRequired)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (runtime.currentSession()?.player?.hasForegroundPlayback() != true) {
            super.onTaskRemoved(rootIntent)
        }
    }

    override fun onDestroy() {
        oemRestrictedNotificationRenderer.cancel()
        runtime.release()
        super.onDestroy()
    }

    companion object {
        internal const val SessionId = "hoshi-sasayaki-playback"
    }
}

@AndroidEntryPoint
internal class SasayakiOemRestrictedPlaybackNotificationReceiver : BroadcastReceiver() {
    @Inject internal lateinit var runtime: SasayakiPlaybackServiceRuntime

    override fun onReceive(context: Context, intent: Intent) {
        if (!runtime.dispatchOemRestrictedNotificationAction(intent.action)) return
        runtime.currentSession()?.let { session ->
            SasayakiOemRestrictedPlaybackNotificationRenderer(
                context = context.applicationContext,
                notificationManager = context.applicationContext.getSystemService(NotificationManager::class.java),
                contentIntent = runtime::playbackReturnPendingIntent,
            ).show(session)
        }
    }
}

private fun Player.hasForegroundPlayback(): Boolean =
    isPlaying ||
        (
            playWhenReady &&
                (
                    playbackState == Player.STATE_READY ||
                        playbackState == Player.STATE_BUFFERING
                )
        )
