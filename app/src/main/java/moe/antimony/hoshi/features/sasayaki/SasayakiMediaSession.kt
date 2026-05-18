package moe.antimony.hoshi.features.sasayaki

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import moe.antimony.hoshi.R
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

@OptIn(UnstableApi::class)
class SasayakiMediaSession(
    context: Context,
    private val player: Player,
    private val title: String,
    private val artwork: Bitmap?,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onSkipToPrevious: () -> Unit,
    private val onSkipToNext: () -> Unit,
    private val onSeekTo: (Long) -> Unit,
    systemMediaControls: SasayakiSystemMediaControlsMode,
) {
    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private var systemMediaControls = systemMediaControls
    private var loggedSuppressionReason: String? = null
    private var isPlaying = false
    private var notificationPlaying: Boolean? = null
    private var hasPublishedNotification = false
    private val sessionPlayer = SasayakiSessionPlayer(
        player = player,
        onPlay = onPlay,
        onPause = onPause,
        onSkipToPrevious = onSkipToPrevious,
        onSkipToNext = onSkipToNext,
        onSeekTo = onSeekTo,
    )
    private val mediaButtons = listOf(
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setDisplayName("Previous Cue")
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setDisplayName("Next Cue")
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
    )
    private val session = MediaSession.Builder(appContext, sessionPlayer)
        .setId("hoshi-sasayaki-${System.identityHashCode(this)}")
        .setMediaButtonPreferences(mediaButtons)
        .setCallback(SasayakiSessionCallback())
        .apply {
            contentIntent()?.let { setSessionActivity(it) }
        }
        .build()

    init {
        suppressNotificationIfNeeded()
        publishMetadata()
    }

    fun activate() {
        if (suppressNotificationIfNeeded()) return
        publishNotification()
    }

    fun setSystemMediaControls(mode: SasayakiSystemMediaControlsMode) {
        if (systemMediaControls == mode) return
        systemMediaControls = mode
        if (suppressNotificationIfNeeded()) return
        if (isPlaying || session.connectedControllers.isNotEmpty()) {
            publishNotification()
        }
    }

    fun update(
        isPlaying: Boolean,
        currentTimeMs: Long,
        durationMs: Long,
        rate: Float,
    ) {
        this.isPlaying = isPlaying
        if (suppressNotificationIfNeeded()) return
        if ((session.connectedControllers.isNotEmpty() || hasPublishedNotification) && notificationPlaying != isPlaying) {
            publishNotification()
        }
    }

    fun release() {
        notificationManager.cancel(NotificationId)
        SasayakiMediaNotificationActionRegistry.unregister(session.id)
        session.release()
    }

    private fun publishMetadata() {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .apply {
                artworkBytes()?.let { bytes ->
                    setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
            }
            .build()
        val current = player.currentMediaItem ?: MediaItem.Builder().build()
        val mediaItem = current.buildUpon()
            .setMediaMetadata(metadata)
            .build()
        if (player.mediaItemCount == 0) {
            player.addMediaItem(mediaItem)
        } else {
            player.replaceMediaItem(player.currentMediaItemIndex.coerceAtLeast(0), mediaItem)
        }
    }

    private fun ensureNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                "Sasayaki Playback",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun suppressNotificationIfNeeded(): Boolean {
        val reason = SasayakiMediaNotificationCompatibility.suppressionReason(systemMediaControls) ?: return false
        if (hasPublishedNotification || notificationPlaying != null) {
            notificationManager.cancel(NotificationId)
        }
        SasayakiMediaNotificationActionRegistry.unregister(session.id)
        notificationPlaying = null
        hasPublishedNotification = false
        if (loggedSuppressionReason != reason) {
            Log.w(Tag, "Sasayaki media notification suppressed: $reason")
            loggedSuppressionReason = reason
        }
        return true
    }

    @SuppressLint("NotificationPermission")
    private fun publishNotification() {
        ensureNotificationChannel()
        SasayakiMediaNotificationActionRegistry.register(
            sessionId = session.id,
            onAction = ::handleNotificationAction,
        )
        val builder = NotificationCompat.Builder(appContext, ChannelId)
            .setSmallIcon(R.drawable.ic_stat_hoshi)
            .setContentTitle(title)
            .setContentText("Sasayaki")
            .setContentIntent(contentIntent())
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(*SasayakiMediaNotificationActions.CompactViewIndices),
            )
        SasayakiMediaNotificationActions.forPlaybackState(isPlaying).forEach { action ->
            builder.addAction(
                action.toNotificationCompatAction(
                    context = appContext,
                    sessionId = session.id,
                ),
            )
        }
        artwork?.let { builder.setLargeIcon(artwork) }
        val notification = builder.build()
        notificationManager.notify(NotificationId, notification)
        notificationPlaying = isPlaying
        hasPublishedNotification = true
    }

    private fun contentIntent(): PendingIntent? =
        appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            ?.let { intent ->
                PendingIntent.getActivity(
                    appContext,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

    private fun handleNotificationAction(action: String) {
        when (action) {
            SasayakiMediaNotificationActions.ActionPrevious -> onSkipToPrevious()
            SasayakiMediaNotificationActions.ActionPlay -> onPlay()
            SasayakiMediaNotificationActions.ActionPause -> onPause()
            SasayakiMediaNotificationActions.ActionNext -> onSkipToNext()
        }
    }

    private fun artworkBytes(): ByteArray? {
        val image = artwork ?: return null
        return ByteArrayOutputStream().use { output ->
            image.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
    }

    private inner class SasayakiSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                .buildUpon()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(playerCommands)
                .setMediaButtonPreferences(mediaButtons)
                .build()
        }
    }

    private class SasayakiSessionPlayer(
        player: Player,
        private val onPlay: () -> Unit,
        private val onPause: () -> Unit,
        private val onSkipToPrevious: () -> Unit,
        private val onSkipToNext: () -> Unit,
        private val onSeekTo: (Long) -> Unit,
    ) : ForwardingSimpleBasePlayer(player) {
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            if (playWhenReady) {
                onPlay()
            } else {
                onPause()
            }
            return Futures.immediateFuture(null)
        }

        override fun handleSeek(
            mediaItemIndex: Int,
            positionMs: Long,
            seekCommand: Int,
        ): ListenableFuture<*> {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                -> onSkipToPrevious()

                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                -> onSkipToNext()

                else -> onSeekTo(positionMs)
            }
            return Futures.immediateFuture(null)
        }
    }

    companion object {
        private const val ChannelId = "sasayaki_playback"
        private const val NotificationId = 2407
        private const val MaxArtworkDimensionPx = 900
        private const val Tag = "SasayakiMediaSession"

        fun loadCoverArt(file: File?): Bitmap? {
            file?.takeIf { it.isFile } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val sampleSize = coverDecodeSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxDimensionPx = MaxArtworkDimensionPx,
            )
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            return BitmapFactory.decodeFile(file.absolutePath, options)
        }

        private fun coverDecodeSampleSize(width: Int, height: Int, maxDimensionPx: Int): Int {
            if (width <= 0 || height <= 0 || maxDimensionPx <= 0) return 1
            var sampleSize = 1
            while (max(width / sampleSize, height / sampleSize) > maxDimensionPx) {
                sampleSize *= 2
            }
            return sampleSize
        }
    }
}
