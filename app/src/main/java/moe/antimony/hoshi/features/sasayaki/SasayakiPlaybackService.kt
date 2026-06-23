package moe.antimony.hoshi.features.sasayaki

import android.app.NotificationManager
import android.content.Intent
import androidx.annotation.OptIn
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
        addSession(runtime.createServiceSession(this))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        runtime.currentSession()

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (runtime.requiresOemRestrictedPlaybackNotificationFallback()) {
            oemRestrictedNotificationRenderer.show(session)
        } else {
            oemRestrictedNotificationRenderer.cancel()
            super.onUpdateNotification(session, startInForegroundRequired)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (sasayakiShouldReleaseInternalControllerOnTaskRemoved(isPlaybackOngoing())) {
            runtime.releasePlaybackServiceConnection()
        }
        super.onTaskRemoved(rootIntent)
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

internal fun sasayakiShouldReleaseInternalControllerOnTaskRemoved(isPlaybackOngoing: Boolean): Boolean =
    !isPlaybackOngoing
