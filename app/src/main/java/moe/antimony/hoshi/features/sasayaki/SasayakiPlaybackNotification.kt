package moe.antimony.hoshi.features.sasayaki

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.PlaybackPendingIntentBuilder
import com.google.common.collect.ImmutableList
import moe.antimony.hoshi.R
import androidx.media3.session.R as Media3R

internal const val SasayakiPlaybackNotificationId = 1001
internal const val SasayakiPlaybackNotificationChannelId = "hoshi_sasayaki_playback"

internal data class SasayakiPlaybackNotificationActionSpec(
    val playerCommand: Int,
    val iconResId: Int,
    val titleResId: Int,
)

@OptIn(UnstableApi::class)
internal fun sasayakiPlaybackNotificationActionSpecs(
    isPlaying: Boolean,
): List<SasayakiPlaybackNotificationActionSpec> =
    listOf(
        SasayakiPlaybackNotificationActionSpec(
            playerCommand = Player.COMMAND_SEEK_BACK,
            iconResId = Media3R.drawable.media3_icon_rewind,
            titleResId = R.string.sasayaki_rewind,
        ),
        SasayakiPlaybackNotificationActionSpec(
            playerCommand = Player.COMMAND_PLAY_PAUSE,
            iconResId = if (isPlaying) {
                Media3R.drawable.media3_icon_pause
            } else {
                Media3R.drawable.media3_icon_play
            },
            titleResId = if (isPlaying) R.string.sasayaki_pause else R.string.sasayaki_play,
        ),
        SasayakiPlaybackNotificationActionSpec(
            playerCommand = Player.COMMAND_SEEK_FORWARD,
            iconResId = Media3R.drawable.media3_icon_fast_forward,
            titleResId = R.string.sasayaki_fast_forward,
        ),
    )

@OptIn(UnstableApi::class)
internal class SasayakiPlaybackNotificationProvider(
    private val context: Context,
    private val contentIntent: () -> PendingIntent,
    private val isPlaybackOngoing: () -> Boolean = { false },
) : MediaNotification.Provider {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    override fun createNotification(
        session: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification =
        MediaNotification(
            SasayakiPlaybackNotificationId,
            buildNotification(session),
        )

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle,
    ): Boolean = false

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        MediaNotification.Provider.NotificationChannelInfo(
            SasayakiPlaybackNotificationChannelId,
            context.getString(R.string.sasayaki_title),
        )

    fun cancel() {
        notificationManager.cancel(SasayakiPlaybackNotificationId)
    }

    @OptIn(UnstableApi::class)
    fun buildNotification(session: MediaSession): Notification {
        ensureChannel()
        val player = session.player
        val metadata = player.mediaMetadata
        val title = metadata.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.sasayaki_title)
        val isPlaying = player.isPlaying || isPlaybackOngoing()
        val builder = Notification.Builder(context, SasayakiPlaybackNotificationChannelId)
            .setSmallIcon(R.drawable.ic_stat_hoshi)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.sasayaki_playback))
            .setContentIntent(contentIntent())
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
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

        sasayakiPlaybackNotificationActionSpecs(isPlaying).forEach { spec ->
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
    private fun actionIntent(spec: SasayakiPlaybackNotificationActionSpec): PendingIntent =
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
