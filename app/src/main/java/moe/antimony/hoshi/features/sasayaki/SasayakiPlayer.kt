package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import moe.antimony.hoshi.epub.BookStorage
import java.io.File
import kotlin.math.max

class SasayakiPlayer(
    context: Context,
    private val bookRoot: File,
    private val bookStorage: BookStorage,
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
    private val audioRepository = SasayakiAudioRepository(bookRoot)
    private val handler = Handler(Looper.getMainLooper())
    private val timeline = CueTimeline(matchData)
    private var mediaPlayer: MediaPlayer? = null
    private var lastSavedSecond = -1
    private var currentCue: SasayakiMatch? = null
    private var hasPlayedOnce = false
    private var stopPlaybackTime: Double? = null
    private var temporaryPlaybackReturnPosition: Double? = null
    private var pendingSeek: PendingSeek? = null

    var playback by mutableStateOf(bookStorage.loadSasayakiPlayback(bookRoot) ?: SasayakiPlaybackData(lastPosition = 0.0))
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
        get() = when {
            playback.audioFileName != null -> "Copied to app storage. The original audiobook file can be deleted."
            playback.audioUri != null -> "Linked to the external audiobook file. Keep the original file available."
            else -> "Select an .mp3 or .m4b audiobook"
        }

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
            mediaPlayer?.playbackParams = playbackParams(value)
        }
        savePlayback()
    }

    fun importAudio(audioUri: Uri, copiedAudioFileName: String? = null) {
        teardownPlayer(clearCue = false)
        playback = playback.copy(
            audioUri = if (copiedAudioFileName == null) audioUri.toString() else null,
            audioFileName = copiedAudioFileName,
        )
        savePlayback()
        restoreAudio()
    }

    fun clearAudio() {
        val previousAudioUri = playback.audioUri
        audioRepository.deleteAudio(playback)
        teardownPlayer(clearCue = true)
        previousAudioUri?.let { uriString ->
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uriString),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
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
        mediaPlayer?.pause()
        isPlaying = false
        handler.removeCallbacks(tickRunnable)
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
        val player = mediaPlayer ?: return
        player.playbackParams = playbackParams(rate)
        player.start()
        hasPlayedOnce = true
        isPlaying = true
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
        val player = mediaPlayer ?: return
        pendingSeek = PendingSeek(
            seconds = seconds,
            startPlayback = startPlayback,
            updateCue = updateCue,
            savePosition = savePosition,
            displayCue = displayCue,
        )
        handler.removeCallbacks(tickRunnable)
        if (isPlaying) {
            player.pause()
            isPlaying = false
        }
        player.seekTo((seconds * 1000.0).toInt().coerceAtLeast(0))
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
    }

    private fun restoreAudio() {
        val uri = playback.audioUri?.let { Uri.parse(it) }
        val file = if (uri == null) audioRepository.audioFile(playback) else null
        if (uri == null && file == null) return
        runCatching {
            val player = MediaPlayer().apply {
                setAudioAttributes(audioAttributes())
                if (uri != null) {
                    setDataSource(appContext, uri)
                } else {
                    setDataSource(requireNotNull(file).absolutePath)
                }
                setOnCompletionListener {
                    this@SasayakiPlayer.isPlaying = false
                    handler.removeCallbacks(tickRunnable)
                }
                setOnSeekCompleteListener { handleSeekComplete() }
                prepare()
                seekTo((playback.lastPosition * 1000.0).toInt().coerceAtLeast(0))
            }
            mediaPlayer = player
            duration = player.duration.coerceAtLeast(0).toDouble() / 1000.0
            hasAudio = true
            errorMessage = null
            updateCue(currentTime)
        }.onFailure { error ->
            errorMessage = error.localizedMessage ?: "Unable to load audiobook."
            hasAudio = false
        }
    }

    private fun tick() {
        if (pendingSeek != null) return
        val player = mediaPlayer ?: return
        currentTime = player.currentPosition.toDouble() / 1000.0
        duration = player.duration.coerceAtLeast(0).toDouble() / 1000.0
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
        bookStorage.saveSasayakiPlayback(bookRoot, playback)
    }

    private fun restoreTemporaryPlaybackPositionIfNeeded() {
        val returnPosition = temporaryPlaybackReturnPosition ?: return
        temporaryPlaybackReturnPosition = null
        mediaPlayer?.seekTo((returnPosition * 1000.0).toInt().coerceAtLeast(0))
        currentTime = returnPosition
        lastSavedSecond = returnPosition.toInt()
        updateCue(returnPosition)
    }

    private fun teardownPlayer(clearCue: Boolean) {
        pausePlayback()
        mediaPlayer?.release()
        mediaPlayer = null
        hasAudio = false
        if (clearCue) clearCue()
    }

    private fun playbackParams(speed: Float): PlaybackParams =
        PlaybackParams().setSpeed(speed).setPitch(1f)

    private fun audioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
}
