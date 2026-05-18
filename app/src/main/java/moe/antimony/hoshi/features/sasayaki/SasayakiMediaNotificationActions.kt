package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton

internal data class SasayakiMediaNotificationActionSpec(
    @param:DrawableRes val iconResId: Int,
    val title: String,
    val action: String,
) {
    fun toNotificationCompatAction(
        context: Context,
        sessionId: String,
    ): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            iconResId,
            title,
            SasayakiMediaNotificationActionReceiver.pendingIntent(
                context = context,
                sessionId = sessionId,
                action = action,
            ),
        ).build()
}

@OptIn(UnstableApi::class)
internal object SasayakiMediaNotificationActions {
    const val ActionPrevious = "moe.antimony.hoshi.features.sasayaki.action.PREVIOUS"
    const val ActionPlay = "moe.antimony.hoshi.features.sasayaki.action.PLAY"
    const val ActionPause = "moe.antimony.hoshi.features.sasayaki.action.PAUSE"
    const val ActionNext = "moe.antimony.hoshi.features.sasayaki.action.NEXT"

    val CompactViewIndices = intArrayOf(0, 1, 2)

    fun forPlaybackState(isPlaying: Boolean): List<SasayakiMediaNotificationActionSpec> =
        listOf(
            SasayakiMediaNotificationActionSpec(
                iconResId = CommandButton.getIconResIdForIconConstant(CommandButton.ICON_PREVIOUS),
                title = "Previous Cue",
                action = ActionPrevious,
            ),
            SasayakiMediaNotificationActionSpec(
                iconResId = CommandButton.getIconResIdForIconConstant(
                    if (isPlaying) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY,
                ),
                title = if (isPlaying) "Pause" else "Play",
                action = if (isPlaying) ActionPause else ActionPlay,
            ),
            SasayakiMediaNotificationActionSpec(
                iconResId = CommandButton.getIconResIdForIconConstant(CommandButton.ICON_NEXT),
                title = "Next Cue",
                action = ActionNext,
            ),
        )
}
