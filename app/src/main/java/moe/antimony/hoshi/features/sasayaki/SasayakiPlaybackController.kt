package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiMatch

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import moe.antimony.hoshi.ui.UiText
import java.io.File

internal interface SasayakiPlaybackControllerContract {
    val playback: SasayakiPlaybackData
    val currentTime: Double
    val duration: Double
    val isPlaying: Boolean
    val errorMessage: UiText?
    var autoScroll: Boolean
    var readerSkipButtonAction: SasayakiReaderSkipButtonAction
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
    fun skipForward(seconds: Int)
    fun skipBackward(seconds: Int)
    fun seekTo(seconds: Double)
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
    playbackPreparer: SasayakiPlaybackPreparer,
    persistenceDispatcher: CoroutineDispatcher,
    private val onPlaybackStartRequested: () -> Unit = {},
    restoreAudioOnCreate: Boolean = true,
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
        persistenceDispatcher = persistenceDispatcher,
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
    private val playbackCommands = SasayakiPlaybackCommandCoordinator(
        playbackState = playbackState,
        playbackLifecycle = playbackLifecycle,
        cueNavigation = cueNavigation,
    )
    private val playbackEvents = SasayakiPlaybackEventCoordinator(
        playbackState = playbackState,
        playbackPersistence = playbackPersistence,
        cueNavigation = cueNavigation,
        cueDisplay = cueDisplay,
    )
    private val audioRestore = SasayakiAudioRestoreController(
        bookRoot = bookRoot,
        bookTitle = bookTitle,
        bookCoverFile = bookCoverFile,
        audioSourceRepository = audioSourceRepository,
        playbackLifecycle = playbackLifecycle,
        playbackPreparer = playbackPreparer,
    )
    private val audioAvailability = SasayakiAudioAvailabilityState(
        initialHasAudio = audioSourceRepository.playbackSource(playbackPersistence.playback) != null,
    )
    private val cuePresentation = SasayakiCuePresentationState()

    override val playback: SasayakiPlaybackData get() = playbackPersistence.playback
    override val currentTime: Double get() = playbackState.currentTime
    override val duration: Double get() = playbackState.duration
    override val isPlaying: Boolean get() = playbackState.isPlaying
    override val errorMessage: UiText? get() = audioAvailability.errorMessage
    override var autoScroll: Boolean
        get() = cuePresentation.autoScroll
        set(value) {
            cuePresentation.autoScroll = value
        }
    override var readerSkipButtonAction: SasayakiReaderSkipButtonAction = SasayakiReaderSkipButtonAction.Cue
    override val hasAudio: Boolean get() = audioAvailability.hasAudio
    override val hasMatch: Boolean = matchData != null
    override val delay: Double get() = playback.delay
    override val rate: Float get() = playback.rate
    override val audioStorageSummary: String
        get() = playbackPersistence.audioStorageSummary

    init {
        if (restoreAudioOnCreate) {
            restoreAudio()
        }
    }

    override fun setDelay(value: Double) {
        playbackPersistence.setDelay(value)
        updateCue(currentTime)
    }

    override fun setRate(value: Float) {
        playbackPersistence.setRate(value)
        playbackLifecycle.setRate(value)
    }

    override fun importAudio(audioUri: Uri, copiedAudioFileName: String?) {
        teardownPlayer(clearCue = false)
        playbackPersistence.importAudio(audioUri, copiedAudioFileName)
        restoreAudio()
    }

