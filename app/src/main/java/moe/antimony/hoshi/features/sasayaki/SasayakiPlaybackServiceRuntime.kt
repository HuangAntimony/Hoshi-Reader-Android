package moe.antimony.hoshi.features.sasayaki

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
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
import java.util.concurrent.Executor

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
    private var playbackServiceConnection: ListenableFuture<MediaController>? = null
    private var activeKey: ActivePlaybackKey? = null
    private var activeBookId: String? = null
    private var activeController: SasayakiPlaybackControllerContract? = null
    private var foregroundPlaybackRequested = false
    private val readerAttachment = SasayakiReaderAttachment()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }

    fun createServiceSession(serviceContext: Context): MediaSession {
        session?.let { return it }

        val createdPlayer = ExoPlayerSasayakiPlayerHandle(
            ExoPlayer.Builder(serviceContext)
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
        val createdSession = MediaSession.Builder(serviceContext, sessionPlayer)
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
        activeBookId.takeIf { activeController?.hasAudio == true }

    fun playbackReturnPendingIntent(): PendingIntent =
        sasayakiPlaybackReturnPendingIntent(appContext, activeBookId)

    fun isForegroundPlaybackRequested(): Boolean =
        foregroundPlaybackRequested

    fun shouldRunPlaybackServiceInForeground(player: Player): Boolean =
        sasayakiShouldRunPlaybackServiceInForeground(
            foregroundPlaybackRequested = foregroundPlaybackRequested,
            playWhenReady = player.playWhenReady,
            playbackState = player.playbackState,
        )

    override fun load(
        request: SasayakiPlaybackRuntimeLoadRequest,
        getCurrentChapterIndex: () -> Int,
        onCue: (SasayakiMatch, Boolean) -> Unit,
        onClearCue: () -> Unit,
        onLoadChapter: (Int) -> Unit,
    ): SasayakiPlaybackControllerContract {
        val serviceConnection = ensurePlaybackServiceConnection()

        val requestedKey = ActivePlaybackKey(
            bookRoot = request.bookRoot.stableIdentity(),
            matchData = request.matchData,
        )
        activeController?.let { controller ->
            if (activeKey == requestedKey) {
                activeBookId = request.bookId
                session?.setSessionActivity(sasayakiPlaybackReturnPendingIntent(appContext, request.bookId))
                readerAttachment.attach(
                    getCurrentChapterIndex = getCurrentChapterIndex,
                    onCue = onCue,
                    onClearCue = onClearCue,
                    onLoadChapter = onLoadChapter,
                )
                return controller
            }
        }

        releaseActiveController(clearBookId = false)
        readerAttachment.attach(
            getCurrentChapterIndex = getCurrentChapterIndex,
            onCue = onCue,
            onClearCue = onClearCue,
            onLoadChapter = onLoadChapter,
        )
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
            onPlaybackStartRequested = ::ensurePlaybackServiceConnection,
            onForegroundPlaybackRequestedChanged = ::setForegroundPlaybackRequested,
            restoreAudioOnCreate = false,
        )
        activeKey = requestedKey
        activeController = controller
        restoreAudioWhenServiceReady(controller, serviceConnection)
        return controller
    }

    override fun detachReader() {
        readerAttachment.detach()
    }

    override fun stopPlayback() {
        setForegroundPlaybackRequested(false)
        releaseActiveController()
        readerAttachment.detach()
        releasePlaybackServiceConnection()
    }

    fun previousFromSession() {
        activeController?.previousCue()
    }

    fun playFromSession(): Boolean {
        val controller = activeController ?: return false
        if (!controller.isPlaying) {
            controller.togglePlayback()
        }
        return true
    }

    fun pauseFromSession(): Boolean {
        val controller = activeController ?: return false
        controller.pausePlayback(restoreTemporaryPosition = true)
        return true
    }

    fun nextFromSession() {
        activeController?.nextCue()
    }

    fun seekToFromSession(positionMs: Long) {
        activeController?.seekTo(positionMs.coerceAtLeast(0L) / 1000.0)
    }

    fun release() {
        setForegroundPlaybackRequested(false)
        releaseActiveController()
        readerAttachment.detach()
        releasePlaybackServiceConnection()
        session?.release()
        session = null
        player?.release()
        player = null
    }

    private fun requirePlayer(): Media3SasayakiPlayerHandle {
        return requireNotNull(player) {
            "SasayakiPlaybackService must create the player before audio can be restored."
        }
    }

    private fun ensurePlaybackServiceConnection(): ListenableFuture<MediaController> {
        playbackServiceConnection?.let { return it }
        // Reader still calls this in-process runtime; the service connection enters the MediaSessionService lifecycle.
        val sessionToken = SessionToken(
            appContext,
            ComponentName(appContext, SasayakiPlaybackService::class.java),
        )
        return MediaController.Builder(appContext, sessionToken).buildAsync().also { future ->
            playbackServiceConnection = future
        }
    }

    internal fun releasePlaybackServiceConnection() {
        playbackServiceConnection?.let(MediaController::releaseFuture)
        playbackServiceConnection = null
    }

    private fun setForegroundPlaybackRequested(requested: Boolean) {
        foregroundPlaybackRequested = requested
    }

    private fun restoreAudioWhenServiceReady(
        controller: SasayakiPlaybackController,
        serviceConnection: ListenableFuture<MediaController>,
    ) {
        serviceConnection.addListener(
            {
                if (activeController !== controller) return@addListener
                runCatching { Futures.getDone(serviceConnection) }.getOrNull() ?: return@addListener
                controller.restoreAudio()
            },
            mainExecutor,
        )
    }

    private fun releaseActiveController(clearBookId: Boolean = true) {
        setForegroundPlaybackRequested(false)
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
            .add(Player.COMMAND_SEEK_BACK)
            .add(Player.COMMAND_SEEK_FORWARD)
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
    private val onPlay: () -> Boolean,
    private val onPause: () -> Boolean,
    private val onSkipToPrevious: () -> Unit,
    private val onSkipToNext: () -> Unit,
    private val onSeekTo: (Long) -> Unit,
) : ForwardingPlayer(player) {
    override fun play() {
        onPlay()
    }

    override fun pause() {
        onPause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) {
            play()
        } else {
            pause()
        }
    }

    override fun seekBack() {
        onSkipToPrevious()
    }

    override fun seekForward() {
        onSkipToNext()
    }

    override fun seekToPrevious() {
        onSkipToPrevious()
    }

    override fun seekToPreviousMediaItem() {
        onSkipToPrevious()
    }

    override fun seekToNext() {
        onSkipToNext()
    }

    override fun seekToNextMediaItem() {
        onSkipToNext()
    }

    override fun seekTo(positionMs: Long) {
        onSeekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        onSeekTo(positionMs)
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
        Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        -> previousCue()

        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_FORWARD,
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
            icon = CommandButton.ICON_REWIND,
            displayNameResId = R.string.sasayaki_rewind,
            slot = CommandButton.SLOT_BACK,
            playerCommand = Player.COMMAND_SEEK_BACK,
        ),
        SasayakiServiceMediaButtonSpec(
            icon = CommandButton.ICON_FAST_FORWARD,
            displayNameResId = R.string.sasayaki_fast_forward,
            slot = CommandButton.SLOT_FORWARD,
            playerCommand = Player.COMMAND_SEEK_FORWARD,
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
