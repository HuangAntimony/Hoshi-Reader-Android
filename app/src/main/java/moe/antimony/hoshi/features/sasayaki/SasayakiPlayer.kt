package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
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
    private val appContext = context.applicationContext
    private val audioRepository = SasayakiAudioRepository(bookRoot)
    private val handler = Handler(Looper.getMainLooper())
    private val timeline = CueTimeline(matchData)
    private var mediaPlayer: MediaPlayer? = null
    private var lastSavedSecond = -1
    private var currentCue: SasayakiMatch? = null
    private var hasPlayedOnce = false

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

    fun importAudio(audioFileName: String) {
        teardownPlayer(clearCue = false)
        playback = playback.copy(audioFileName = audioFileName, audioUri = null)
        savePlayback()
        restoreAudio()
    }

    fun togglePlayback() {
        if (isPlaying) pausePlayback() else startPlayback()
    }

    fun pausePlayback() {
        mediaPlayer?.pause()
        isPlaying = false
        handler.removeCallbacks(tickRunnable)
    }

    fun nextCue() {
        val next = timeline.nextCue(after = currentCue?.startTime ?: currentTime - delay) ?: return
        seek(next + delay, startPlayback = isPlaying)
    }

    fun previousCue() {
        val previous = timeline.previousCue(before = currentCue?.startTime ?: max(0.0, currentTime - delay)) ?: 0.0
        seek(previous + delay, startPlayback = isPlaying)
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
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    private fun seek(seconds: Double, startPlayback: Boolean) {
        val player = mediaPlayer ?: return
        player.seekTo((seconds * 1000.0).toInt().coerceAtLeast(0))
        currentTime = seconds
        playback = playback.copy(lastPosition = seconds)
        savePlayback()
        updateCue(seconds)
        if (startPlayback) startPlayback()
    }

    private fun restoreAudio() {
        val file = audioRepository.audioFile(playback) ?: return
        runCatching {
            val player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    this@SasayakiPlayer.isPlaying = false
                    handler.removeCallbacks(tickRunnable)
                }
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
        val player = mediaPlayer ?: return
        currentTime = player.currentPosition.toDouble() / 1000.0
        duration = player.duration.coerceAtLeast(0).toDouble() / 1000.0
        val second = currentTime.toInt()
        if (second != lastSavedSecond) {
            lastSavedSecond = second
            playback = playback.copy(lastPosition = currentTime)
            savePlayback()
        }
        updateCue(currentTime)
    }

    private fun updateCue(time: Double) {
        if (!hasAudio || !hasMatch) return
        val cue = timeline.cueAt(time - delay)
        if (cue == null) {
            clearCue()
            return
        }
        if (cue.id == currentCue?.id) return
        if (cue.chapterIndex == getCurrentChapterIndex()) {
            currentCue = cue
            onCue(cue, autoScroll && hasPlayedOnce)
        } else if (autoScroll && hasPlayedOnce) {
            currentCue = null
            onClearCue()
            onLoadChapter(cue.chapterIndex)
        } else {
            clearCue()
        }
    }

    private fun clearCue() {
        if (currentCue == null) return
        currentCue = null
        onClearCue()
    }

    private fun savePlayback() {
        bookStorage.saveSasayakiPlayback(bookRoot, playback)
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
}
