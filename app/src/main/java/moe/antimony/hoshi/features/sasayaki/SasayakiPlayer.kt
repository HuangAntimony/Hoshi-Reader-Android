package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.math.max

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
    private val initialPlayback = playbackRepository.load() ?: SasayakiPlaybackData(lastPosition = 0.0)
    private val audioSourceRepository = SasayakiAudioRepository(bookRoot)
    private val handler = Handler(Looper.getMainLooper())
    private val timeline = CueTimeline(matchData)
    private val playbackState = SasayakiPlaybackStateCoordinator(
        initialPosition = initialPlayback.lastPosition,
    )
    private val cueDisplay = SasayakiCueDisplayCoordinator()
    private var playbackEngine: SasayakiPlaybackEngine? = null
    private var mediaSession: SasayakiMediaSessionHandle? = null
    private var hasPlayedOnce = false

    var playback by mutableStateOf(initialPlayback)
        private set
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
        get() = audioSourceRepository.storageSummary(playback)

    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 125L)
        }
    }

    init {
        restoreAudio()
    }

    fun setDelay(value: Double) {
        playback = playback.copy(delay = value)
        savePlayback()
        updateCue(currentTime)
    }

    fun setRate(value: Float) {
        playback = playback.copy(rate = value)
        if (isPlaying) {
            playbackEngine?.setRate(value)
        }
        updateMediaSession()
        savePlayback()
    }

    fun importAudio(audioUri: Uri, copiedAudioFileName: String? = null) {
        teardownPlayer(clearCue = false)
        playback = audioSourceRepository.importedPlayback(playback, audioUri, copiedAudioFileName)
        savePlayback()
        restoreAudio()
    }

    fun clearAudio() {
        audioSourceRepository.clearAudioSource(playback, appContext.contentResolver)
        teardownPlayer(clearCue = true)
        playback = playback.copy(
            lastPosition = 0.0,
            audioUri = null,
            audioFileName = null,
        )
        playbackState.clearAudioState()
        hasAudio = false
        errorMessage = null
        savePlayback()
    }

    fun togglePlayback() {
        if (isPlaying) pausePlayback() else startPlayback()
    }

    fun pausePlayback(restoreTemporaryPosition: Boolean = true) {
        playbackEngine?.pause()
        playbackState.markPaused()
        handler.removeCallbacks(tickRunnable)
        updateMediaSession()
        if (restoreTemporaryPosition) {
            restoreTemporaryPlaybackPositionIfNeeded()
        }
    }

    fun nextCue() {
        val next = timeline.nextCue(after = cueDisplay.currentCueStartTime ?: currentTime - delay) ?: return
        playbackState.clearStopPlaybackTime()
        seek(next + delay, startPlayback = isPlaying)
    }

    fun previousCue() {
        val previous = timeline.previousCue(before = cueDisplay.currentCueStartTime ?: max(0.0, currentTime - delay)) ?: 0.0
        playbackState.clearStopPlaybackTime()
        seek(previous + delay, startPlayback = isPlaying)
    }

    fun findCue(chapterIndex: Int, offset: Int): SasayakiMatch? =
        timeline.findCue(chapterIndex = chapterIndex, offset = offset)

    fun playCue(cue: SasayakiMatch, stop: Boolean) {
        playbackState.clearStopPlaybackTime()
        if (isPlaying) pausePlayback(restoreTemporaryPosition = false)
        playbackState.setTemporaryPlaybackReturnPosition(if (stop) playback.lastPosition else null)
        playbackState.setStopPlaybackTime(if (stop) cue.endTime + delay else null)
        seek(
            seconds = cue.startTime + delay,
            startPlayback = true,
            updateCue = false,
            savePosition = !stop,
            displayCue = cue,
        )
    }

    fun release() {
        teardownPlayer(clearCue = true)
    }

    private fun startPlayback() {
        val engine = playbackEngine ?: return
        engine.start(rate)
        hasPlayedOnce = true
        playbackState.markPlaying()
        updateMediaSession()
        mediaSession?.activate()
        updateCue(currentTime, forceDisplay = true)
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    private fun seek(
        seconds: Double,
        startPlayback: Boolean,
        updateCue: Boolean = true,
        savePosition: Boolean = true,
        displayCue: SasayakiMatch? = null,
    ) {
        val engine = playbackEngine ?: return
        playbackState.beginSeek(
            seconds = seconds,
            startPlayback = startPlayback,
            updateCue = updateCue,
            savePosition = savePosition,
            displayCue = displayCue,
        )
        handler.removeCallbacks(tickRunnable)
        if (isPlaying) {
            engine.pause()
            playbackState.markPaused()
        }
        engine.seekTo((seconds * 1000.0).toInt())
    }

    private fun handleSeekComplete() {
        val seek = playbackState.completeSeek() ?: return
        if (seek.savePosition) {
            playback = playback.copy(lastPosition = seek.seconds)
            savePlayback()
        }
        if (seek.updateCue) updateCue(seek.seconds)
        seek.displayCue?.let { cue ->
            applyCueDisplayAction(
                cueDisplay.displaySelectedCue(
                    cue = cue,
                    currentChapterIndex = getCurrentChapterIndex(),
                    reveal = autoScroll && (hasPlayedOnce || seek.startPlayback),
                ),
            )
        }
        if (seek.startPlayback) startPlayback()
        updateMediaSession()
    }

    private fun restoreAudio() {
        val source = audioSourceRepository.playbackSource(playback) ?: return
        runCatching {
            val engine = AndroidSasayakiPlaybackEngine.prepare(
                context = appContext,
                source = source,
                startPositionMs = (playback.lastPosition * 1000.0).toInt(),
                onCompletion = {
                    playbackState.markCompleted()
                    handler.removeCallbacks(tickRunnable)
                    updateMediaSession()
                },
                onSeekComplete = ::handleSeekComplete,
            )
            playbackEngine = engine
            mediaSession?.release()
            mediaSession = AndroidSasayakiMediaSessionHandle(
                context = appContext,
                title = bookTitle ?: bookRoot.name,
                artworkFile = bookCoverFile,
                onPlay = ::startPlayback,
                onPause = { pausePlayback() },
                onSkipToPrevious = ::previousCue,
                onSkipToNext = ::nextCue,
                onSeekTo = { positionMs ->
                    playbackState.clearStopPlaybackTime()
                    seek(positionMs.toDouble() / 1000.0, startPlayback = isPlaying)
                },
            )
            playbackState.updateDuration(engine.durationMs)
            hasAudio = true
            errorMessage = null
            updateCue(currentTime)
            updateMediaSession()
        }.onFailure { error ->
            errorMessage = error.localizedMessage ?: "Unable to load audiobook."
            hasAudio = false
        }
    }

    private fun tick() {
        if (playbackState.hasPendingSeek) return
        val engine = playbackEngine ?: return
        val tick = playbackState.updateTick(
            currentPositionMs = engine.currentPositionMs,
            durationMs = engine.durationMs,
        )
        if (tick.shouldSavePosition) {
            playback = playback.copy(lastPosition = currentTime)
            savePlayback()
        }
        if (tick.shouldStopPlayback) {
            pausePlayback()
        }
        updateCue(currentTime)
        updateMediaSession()
    }

    private fun updateCue(time: Double, forceDisplay: Boolean = false) {
        if (!hasAudio || !hasMatch) return
        val cue = timeline.cueAt(time - delay)
        applyCueDisplayAction(
            cueDisplay.update(
                cue = cue,
                currentChapterIndex = getCurrentChapterIndex(),
                autoScroll = autoScroll,
                hasPlayedOnce = hasPlayedOnce,
                forceDisplay = forceDisplay,
            ),
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

    private fun savePlayback() {
        playbackRepository.save(playback)
    }

    private fun updateMediaSession() {
        mediaSession?.update(
            isPlaying = isPlaying,
            currentTimeMs = (currentTime * 1000.0).toLong(),
            durationMs = (duration * 1000.0).toLong(),
            rate = rate,
        )
    }

    private fun restoreTemporaryPlaybackPositionIfNeeded() {
        val returnPosition = playbackState.restoreTemporaryPlaybackPositionIfNeeded() ?: return
        playbackEngine?.seekTo((returnPosition * 1000.0).toInt())
        updateCue(returnPosition)
        updateMediaSession()
    }

    private fun teardownPlayer(clearCue: Boolean) {
        pausePlayback()
        playbackEngine?.release()
        playbackEngine = null
        mediaSession?.release()
        mediaSession = null
        hasAudio = false
        if (clearCue) applyCueDisplayAction(cueDisplay.clear())
    }
}
