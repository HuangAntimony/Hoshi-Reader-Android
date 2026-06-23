package moe.antimony.hoshi.features.sasayaki

import android.app.PendingIntent
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import moe.antimony.hoshi.di.ApplicationScope
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.MainActivity
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import java.io.File

internal const val SasayakiPlaybackReturnAction = "moe.antimony.hoshi.action.RETURN_TO_SASAYAKI_READER"
internal const val SasayakiPlaybackReturnBookIdExtra = "moe.antimony.hoshi.extra.SASAYAKI_BOOK_ID"

internal data class SasayakiPlaybackRuntimeLoadRequest(
    val bookId: String,
    val bookRoot: File,
    val playbackRepository: SasayakiPlaybackRepository,
    val bookTitle: String?,
    val bookCoverFile: File?,
    val matchData: SasayakiMatchData?,
    val initialPlayback: SasayakiPlaybackData?,
)

internal interface SasayakiPlaybackRuntime {
    fun load(
        request: SasayakiPlaybackRuntimeLoadRequest,
        getCurrentChapterIndex: () -> Int,
        onCue: (SasayakiMatch, Boolean) -> Unit,
        onClearCue: () -> Unit,
        onLoadChapter: (Int) -> Unit,
    ): SasayakiPlaybackControllerContract

    fun detachReader()
    fun stopPlayback()
}

@OptIn(UnstableApi::class)
@Singleton
internal class SasayakiPlaybackServiceRuntime @Inject constructor(
    @ApplicationContext context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SasayakiPlaybackRuntime {
    private val appContext = context.applicationContext
    private var player: ExoPlayerSasayakiPlayerHandle? = null
    private var session: MediaSession? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var activeKey: ActivePlaybackKey? = null
    private var activeBookId: String? = null
    private var activeController: SasayakiPlaybackControllerContract? = null
    private val readerAttachment = SasayakiReaderAttachment()

    fun createSession(): MediaSession {
        session?.let { return it }

        val createdPlayer = ExoPlayerSasayakiPlayerHandle(
            ExoPlayer.Builder(appContext)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build(),
        ).apply {
            setAudioAttributes(sasayakiMedia3AudioAttributes(), true)
        }
        val mediaButtons = sasayakiServiceMediaButtons(appContext)
        val sessionPlayer = SasayakiServiceSessionPlayer(
            player = createdPlayer.player,
            onPlay = ::playFromSession,
            onPause = ::pauseFromSession,
            onSkipToPrevious = ::previousFromSession,
            onSkipToNext = ::nextFromSession,
            onSeekTo = ::seekToFromSession,
        )
        val createdSession = MediaSession.Builder(appContext, sessionPlayer)
            .setId(SasayakiPlaybackService.SessionId)
            .setMediaButtonPreferences(mediaButtons)
            .setSessionActivity(sasayakiPlaybackReturnPendingIntent(appContext, activeBookId))
            .setCallback(SasayakiPlaybackServiceSessionCallback(runtime = this, mediaButtons = mediaButtons))
            .build()

        player = createdPlayer
        session = createdSession
        return createdSession
    }

    fun currentSession(): MediaSession? =
        session

    fun activePlaybackBookId(): String? =
        activeBookId.takeIf { activeController != null }

    fun requiresOemRestrictedPlaybackNotificationFallback(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            appContext.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true

    fun playbackReturnPendingIntent(): PendingIntent =
        sasayakiPlaybackReturnPendingIntent(appContext, activeBookId)

    override fun load(
        request: SasayakiPlaybackRuntimeLoadRequest,
        getCurrentChapterIndex: () -> Int,
        onCue: (SasayakiMatch, Boolean) -> Unit,
        onClearCue: () -> Unit,
        onLoadChapter: (Int) -> Unit,
    ): SasayakiPlaybackControllerContract {
        createSession()
        ensureControllerConnection()
        readerAttachment.attach(
            getCurrentChapterIndex = getCurrentChapterIndex,
            onCue = onCue,
            onClearCue = onClearCue,
            onLoadChapter = onLoadChapter,
        )

        val requestedKey = ActivePlaybackKey(
            bookRoot = request.bookRoot.stableIdentity(),
            matchData = request.matchData,
        )
        activeController?.let { controller ->
            if (activeKey == requestedKey) {
                activeBookId = request.bookId
                session?.setSessionActivity(sasayakiPlaybackReturnPendingIntent(appContext, request.bookId))
                return controller
            }
        }

        releaseActiveController(clearBookId = false)
        activeBookId = request.bookId
        session?.setSessionActivity(sasayakiPlaybackReturnPendingIntent(appContext, request.bookId))
        val controller = SasayakiPlaybackController(
            context = appContext,
            bookRoot = request.bookRoot,
            playbackRepository = request.playbackRepository,
            bookTitle = request.bookTitle,
            bookCoverFile = request.bookCoverFile,
            matchData = request.matchData,
            initialPlayback = request.initialPlayback,
            persistenceScope = appScope,
            persistenceDispatcher = ioDispatcher,
            getCurrentChapterIndex = readerAttachment::currentChapterIndex,
            onCue = readerAttachment::cue,
            onClearCue = readerAttachment::clearCue,
            onLoadChapter = readerAttachment::loadChapter,
            playbackPreparer = ServiceOwnedSasayakiPlaybackPreparer(
                playerProvider = ::requirePlayer,
            ),
            onPlaybackStartRequested = ::ensureControllerConnection,
        )
        activeKey = requestedKey
        activeController = controller
        return controller
    }

    override fun detachReader() {
        readerAttachment.detach()
    }

    override fun stopPlayback() {
        releaseActiveController()
        readerAttachment.detach()
        releaseControllerConnection()
        appContext.stopService(playbackServiceIntent())
    }

    fun previousFromSession() {
        activeController?.previousCue()
    }

    fun playFromSession() {
        val controller = activeController ?: return
        if (!controller.isPlaying) {
            controller.togglePlayback()
        }
    }

    fun pauseFromSession() {
        activeController?.pausePlayback(restoreTemporaryPosition = true)
    }

    fun toggleFromNotification() {
        activeController?.togglePlayback()
    }

    fun nextFromSession() {
        activeController?.nextCue()
    }

    fun seekToFromSession(positionMs: Long) {
        activeController?.seekTo(positionMs.coerceAtLeast(0L) / 1000.0)
    }

    fun dispatchOemRestrictedNotificationAction(action: String?): Boolean {
        when (action) {
            SasayakiOemRestrictedNotificationPreviousCueAction -> previousFromSession()
            SasayakiOemRestrictedNotificationTogglePlaybackAction -> toggleFromNotification()
            SasayakiOemRestrictedNotificationNextCueAction -> nextFromSession()
            else -> return false
        }
        return true
    }

    fun release() {
        releaseActiveController()
        readerAttachment.detach()
        releaseControllerConnection()
        session?.release()
        session = null
        player?.release()
        player = null
    }

    private fun requirePlayer(): Media3SasayakiPlayerHandle {
        createSession()
        return requireNotNull(player)
    }

    private fun playbackServiceIntent(): Intent =
        Intent(MediaSessionService.SERVICE_INTERFACE).setClass(appContext, SasayakiPlaybackService::class.java)

    private fun ensureControllerConnection() {
        if (controllerFuture != null) return
        val sessionToken = SessionToken(
            appContext,
            ComponentName(appContext, SasayakiPlaybackService::class.java),
        )
        controllerFuture = MediaController.Builder(appContext, sessionToken).buildAsync()
    }

    private fun releaseControllerConnection() {
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
    }

    private fun releaseActiveController(clearBookId: Boolean = true) {
        activeController?.release()
        activeController = null
        activeKey = null
        if (clearBookId) {
            activeBookId = null
        }
    }

    private data class ActivePlaybackKey(
        val bookRoot: File,
        val matchData: SasayakiMatchData?,
    )

    private fun File.stableIdentity(): File =
        runCatching { canonicalFile }.getOrElse { absoluteFile }
}

@OptIn(UnstableApi::class)
private class SasayakiPlaybackServiceSessionCallback(
    private val runtime: SasayakiPlaybackServiceRuntime,
    private val mediaButtons: List<CommandButton>,
) : MediaSession.Callback {
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val playerCommands = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_GET_AUDIO_ATTRIBUTES)
            .add(Player.COMMAND_RELEASE)
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailablePlayerCommands(playerCommands)
            .setMediaButtonPreferences(mediaButtons)
            .build()
    }
}

