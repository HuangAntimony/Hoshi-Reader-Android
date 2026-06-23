package moe.antimony.hoshi.features.sasayaki

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.PlaybackPendingIntentBuilder
import moe.antimony.hoshi.R
import androidx.media3.session.R as Media3R

internal const val SasayakiPlaybackNotificationId = 1001
internal const val SasayakiPlaybackNotificationChannelId = "hoshi_sasayaki_playback"

internal data class SasayakiOemRestrictedNotificationActionSpec(
    val playerCommand: Int,
    val iconResId: Int,
    val titleResId: Int,
)

@OptIn(UnstableApi::class)
internal fun sasayakiOemRestrictedNotificationActionSpecs(
    isPlaying: Boolean,
): List<SasayakiOemRestrictedNotificationActionSpec> =
    listOf(
        SasayakiOemRestrictedNotificationActionSpec(
            playerCommand = Player.COMMAND_SEEK_BACK,
            iconResId = Media3R.drawable.media3_icon_rewind,
            titleResId = R.string.sasayaki_rewind,
        ),
        SasayakiOemRestrictedNotificationActionSpec(
            playerCommand = Player.COMMAND_PLAY_PAUSE,
            iconResId = if (isPlaying) {
                Media3R.drawable.media3_icon_pause
            } else {
                Media3R.drawable.media3_icon_play
            },
            titleResId = if (isPlaying) R.string.sasayaki_pause else R.string.sasayaki_play,
        ),
        SasayakiOemRestrictedNotificationActionSpec(
            playerCommand = Player.COMMAND_SEEK_FORWARD,
            iconResId = Media3R.drawable.media3_icon_fast_forward,
            titleResId = R.string.sasayaki_fast_forward,
        ),
    )

internal class SasayakiOemRestrictedPlaybackNotificationRenderer(
    private val context: Context,
    private val notificationManager: NotificationManager,
    private val contentIntent: () -> PendingIntent,
) {
    @SuppressLint("NotificationPermission")
    fun show(session: MediaSession) {
        val player = session.player
        if (player.currentMediaItem == null) {
            cancel()
            return
        }
        ensureChannel()
        notificationManager.notify(SasayakiPlaybackNotificationId, buildNotification(session))
    }

    fun cancel() {
        notificationManager.cancel(SasayakiPlaybackNotificationId)
    }

    @OptIn(UnstableApi::class)
    private fun buildNotification(session: MediaSession): Notification {
        val player = session.player
        val metadata = player.mediaMetadata
        val title = metadata.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.sasayaki_title)
        val builder = Notification.Builder(context, SasayakiPlaybackNotificationChannelId)
            .setSmallIcon(R.drawable.ic_stat_hoshi)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.sasayaki_playback))
            .setContentIntent(contentIntent())
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(player.isPlaying)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.platformToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )

        metadata.artworkData
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?.let(builder::setLargeIcon)

        sasayakiOemRestrictedNotificationActionSpecs(player.isPlaying).forEach { spec ->
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, spec.iconResId),
                    context.getString(spec.titleResId),
                    actionIntent(spec),
                ).build(),
            )
        }
        return builder.build()
    }

    @OptIn(UnstableApi::class)
    private fun actionIntent(spec: SasayakiOemRestrictedNotificationActionSpec): PendingIntent =
        PlaybackPendingIntentBuilder(
            context,
            spec.playerCommand,
            SasayakiPlaybackService::class.java,
        )
            .setSessionId(SasayakiPlaybackService.SessionId)
            .build()

    private fun ensureChannel() {
        val channel = NotificationChannel(
            SasayakiPlaybackNotificationChannelId,
            context.getString(R.string.sasayaki_title),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }
}
