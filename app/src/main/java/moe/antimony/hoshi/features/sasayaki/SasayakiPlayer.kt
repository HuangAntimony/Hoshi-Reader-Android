package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    private val mediaSessionHandle = SasayakiMediaSessionHandleCoordinator()
    private var hasPlayedOnce = false

    val playback: SasayakiPlaybackData get() = playbackPersistence.playback
    val currentTime: Double get() = playbackState.currentTime
    val duration: Double get() = playbackState.duration
    val isPlaying: Boolean get() = playbackState.isPlaying
    var errorMessage by mutableStateOf<String?>(null)
    var autoScroll by mutableStateOf(true)
    var hasAudio by mutableStateOf(false)
        private set

    val hasMatch: Boolean = matchData != null

    val delay: Double get() = playback.delay
    val rate: Float get() = playback.rate
    val audioStorageSummary: String
        get() = playbackPersistence.audioStorageSummary

    init {
        restoreAudio()
    }

    fun setDelay(value: Double) {
        playbackPersistence.setDelay(value)
        updateCue(currentTime)
    }

    fun setRate(value: Float) {
        playbackPersistence.setRate(value)
        playbackLifecycle.setRateIfPlaying(value)
        updateMediaSession()
    }

    fun importAudio(audioUri: Uri, copiedAudioFileName: String? = null) {
        teardownPlayer(clearCue = false)
        playbackPersistence.importAudio(audioUri, copiedAudioFileName)
        restoreAudio()
    }

    fun clearAudio() {
        audioSourceRepository.clearAudioSource(playback, appContext.contentResolver)
        teardownPlayer(clearCue = true)
        playbackPersistence.clearAudioMetadata()
        playbackState.clearAudioState()
        hasAudio = false
        errorMessage = null
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
            markPlayedOnce = { hasPlayedOnce = true },
            afterMarkedPlaying = {
                updateMediaSession()
                mediaSessionHandle.activate()
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
            autoScroll = autoScroll,
            hasPlayedOnce = hasPlayedOnce,
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
                callbacks = SasayakiAudioRestoreCallbacks(
                    onCompletion = {
                        playbackLifecycle.markCompleted(updateMediaSession = ::updateMediaSession)
                    },
                    onSeekComplete = ::handleSeekComplete,
                    onPlay = ::startPlayback,
                    onPause = { pausePlayback() },
                    onSkipToPrevious = ::previousCue,
                    onSkipToNext = ::nextCue,
                    onSeekTo = { positionMs ->
                        playbackCommands.mediaSessionSeek(
                            positionMs = positionMs,
                            isPlaying = isPlaying,
                        )
                    },
                ),
            )
        }.onFailure { error ->
            errorMessage = error.localizedMessage ?: "Unable to load audiobook."
            hasAudio = false
        }.getOrNull() ?: return
        mediaSessionHandle.replace(result.mediaSession)
        playbackState.updateDuration(result.durationMs)
        hasAudio = true
        errorMessage = null
        updateCue(currentTime)
        updateMediaSession()
    }

    private fun tick() {
        playbackEvents.tick(
            hasAudio = hasAudio,
            hasMatch = hasMatch,
            delay = delay,
            currentChapterIndex = getCurrentChapterIndex(),
            autoScroll = autoScroll,
            hasPlayedOnce = hasPlayedOnce,
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
            autoScroll = autoScroll,
            hasPlayedOnce = hasPlayedOnce,
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
        mediaSessionHandle.update(
            isPlaying = isPlaying,
            currentTimeMs = (currentTime * 1000.0).toLong(),
            durationMs = (duration * 1000.0).toLong(),
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
        pausePlayback()
        playbackLifecycle.releaseEngine()
        mediaSessionHandle.releaseAndClear()
        hasAudio = false
        if (clearCue) applyCueDisplayAction(cueDisplay.clear())
    }
}
