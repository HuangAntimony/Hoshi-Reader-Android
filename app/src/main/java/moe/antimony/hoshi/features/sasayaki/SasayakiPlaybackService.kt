package moe.antimony.hoshi.features.sasayaki

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
    private lateinit var notificationProvider: SasayakiPlaybackNotificationProvider

    override fun onCreate() {
        super.onCreate()
        notificationProvider = SasayakiPlaybackNotificationProvider(
            context = this,
            contentIntent = runtime::playbackReturnPendingIntent,
            isPlaybackOngoing = runtime::isForegroundPlaybackRequested,
        )
        setMediaNotificationProvider(notificationProvider)
        addSession(runtime.createServiceSession(this))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        runtime.currentSession()

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(
            session,
            startInForegroundRequired || runtime.shouldRunPlaybackServiceInForeground(session.player),
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (sasayakiShouldStopPlaybackOnTaskRemoved(runtime.isForegroundPlaybackRequested())) {
            runtime.stopPlayback()
            pauseAllPlayersAndStopSelf()
        } else {
            super.onTaskRemoved(rootIntent)
        }
    }

    override fun onDestroy() {
        runtime.release()
        super.onDestroy()
    }

    companion object {
        internal const val SessionId = "hoshi-sasayaki-playback"
    }
}

internal fun sasayakiShouldStopPlaybackOnTaskRemoved(
    isForegroundPlaybackRequested: Boolean,
): Boolean =
    !isForegroundPlaybackRequested

internal fun sasayakiShouldRunPlaybackServiceInForeground(player: Player): Boolean =
    sasayakiShouldRunPlaybackServiceInForeground(
        foregroundPlaybackRequested = false,
        playWhenReady = player.playWhenReady,
        playbackState = player.playbackState,
    )

internal fun sasayakiShouldRunPlaybackServiceInForeground(
    foregroundPlaybackRequested: Boolean,
    playWhenReady: Boolean,
    playbackState: Int,
): Boolean =
    foregroundPlaybackRequested ||
        playWhenReady &&
        playbackState != Player.STATE_IDLE &&
        playbackState != Player.STATE_ENDED
