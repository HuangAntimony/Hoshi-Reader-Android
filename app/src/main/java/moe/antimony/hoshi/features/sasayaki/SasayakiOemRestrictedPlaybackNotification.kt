package moe.antimony.hoshi.features.sasayaki

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import moe.antimony.hoshi.R
import androidx.media3.session.R as Media3R

internal const val SasayakiPlaybackNotificationId = 1001
internal const val SasayakiPlaybackNotificationChannelId = "hoshi_sasayaki_playback"
internal const val SasayakiOemRestrictedNotificationPreviousCueAction =
    "moe.antimony.hoshi.sasayaki.oem_restricted_notification.PREVIOUS_CUE"
internal const val SasayakiOemRestrictedNotificationTogglePlaybackAction =
    "moe.antimony.hoshi.sasayaki.oem_restricted_notification.TOGGLE_PLAYBACK"
internal const val SasayakiOemRestrictedNotificationNextCueAction =
    "moe.antimony.hoshi.sasayaki.oem_restricted_notification.NEXT_CUE"

internal data class SasayakiOemRestrictedNotificationActionSpec(
    val action: String,
    val iconResId: Int,
    val titleResId: Int,
    val requestCode: Int,
)

@OptIn(UnstableApi::class)
internal fun sasayakiOemRestrictedNotificationActionSpecs(
    isPlaying: Boolean,
): List<SasayakiOemRestrictedNotificationActionSpec> =
    listOf(
        SasayakiOemRestrictedNotificationActionSpec(
            action = SasayakiOemRestrictedNotificationPreviousCueAction,
            iconResId = Media3R.drawable.media3_icon_previous,
            titleResId = R.string.sasayaki_previous_cue,
            requestCode = 20,
        ),
        SasayakiOemRestrictedNotificationActionSpec(
            action = SasayakiOemRestrictedNotificationTogglePlaybackAction,
            iconResId = if (isPlaying) {
                Media3R.drawable.media3_icon_pause
            } else {
                Media3R.drawable.media3_icon_play
            },
            titleResId = if (isPlaying) R.string.sasayaki_pause else R.string.sasayaki_play,
            requestCode = 21,
        ),
        SasayakiOemRestrictedNotificationActionSpec(
            action = SasayakiOemRestrictedNotificationNextCueAction,
            iconResId = Media3R.drawable.media3_icon_next,
            titleResId = R.string.sasayaki_next_cue,
            requestCode = 22,
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

    private fun actionIntent(spec: SasayakiOemRestrictedNotificationActionSpec): PendingIntent =
        PendingIntent.getService(
            context,
            spec.requestCode,
            Intent(spec.action).setClass(context, SasayakiPlaybackService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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
