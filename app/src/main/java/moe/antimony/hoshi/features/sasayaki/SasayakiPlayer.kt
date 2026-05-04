package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File

class SasayakiPlayer(
    context: Context,
    private val bookRoot: File,
    private val playbackRepository: SasayakiPlaybackRepository,
    private val bookTitle: String?,
    private val bookCoverFile: File?,
    matchData: SasayakiMatchData?,
    private val getCurrentChapterIndex: () -> Int,
    private val onCue: (SasayakiMatch, Boolean) -> Unit,
    private val onClearCue: () -> Unit,
    private val onLoadChapter: (Int) -> Unit,
) {
    private val appContext = context.applicationContext
    private val audioSourceRepository = SasayakiAudioRepository(bookRoot)
    private val playbackPersistence = SasayakiPlaybackPersistenceState(
        playbackRepository = playbackRepository,
        audioSourceRepository = audioSourceRepository,
    )
    private val handler = Handler(Looper.getMainLooper())
    private val cueNavigation = SasayakiCueNavigationController(matchData)
    private val playbackState = SasayakiPlaybackStateCoordinator(
        initialPosition = playback.lastPosition,
    )
    private val cueDisplay = SasayakiCueDisplayCoordinator()
    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 125L)
        }
    }
    private val playbackLifecycle = SasayakiPlaybackLifecycleController(
        playbackState = playbackState,
        handler = handler,
        tickRunnable = tickRunnable,
    )
    private val playbackCommands = SasayakiPlaybackCommandCoordinator(
        playbackState = playbackState,
        playbackLifecycle = playbackLifecycle,
        cueNavigation = cueNavigation,
        cueDisplay = cueDisplay,
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
    private val playbackTeardown = SasayakiPlaybackTeardownCoordinator(
        playbackLifecycle = playbackLifecycle,
        mediaSessionHandle = mediaSessionHandle,
        audioAvailability = audioAvailability,
        cueDisplay = cueDisplay,
    )
    private val cuePresentation = SasayakiCuePresentationState()

    val playback: SasayakiPlaybackData get() = playbackPersistence.playback
    val currentTime: Double get() = playbackState.currentTime
    val duration: Double get() = playbackState.duration
    val isPlaying: Boolean get() = playbackState.isPlaying
    val errorMessage: String? get() = audioAvailability.errorMessage
    var autoScroll: Boolean
        get() = cuePresentation.autoScroll
        set(value) {
            cuePresentation.autoScroll = value
        }
    val hasAudio: Boolean get() = audioAvailability.hasAudio

    val hasMatch: Boolean = matchData != null

    val delay: Double get() = playback.delay
    val rate: Float get() = playback.rate
    val audioStorageSummary: String
        get() = playbackPersistence.audioStorageSummary

    init {
        restoreAudio()
    }

    fun setDelay(value: Double) {
        playbackSettings.setDelay(
            value = value,
            currentTime = currentTime,
            updateCue = ::updateCue,
        )
    }

    fun setRate(value: Float) {
        playbackSettings.setRate(
            value = value,
            updateMediaSession = ::updateMediaSession,
        )
    }

    fun importAudio(audioUri: Uri, copiedAudioFileName: String? = null) {
        audioCommands.importAudio(
            audioUri = audioUri,
            copiedAudioFileName = copiedAudioFileName,
            teardownPlayer = ::teardownPlayer,
            restoreAudio = ::restoreAudio,
        )
    }

    fun clearAudio() {
        audioCommands.clearAudio(
            playback = playback,
            teardownPlayer = ::teardownPlayer,
        )
    }

    fun togglePlayback() {
        playbackCommands.toggle(
            isPlaying = isPlaying,
            startPlayback = ::startPlayback,
            pausePlayback = { pausePlayback() },
        )
    }

    fun pausePlayback(restoreTemporaryPosition: Boolean = true) {
        playbackCommands.pause(
            restoreTemporaryPosition = restoreTemporaryPosition,
            updateMediaSession = ::updateMediaSession,
            restoreTemporaryPositionIfNeeded = ::restoreTemporaryPlaybackPositionIfNeeded,
        )
    }

    fun nextCue() {
        playbackCommands.nextCue(
            currentTime = currentTime,
            delay = delay,
            isPlaying = isPlaying,
        )
    }

    fun previousCue() {
        playbackCommands.previousCue(
            currentTime = currentTime,
            delay = delay,
            isPlaying = isPlaying,
        )
    }

    fun findCue(chapterIndex: Int, offset: Int): SasayakiMatch? =
        cueNavigation.findCue(chapterIndex = chapterIndex, offset = offset)

    fun playCue(cue: SasayakiMatch, stop: Boolean) {
        playbackCommands.playCue(
            cue = cue,
            stop = stop,
            isPlaying = isPlaying,
            lastPosition = playback.lastPosition,
            delay = delay,
            pauseWithoutRestore = { pausePlayback(restoreTemporaryPosition = false) },
        )
    }

    fun release() {
        teardownPlayer(clearCue = true)
    }

    private fun startPlayback() {
        playbackCommands.start(
            rate = rate,
            markPlayedOnce = cuePresentation::markPlayedOnce,
            afterMarkedPlaying = {
                updateMediaSession()
                mediaSessionPublishing.activate()
                updateCue(currentTime, forceDisplay = true)
            },
        )
    }

    private fun seek(
        seconds: Double,
        startPlayback: Boolean,
        updateCue: Boolean = true,
        savePosition: Boolean = true,
        displayCue: SasayakiMatch? = null,
    ) {
        playbackCommands.seek(
            seconds = seconds,
            startPlayback = startPlayback,
            updateCue = updateCue,
            savePosition = savePosition,
            displayCue = displayCue,
        )
    }

    private fun handleSeekComplete() {
        playbackEvents.handleSeekComplete(
            hasAudio = hasAudio,
            hasMatch = hasMatch,
            delay = delay,
            currentChapterIndex = getCurrentChapterIndex(),
            autoScroll = cuePresentation.autoScroll,
            hasPlayedOnce = cuePresentation.hasPlayedOnce,
            startPlayback = ::startPlayback,
            updateMediaSession = ::updateMediaSession,
            applyCueDisplayAction = ::applyCueDisplayAction,
        )
    }

    private fun restoreAudio() {
        val result = runCatching {
            audioRestore.restore(
                playback = playback,
                releaseExistingMediaSession = mediaSessionHandle::releaseExisting,
                callbacks = audioRestoreCallbacks.build(
                    updateMediaSession = ::updateMediaSession,
                    handleSeekComplete = ::handleSeekComplete,
                    startPlayback = ::startPlayback,
                    pausePlayback = { pausePlayback() },
                    previousCue = ::previousCue,
                    nextCue = ::nextCue,
                    isPlaying = { isPlaying },
                ),
            )
        }.onFailure(audioRestoreResult::handleFailure).getOrNull() ?: return
        audioRestoreResult.handleSuccess(
            result = result,
            currentTime = currentTime,
            updateCue = ::updateCue,
            updateMediaSession = ::updateMediaSession,
        )
    }

    private fun tick() {
        playbackEvents.tick(
            hasAudio = hasAudio,
            hasMatch = hasMatch,
            delay = delay,
            currentChapterIndex = getCurrentChapterIndex(),
            autoScroll = cuePresentation.autoScroll,
            hasPlayedOnce = cuePresentation.hasPlayedOnce,
            pausePlayback = { pausePlayback() },
            updateMediaSession = ::updateMediaSession,
            applyCueDisplayAction = ::applyCueDisplayAction,
        )
    }

    private fun updateCue(time: Double, forceDisplay: Boolean = false) {
        playbackEvents.updateCue(
            hasAudio = hasAudio,
            hasMatch = hasMatch,
            time = time,
            delay = delay,
            currentChapterIndex = getCurrentChapterIndex(),
            autoScroll = cuePresentation.autoScroll,
            hasPlayedOnce = cuePresentation.hasPlayedOnce,
            forceDisplay = forceDisplay,
            applyCueDisplayAction = ::applyCueDisplayAction,
        )
    }

    private fun applyCueDisplayAction(action: SasayakiCueDisplayAction) {
        when (action) {
            SasayakiCueDisplayAction.None -> Unit
            SasayakiCueDisplayAction.Clear -> onClearCue()
            is SasayakiCueDisplayAction.Display -> onCue(action.cue, action.reveal)
            is SasayakiCueDisplayAction.ClearAndLoadChapter -> {
                onClearCue()
                onLoadChapter(action.chapterIndex)
            }
        }
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
        val returnPosition = playbackState.restoreTemporaryPlaybackPositionIfNeeded() ?: return
        playbackLifecycle.seekTo((returnPosition * 1000.0).toInt())
        updateCue(returnPosition)
        updateMediaSession()
    }

    private fun teardownPlayer(clearCue: Boolean) {
        playbackTeardown.teardown(
            clearCue = clearCue,
            pausePlayback = { pausePlayback() },
            applyCueDisplayAction = ::applyCueDisplayAction,
        )
    }
}