@OptIn(UnstableApi::class)
private class SasayakiServiceSessionPlayer(
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
        dispatchSasayakiServicePlayerSeekCommand(
            seekCommand = seekCommand,
            positionMs = positionMs,
            previousCue = onSkipToPrevious,
            nextCue = onSkipToNext,
            seekTo = onSeekTo,
        )
        return Futures.immediateFuture(null)
    }
}

internal fun dispatchSasayakiServicePlayerSeekCommand(
    seekCommand: Int,
    positionMs: Long,
    previousCue: () -> Unit,
    nextCue: () -> Unit,
    seekTo: (Long) -> Unit,
) {
    when (seekCommand) {
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        -> previousCue()

        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        -> nextCue()

        else -> seekTo(positionMs)
    }
}

internal data class SasayakiServiceMediaButtonSpec(
    val icon: Int,
    val displayNameResId: Int,
    val slot: Int,
    val playerCommand: Int,
)

@OptIn(UnstableApi::class)
internal fun sasayakiServiceMediaButtonSpecs(): List<SasayakiServiceMediaButtonSpec> =
    listOf(
        SasayakiServiceMediaButtonSpec(
            icon = CommandButton.ICON_PREVIOUS,
            displayNameResId = R.string.sasayaki_previous_cue,
            slot = CommandButton.SLOT_BACK,
            playerCommand = Player.COMMAND_SEEK_TO_PREVIOUS,
        ),
        SasayakiServiceMediaButtonSpec(
            icon = CommandButton.ICON_NEXT,
            displayNameResId = R.string.sasayaki_next_cue,
            slot = CommandButton.SLOT_FORWARD,
            playerCommand = Player.COMMAND_SEEK_TO_NEXT,
        ),
    )

@OptIn(UnstableApi::class)
internal fun sasayakiServiceMediaButtons(context: Context): List<CommandButton> =
    sasayakiServiceMediaButtonSpecs().map { spec ->
        CommandButton.Builder(spec.icon)
            .setDisplayName(context.getString(spec.displayNameResId))
            .setPlayerCommand(spec.playerCommand)
            .setSlots(spec.slot)
            .build()
    }

internal fun sasayakiPlaybackReturnActivityFlags(): Int =
    Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT

private fun sasayakiPlaybackReturnPendingIntent(context: Context, bookId: String?): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
        .setAction(SasayakiPlaybackReturnAction)
        .addFlags(sasayakiPlaybackReturnActivityFlags())
    bookId?.let { intent.putExtra(SasayakiPlaybackReturnBookIdExtra, it) }
    return PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