    override fun clearAudio() {
        audioSourceRepository.clearAudioSource(playback, appContext.contentResolver)
        teardownPlayer(clearCue = true)
        playbackPersistence.clearAudioMetadata()
        playbackState.clearAudioState()
        audioAvailability.markAudioCleared()
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
            restoreTemporaryPositionIfNeeded = ::restoreTemporaryPlaybackPositionIfNeeded,
        )
    }

    override fun nextCue() {
        val seconds = readerSkipButtonAction.seconds
        if (seconds == null) {
            playbackCommands.nextCue(
                currentTime = currentTime,
                delay = delay,
                isPlaying = isPlaying,
            )
        } else {
            playbackCommands.skipForward(
                currentTime = currentTime,
                duration = duration,
                seconds = seconds,
                isPlaying = isPlaying,
            )
        }
    }

    override fun previousCue() {
        val seconds = readerSkipButtonAction.seconds
        if (seconds == null) {
            playbackCommands.previousCue(
                currentTime = currentTime,
                delay = delay,
                isPlaying = isPlaying,
            )
        } else {
            playbackCommands.skipBackward(
                currentTime = currentTime,
                seconds = seconds,
                isPlaying = isPlaying,
            )
        }
    }

    override fun skipForward(seconds: Int) {
        playbackCommands.skipForward(
            currentTime = currentTime,
            duration = duration,
            seconds = seconds,
            isPlaying = isPlaying,
        )
    }

    override fun skipBackward(seconds: Int) {
        playbackCommands.skipBackward(
            currentTime = currentTime,
            seconds = seconds,
            isPlaying = isPlaying,
        )
    }

    override fun seekTo(seconds: Double) {
        playbackCommands.seekTo(
            seconds = seconds,
            duration = duration,
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
        playbackCommands.start(
            rate = rate,
            beforeStart = onPlaybackStartRequested,
            markPlayedOnce = cuePresentation::markPlayedOnce,
            afterMarkedPlaying = {
                updateCue(currentTime, forceDisplay = true)
            },
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
            applyCueDisplayAction = ::applyCueDisplayAction,
        )
    }

    internal fun restoreAudio() {
        val result = runCatching {
            audioRestore.restore(
                playback = playback,
                callbacks = SasayakiAudioRestoreCallbacks(
                    onPrepared = { durationMs ->
                        handleAudioPrepared(durationMs = durationMs, currentTime = currentTime)
                    },
                    onCompletion = playbackLifecycle::markCompleted,
                    onSeekComplete = ::handleSeekComplete,
                    onPlaybackActiveChanged = ::handlePlaybackActiveChanged,
                    onPositionChanged = ::handlePlayerPositionChanged,
                    onError = ::handleAudioRestoreFailure,
                ),
            )
        }.onFailure(::handleAudioRestoreFailure).getOrNull() ?: return
        handleAudioPrepared(durationMs = result.durationMs, currentTime = currentTime)
    }

    private fun handleAudioRestoreFailure(error: Throwable) {
        audioAvailability.markRestoreFailed(error)
    }

    private fun handleAudioPrepared(durationMs: Int, currentTime: Double) {
        playbackState.updateDuration(durationMs)
        audioAvailability.markRestoreSucceeded()
        updateCue(currentTime)
    }

    private fun tick() {
        val tick = playbackLifecycle.updateTick() ?: return
        if (tick.shouldSavePosition) {
            playbackPersistence.savePosition(currentTime)
        }
        if (tick.shouldStopPlayback) {
            pausePlayback(restoreTemporaryPosition = true)
        }
        playbackEvents.updateCue(
            hasAudio = hasAudio,
            hasMatch = hasMatch,
            time = currentTime,
            delay = delay,
            currentChapterIndex = getCurrentChapterIndex(),
            autoScroll = cuePresentation.autoScroll,
            hasPlayedOnce = cuePresentation.hasPlayedOnce,
            applyCueDisplayAction = ::applyCueDisplayAction,
        )
    }

    private fun handlePlaybackActiveChanged(active: Boolean) {
        playbackLifecycle.syncPlayerPlaybackActive(
            active = active,
            markPlayedOnce = cuePresentation::markPlayedOnce,
            afterMarkedPlaying = {
                updateCue(currentTime, forceDisplay = true)
            },
            restoreTemporaryPositionIfNeeded = ::restoreTemporaryPlaybackPositionIfNeeded,
        )
    }

    private fun handlePlayerPositionChanged(positionMs: Int, durationMs: Int) {
        val shouldSavePosition = playbackLifecycle.syncPlayerPosition(
            currentPositionMs = positionMs,
            durationMs = durationMs,
        )
        if (shouldSavePosition) {
            playbackPersistence.savePosition(currentTime)
        }
        updateCue(currentTime, forceDisplay = true)
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
        cueDisplayActionDispatcher.apply(action)
    }

    private fun restoreTemporaryPlaybackPositionIfNeeded() {
        val returnPosition = playbackState.restoreTemporaryPlaybackPositionIfNeeded() ?: return
        playbackLifecycle.seekTo((returnPosition * 1000.0).toInt())
        updateCue(returnPosition)
    }

    private fun teardownPlayer(clearCue: Boolean) {
        pausePlayback(restoreTemporaryPosition = true)
        playbackLifecycle.releaseEngine()
        audioAvailability.markAudioUnavailable()
        if (clearCue) applyCueDisplayAction(cueDisplay.clear())
    }
}
