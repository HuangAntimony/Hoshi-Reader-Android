package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiMatch

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import java.io.File

internal interface SasayakiPlaybackControllerContract {
    val playback: SasayakiPlaybackData
    val currentTime: Double
    val duration: Double
    val isPlaying: Boolean
    val errorMessage: String?
    var autoScroll: Boolean
    val hasAudio: Boolean
    val hasMatch: Boolean
    val delay: Double
    val rate: Float
    val audioStorageSummary: String

    fun setDelay(value: Double)
    fun setRate(value: Float)
    fun importAudio(audioUri: Uri, copiedAudioFileName: String?)
    fun clearAudio()
    fun togglePlayback()
    fun pausePlayback(restoreTemporaryPosition: Boolean)
    fun nextCue()
    fun previousCue()
    fun findCue(chapterIndex: Int, offset: Int): SasayakiMatch?
    fun playCue(cue: SasayakiMatch, stop: Boolean)
    fun exportCueAudio(cue: SasayakiMatch, sentence: String): File?
    fun release()
}

internal class SasayakiPlaybackController(
    context: Context,
    bookRoot: File,
    playbackRepository: SasayakiPlaybackRepository,
    bookTitle: String?,
    bookCoverFile: File?,
    private val matchData: SasayakiMatchData?,
    initialPlayback: SasayakiPlaybackData?,
    persistenceScope: CoroutineScope,
    private val getCurrentChapterIndex: () -> Int,
    onCue: (SasayakiMatch, Boolean) -> Unit,
    onClearCue: () -> Unit,
    onLoadChapter: (Int) -> Unit,
) : SasayakiPlaybackControllerContract {
    private val appContext = context.applicationContext
    private val audioSourceRepository = SasayakiAudioRepository(bookRoot)
    private val cueAudioExporter = SasayakiCueAudioExporter(
        context = appContext,
    )
    private val playbackPersistence = SasayakiPlaybackPersistenceState(
        playbackRepository = playbackRepository,
        audioSourceRepository = audioSourceRepository,
        initialPlayback = initialPlayback,
        persistenceScope = persistenceScope,
    )
    private val handler = Handler(Looper.getMainLooper())
    private val cueNavigation = SasayakiCueNavigationController(matchData)
    private val playbackState = SasayakiPlaybackStateCoordinator(
        initialPosition = playback.lastPosition,
    )
    private val cueDisplay = SasayakiCueDisplayCoordinator()
    private val cueDisplayActionDispatcher = SasayakiCueDisplayActionDispatcher(
        onCue = onCue,
        onClearCue = onClearCue,
        onLoadChapter = onLoadChapter,
    )
    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 125L)
        }
    }
    private val playbackLifecycle = SasayakiPlaybackLifecycleController(
        playbackState = playbackState,
        tickScheduler = HandlerSasayakiTickScheduler(
            handler = handler,
            tickRunnable = tickRunnable,
        ),
    )
    private val temporaryPlaybackRestore = SasayakiTemporaryPlaybackRestoreCoordinator(
        playbackState = playbackState,
        playbackLifecycle = playbackLifecycle,
    )
    private val playbackCommands = SasayakiPlaybackCommandCoordinator(
        playbackState = playbackState,
        playbackLifecycle = playbackLifecycle,
        cueNavigation = cueNavigation,
    )
    private val audioRestoreCallbacks = SasayakiAudioRestoreCallbacksCoordinator(
        playbackLifecycle = playbackLifecycle,
        playbackCommands = playbackCommands,
    )
    private val playbackSettings = SasayakiPlaybackSettingsCoordinator(
        playbackPersistence = playbackPersistence,
        playbackLifecycle = playbackLifecycle,
    )
    private val playbackEvents = SasayakiPlaybackEventCoordinator(
        playbackState = playbackState,
        playbackLifecycle = playbackLifecycle,
        playbackPersistence = playbackPersistence,
        cueNavigation = cueNavigation,
        cueDisplay = cueDisplay,
    )
    private val audioRestore = SasayakiAudioRestoreController(
        context = appContext,
        bookRoot = bookRoot,
        bookTitle = bookTitle,
        bookCoverFile = bookCoverFile,
        audioSourceRepository = audioSourceRepository,
        playbackLifecycle = playbackLifecycle,
    )
    private val audioAvailability = SasayakiAudioAvailabilityState()
    private val audioCommands = SasayakiAudioCommandCoordinator(
        audioSourceRepository = audioSourceRepository,
        playbackPersistence = playbackPersistence,
        playbackState = playbackState,
        audioAvailability = audioAvailability,
        contentResolver = appContext.contentResolver,
    )
    private val mediaSessionHandle = SasayakiMediaSessionHandleCoordinator()
    private val mediaSessionPublishing = SasayakiMediaSessionPublishingCoordinator(
        mediaSessionHandle = mediaSessionHandle,
    )
    private val audioRestoreResult = SasayakiAudioRestoreResultCoordinator(
        mediaSessionHandle = mediaSessionHandle,
        playbackState = playbackState,
        audioAvailability = audioAvailability,
    )
    private val audioRestoreWorkflow = SasayakiAudioRestoreWorkflowCoordinator(
        audioRestore = audioRestore,
        audioRestoreCallbacks = audioRestoreCallbacks,
        audioRestoreResult = audioRestoreResult,
    )
    private val playbackTeardown = SasayakiPlaybackTeardownCoordinator(
        playbackLifecycle = playbackLifecycle,
        mediaSessionHandle = mediaSessionHandle,
        audioAvailability = audioAvailability,
        cueDisplay = cueDisplay,
    )
    private val cuePresentation = SasayakiCuePresentationState()
    private val playbackStart = SasayakiPlaybackStartCoordinator(
        playbackCommands = playbackCommands,
        cuePresentation = cuePresentation,
        mediaSessionPublishing = mediaSessionPublishing,
    )
    private val playbackTick = SasayakiPlaybackTickCoordinator(
        playbackEvents = playbackEvents,
        cuePresentation = cuePresentation,
        getCurrentChapterIndex = getCurrentChapterIndex,
    )
    private val cueUpdate = SasayakiCueUpdateCoordinator(
        playbackEvents = playbackEvents,
        cuePresentation = cuePresentation,
        getCurrentChapterIndex = getCurrentChapterIndex,
    )
    private val seekComplete = SasayakiSeekCompleteCoordinator(
        playbackEvents = playbackEvents,
        cuePresentation = cuePresentation,
        getCurrentChapterIndex = getCurrentChapterIndex,
    )

    override val playback: SasayakiPlaybackData get() = playbackPersistence.playback
    override val currentTime: Double get() = playbackState.currentTime
    override val duration: Double get() = playbackState.duration
    override val isPlaying: Boolean get() = playbackState.isPlaying
    override val errorMessage: String? get() = audioAvailability.errorMessage
    override var autoScroll: Boolean
        get() = cuePresentation.autoScroll
        set(value) {
            cuePresentation.autoScroll = value
        }
    override val hasAudio: Boolean get() = audioAvailability.hasAudio
    override val hasMatch: Boolean = matchData != null
    override val delay: Double get() = playback.delay
    override val rate: Float get() = playback.rate
    override val audioStorageSummary: String
        get() = playbackPersistence.audioStorageSummary

    init {
        restoreAudio()
    }

    override fun setDelay(value: Double) {
        playbackSettings.setDelay(
            value = value,
            currentTime = currentTime,
            updateCue = ::updateCue,
        )
    }

    override fun setRate(value: Float) {
        playbackSettings.setRate(
            value = value,
            updateMediaSession = ::updateMediaSession,
        )
    }

    override fun importAudio(audioUri: Uri, copiedAudioFileName: String?) {
        audioCommands.importAudio(
            audioUri = audioUri,
            copiedAudioFileName = copiedAudioFileName,
            teardownPlayer = ::teardownPlayer,
            restoreAudio = ::restoreAudio,
        )
    }

    override fun clearAudio() {
        audioCommands.clearAudio(
            playback = playback,
            teardownPlayer = ::teardownPlayer,
        )
    }

    override fun togglePlayback() {
        playbackCommands.toggle(
            isPlaying = isPlaying,
            startPlayback = ::startPlayback,
            pausePlayback = { pausePlayback(restoreTemporaryPosition = true) },
        )
    }

    override fun pausePlayback(restoreTemporaryPosition: Boolean) {
        playbackCommands.pause(
            restoreTemporaryPosition = restoreTemporaryPosition,
            updateMediaSession = ::updateMediaSession,
            restoreTemporaryPositionIfNeeded = ::restoreTemporaryPlaybackPositionIfNeeded,
        )
    }

    override fun nextCue() {
        playbackCommands.nextCue(
            currentTime = currentTime,
            delay = delay,
            isPlaying = isPlaying,
        )
    }

    override fun previousCue() {
        playbackCommands.previousCue(
            currentTime = currentTime,
            delay = delay,
            isPlaying = isPlaying,
        )
    }

    override fun findCue(chapterIndex: Int, offset: Int): SasayakiMatch? =
        cueNavigation.findCue(chapterIndex = chapterIndex, offset = offset)

    override fun playCue(cue: SasayakiMatch, stop: Boolean) {
        playbackCommands.playCue(
            cue = cue,
            stop = stop,
            isPlaying = isPlaying,
            lastPosition = playback.lastPosition,
            delay = delay,
            pauseWithoutRestore = { pausePlayback(restoreTemporaryPosition = false) },
        )
    }

    override fun exportCueAudio(cue: SasayakiMatch, sentence: String): File? {
        val source = audioSourceRepository.playbackSource(playback) ?: return null
        val range = SasayakiCueAudioRangeResolver.resolve(
            matchData = matchData,
            cue = cue,
            sentence = sentence,
            delay = delay,
        )
        return cueAudioExporter.export(
            source = source,
            cue = cue,
            range = range,
        )
    }

    override fun release() {
        teardownPlayer(clearCue = true)
    }

    private fun startPlayback() {
        playbackStart.start(
            rate = rate,
            currentTime = { currentTime },
            updateMediaSession = ::updateMediaSession,
            redisplayCue = { time -> updateCue(time, forceDisplay = true) },
        )
    }

    private fun handleSeekComplete() {
        seekComplete.handle(
            hasAudio = hasAudio,
            hasMatch = hasMatch,
            delay = delay,
            startPlayback = ::startPlayback,
            updateMediaSession = ::updateMediaSession,
            applyCueDisplayAction = ::applyCueDisplayAction,
        )
    }

    private fun restoreAudio() {
        audioRestoreWorkflow.restore(
            playback = playback,
            currentTime = { currentTime },
            releaseExistingMediaSession = mediaSessionHandle::releaseExisting,
            updateMediaSession = ::updateMediaSession,
            handleSeekComplete = ::handleSeekComplete,
            startPlayback = ::startPlayback,
            pausePlayback = { pausePlayback(restoreTemporaryPosition = true) },
            previousCue = ::previousCue,
            nextCue = ::nextCue,
            isPlaying = { isPlaying },
            updateCue = ::updateCue,
        )
    }

    private fun tick() {
        playbackTick.tick(
            hasAudio = hasAudio,
            hasMatch = hasMatch,
            delay = delay,
            pausePlayback = { pausePlayback(restoreTemporaryPosition = true) },
            updateMediaSession = ::updateMediaSession,
            applyCueDisplayAction = ::applyCueDisplayAction,
        )
    }

    private fun updateCue(time: Double, forceDisplay: Boolean = false) {
        cueUpdate.update(
            hasAudio = hasAudio,
            hasMatch = hasMatch,
            time = time,
            delay = delay,
            forceDisplay = forceDisplay,
            applyCueDisplayAction = ::applyCueDisplayAction,
        )
    }

    private fun applyCueDisplayAction(action: SasayakiCueDisplayAction) {
        cueDisplayActionDispatcher.apply(action)
    }

    private fun updateMediaSession() {
        mediaSessionPublishing.update(
            isPlaying = isPlaying,
            currentTime = currentTime,
            duration = duration,
            rate = rate,
        )
    }

    private fun restoreTemporaryPlaybackPositionIfNeeded() {
        temporaryPlaybackRestore.restoreIfNeeded(
            updateCue = ::updateCue,
            updateMediaSession = ::updateMediaSession,
        )
    }

    private fun teardownPlayer(clearCue: Boolean) {
        playbackTeardown.teardown(
            clearCue = clearCue,
            pausePlayback = { pausePlayback(restoreTemporaryPosition = true) },
            applyCueDisplayAction = ::applyCueDisplayAction,
        )
    }
}
