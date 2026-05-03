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
    private data class PendingSeek(
        val seconds: Double,
        val startPlayback: Boolean,
        val updateCue: Boolean,
        val savePosition: Boolean,
        val displayCue: SasayakiMatch? = null,
    )

    private val appContext = context.applicationContext
    private val audioSourceRepository = SasayakiAudioRepository(bookRoot)
    private val handler = Handler(Looper.getMainLooper())
    private val timeline = CueTimeline(matchData)
    private var playbackEngine: SasayakiPlaybackEngine? = null
    private var mediaSession: SasayakiMediaSessionHandle? = null
    private var lastSavedSecond = -1
    private var currentCue: SasayakiMatch? = null
    private var hasPlayedOnce = false
    private var stopPlaybackTime: Double? = null
    private var temporaryPlaybackReturnPosition: Double? = null
    private var pendingSeek: PendingSeek? = null

    var playback by mutableStateOf(playbackRepository.load() ?: SasayakiPlaybackData(lastPosition = 0.0))
        private set
    var currentTime by mutableStateOf(playback.lastPosition)
        private set
    var duration by mutableStateOf(0.0)
        private set
    var isPlaying by mutableStateOf(false)
        private set
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
        currentTime = 0.0
        duration = 0.0
        hasAudio = false
        errorMessage = null
        savePlayback()
    }

    fun togglePlayback() {
        if (isPlaying) pausePlayback() else startPlayback()
    }

    fun pausePlayback(restoreTemporaryPosition: Boolean = true) {
        playbackEngine?.pause()
        isPlaying = false
        handler.removeCallbacks(tickRunnable)
        updateMediaSession()
        if (restoreTemporaryPosition) {
            restoreTemporaryPlaybackPositionIfNeeded()
        }
    }

    fun nextCue() {
        val next = timeline.nextCue(after = currentCue?.startTime ?: currentTime - delay) ?: return
        stopPlaybackTime = null
        seek(next + delay, startPlayback = isPlaying)
    }

    fun previousCue() {
        val previous = timeline.previousCue(before = currentCue?.startTime ?: max(0.0, currentTime - delay)) ?: 0.0
        stopPlaybackTime = null
        seek(previous + delay, startPlayback = isPlaying)
    }

    fun findCue(chapterIndex: Int, offset: Int): SasayakiMatch? =
        timeline.findCue(chapterIndex = chapterIndex, offset = offset)

    fun playCue(cue: SasayakiMatch, stop: Boolean) {
        stopPlaybackTime = null
        if (isPlaying) pausePlayback(restoreTemporaryPosition = false)
        temporaryPlaybackReturnPosition = if (stop) playback.lastPosition else null
        stopPlaybackTime = if (stop) cue.endTime + delay else null
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
        isPlaying = true
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
        pendingSeek = PendingSeek(
            seconds = seconds,
            startPlayback = startPlayback,
            updateCue = updateCue,
            savePosition = savePosition,
            displayCue = displayCue,
        )
        handler.removeCallbacks(tickRunnable)
        if (isPlaying) {
            engine.pause()
            isPlaying = false
        }
        engine.seekTo((seconds * 1000.0).toInt())
    }

    private fun handleSeekComplete() {
        val seek = pendingSeek ?: return
        pendingSeek = null
        currentTime = seek.seconds
        if (seek.savePosition) {
            playback = playback.copy(lastPosition = seek.seconds)
            savePlayback()
        }
        if (seek.updateCue) updateCue(seek.seconds)
        seek.displayCue?.let { cue ->
            if (cue.chapterIndex == getCurrentChapterIndex()) {
                displayCue(cue, reveal = autoScroll && (hasPlayedOnce || seek.startPlayback))
            }
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
                    this@SasayakiPlayer.isPlaying = false
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
                    stopPlaybackTime = null
                    seek(positionMs.toDouble() / 1000.0, startPlayback = isPlaying)
                },
            )
            duration = engine.durationMs.coerceAtLeast(0).toDouble() / 1000.0
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
        if (pendingSeek != null) return
        val engine = playbackEngine ?: return
        currentTime = engine.currentPositionMs.toDouble() / 1000.0
        duration = engine.durationMs.coerceAtLeast(0).toDouble() / 1000.0
        val second = currentTime.toInt()
        if (temporaryPlaybackReturnPosition == null && second != lastSavedSecond) {
            lastSavedSecond = second
            playback = playback.copy(lastPosition = currentTime)
            savePlayback()
        }
        stopPlaybackTime?.let { stopTime ->
            if (currentTime >= stopTime && isPlaying) {
                stopPlaybackTime = null
                pausePlayback()
            }
        }
        updateCue(currentTime)
        updateMediaSession()
    }

    private fun updateCue(time: Double, forceDisplay: Boolean = false) {
        if (!hasAudio || !hasMatch) return
        val cue = timeline.cueAt(time - delay)
        if (cue == null) {
            clearCue()
            return
        }
        if (!forceDisplay && cue.id == currentCue?.id) return
        if (cue.chapterIndex == getCurrentChapterIndex()) {
            displayCue(cue, autoScroll && hasPlayedOnce)
        } else if (autoScroll && hasPlayedOnce) {
            currentCue = null
            onClearCue()
            onLoadChapter(cue.chapterIndex)
        } else {
            clearCue()
        }
    }

    private fun displayCue(cue: SasayakiMatch, reveal: Boolean) {
        currentCue = cue
        onCue(cue, reveal)
    }

    private fun clearCue() {
        if (currentCue == null) return
        currentCue = null
        onClearCue()
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
        val returnPosition = temporaryPlaybackReturnPosition ?: return
        temporaryPlaybackReturnPosition = null
        playbackEngine?.seekTo((returnPosition * 1000.0).toInt())
        currentTime = returnPosition
        lastSavedSecond = returnPosition.toInt()
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
        if (clearCue) clearCue()
    }
}
