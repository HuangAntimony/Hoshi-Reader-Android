package moe.antimony.hoshi.features.sasayaki

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.MainActivity
import moe.antimony.hoshi.R
import java.io.File

internal const val SasayakiPlaybackReturnAction = "moe.antimony.hoshi.action.RETURN_TO_SASAYAKI_READER"
internal const val SasayakiPlaybackReturnBookIdExtra = "moe.antimony.hoshi.extra.SASAYAKI_BOOK_ID"
private const val SasayakiPreviousCueAction = "moe.antimony.hoshi.sasayaki.action.PREVIOUS_CUE"
private const val SasayakiNextCueAction = "moe.antimony.hoshi.sasayaki.action.NEXT_CUE"

internal data class SasayakiPlaybackRuntimeLoadRequest(
    val bookId: String,
    val bookRoot: File,
    val playbackRepository: SasayakiPlaybackRepository,
    val bookTitle: String?,
    val bookCoverFile: File?,
    val matchData: SasayakiMatchData?,
    val initialPlayback: SasayakiPlaybackData?,
    val persistenceScope: CoroutineScope,
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
) : SasayakiPlaybackRuntime {
    private val appContext = context.applicationContext
    private var player: ExoPlayerSasayakiPlayerHandle? = null
    private var session: MediaSession? = null
    private var activeKey: ActivePlaybackKey? = null
    private var activeBookId: String? = null
    private var activeController: SasayakiPlaybackControllerContract? = null
    private val readerAttachment = SasayakiReaderAttachment()

    fun createSession(): MediaSession {
        session?.let { return it }

        val createdPlayer = ExoPlayerSasayakiPlayerHandle(ExoPlayer.Builder(appContext).build()).apply {
            setAudioAttributes(sasayakiMedia3AudioAttributes(), true)
        }
        val mediaButtons = sasayakiServiceMediaButtons(appContext)
        val sessionPlayer = SasayakiServiceSessionPlayer(
            player = createdPlayer.player,
            runtime = this,
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

    fun currentPlayer(): Media3SasayakiPlayerHandle? =
        player

    fun activePlaybackBookId(): String? =
        activeBookId.takeIf { activeController != null }

    override fun load(
        request: SasayakiPlaybackRuntimeLoadRequest,
        getCurrentChapterIndex: () -> Int,
        onCue: (SasayakiMatch, Boolean) -> Unit,
        onClearCue: () -> Unit,
        onLoadChapter: (Int) -> Unit,
    ): SasayakiPlaybackControllerContract {
        appContext.startService(Intent(appContext, SasayakiPlaybackService::class.java))
        createSession()
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
            persistenceScope = request.persistenceScope,
            getCurrentChapterIndex = readerAttachment::currentChapterIndex,
            onCue = readerAttachment::cue,
            onClearCue = readerAttachment::clearCue,
            onLoadChapter = readerAttachment::loadChapter,
            playbackPreparer = ServiceOwnedSasayakiPlaybackPreparer(
                playerProvider = ::requirePlayer,
            ),
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
        appContext.stopService(Intent(appContext, SasayakiPlaybackService::class.java))
    }

    fun playFromSession() {
        val controller = activeController ?: return
        if (!controller.isPlaying) {
            controller.togglePlayback()
        }
    }

    fun pauseFromSession() {
        val controller = activeController ?: return
        if (controller.isPlaying) {
            controller.pausePlayback(restoreTemporaryPosition = true)
        }
    }

    fun seekFromSession(positionMs: Long) {
        if (positionMs == C.TIME_UNSET) return
        activeController?.seekTo(positionMs.coerceAtLeast(0L).toDouble() / 1000.0)
    }

    fun previousFromSession() {
        activeController?.previousCue()
    }

    fun nextFromSession() {
        activeController?.nextCue()
    }

    fun release() {
        releaseActiveController()
        readerAttachment.detach()
        session?.release()
        session = null
        player?.release()
        player = null
    }

    private fun requirePlayer(): Media3SasayakiPlayerHandle {
        createSession()
        return requireNotNull(player)
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
private class SasayakiServiceSessionPlayer(
    player: Player,
    private val runtime: SasayakiPlaybackServiceRuntime,
) : ForwardingSimpleBasePlayer(player) {
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            runtime.playFromSession()
        } else {
            runtime.pauseFromSession()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> runtime.previousFromSession()
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> runtime.nextFromSession()
            else -> runtime.seekFromSession(positionMs)
        }
        return Futures.immediateVoidFuture()
    }
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
            .setAvailableSessionCommands(sasayakiServiceSessionCommands())
            .setAvailablePlayerCommands(playerCommands)
            .setMediaButtonPreferences(mediaButtons)
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            SasayakiPreviousCueAction -> runtime.previousFromSession()
            SasayakiNextCueAction -> runtime.nextFromSession()
            else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }
}

@OptIn(UnstableApi::class)
private fun sasayakiServiceMediaButtons(context: Context): List<CommandButton> =
    listOf(
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setDisplayName(context.getString(R.string.sasayaki_previous_cue))
            .setSessionCommand(sasayakiPreviousCueCommand())
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setDisplayName(context.getString(R.string.sasayaki_next_cue))
            .setSessionCommand(sasayakiNextCueCommand())
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
    )

private fun sasayakiServiceSessionCommands(): SessionCommands =
    SessionCommands.Builder()
        .add(sasayakiPreviousCueCommand())
        .add(sasayakiNextCueCommand())
        .build()

private fun sasayakiPreviousCueCommand(): SessionCommand =
    SessionCommand(SasayakiPreviousCueAction, Bundle.EMPTY)

private fun sasayakiNextCueCommand(): SessionCommand =
    SessionCommand(SasayakiNextCueAction, Bundle.EMPTY)

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
