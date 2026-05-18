package moe.antimony.hoshi.features.sasayaki

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SasayakiMediaNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(ExtraSessionId) ?: return
        val action = intent.action ?: return
        SasayakiMediaNotificationActionRegistry.dispatch(
            sessionId = sessionId,
            action = action,
        )
    }

    companion object {
        fun pendingIntent(
            context: Context,
            sessionId: String,
            action: String,
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                action.requestCode(),
                Intent(context, SasayakiMediaNotificationActionReceiver::class.java)
                    .setAction(action)
                    .putExtra(ExtraSessionId, sessionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private const val ExtraSessionId = "moe.antimony.hoshi.features.sasayaki.extra.SESSION_ID"

        private fun String.requestCode(): Int =
            when (this) {
                SasayakiMediaNotificationActions.ActionPrevious -> 24070
                SasayakiMediaNotificationActions.ActionPlay -> 24071
                SasayakiMediaNotificationActions.ActionPause -> 24072
                SasayakiMediaNotificationActions.ActionNext -> 24073
                else -> 24079
            }
    }
}
