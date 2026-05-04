package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class SasayakiPendingSeek(
    val seconds: Double,
    val startPlayback: Boolean,
    val updateCue: Boolean,
    val savePosition: Boolean,
    val displayCue: SasayakiMatch? = null,
)

data class SasayakiPlaybackTickUpdate(
    val shouldSavePosition: Boolean,
    val shouldStopPlayback: Boolean,
)

class SasayakiPlaybackStateCoordinator(initialPosition: Double) {
    var currentTime by mutableStateOf(initialPosition)
        private set
    var duration by mutableStateOf(0.0)
        private set
    var isPlaying by mutableStateOf(false)
        private set

    private var lastSavedSecond = -1
    private var stopPlaybackTime: Double? = null
    private var temporaryPlaybackReturnPosition: Double? = null
    private var pendingSeek: SasayakiPendingSeek? = null

    val hasPendingSeek: Boolean
        get() = pendingSeek != null

    fun markPlaying() {
        isPlaying = true
    }

    fun markPaused() {
        isPlaying = false
    }

    fun markCompleted() {
        isPlaying = false
    }

    fun clearStopPlaybackTime() {
        stopPlaybackTime = null
    }

    fun setStopPlaybackTime(value: Double?) {
        stopPlaybackTime = value
    }

    fun setTemporaryPlaybackReturnPosition(value: Double?) {
        temporaryPlaybackReturnPosition = value
    }

    fun beginSeek(
        seconds: Double,
        startPlayback: Boolean,
        updateCue: Boolean,
        savePosition: Boolean,
        displayCue: SasayakiMatch?,
    ) {
        pendingSeek = SasayakiPendingSeek(
            seconds = seconds,
            startPlayback = startPlayback,
            updateCue = updateCue,
            savePosition = savePosition,
            displayCue = displayCue,
        )
    }

    fun completeSeek(): SasayakiPendingSeek? {
        val seek = pendingSeek ?: return null
        pendingSeek = null
        currentTime = seek.seconds
        return seek
    }

    fun updateDuration(durationMs: Int) {
        duration = durationMs.coerceAtLeast(0).toDouble() / 1000.0
    }

    fun updateTick(currentPositionMs: Int, durationMs: Int): SasayakiPlaybackTickUpdate {
        currentTime = currentPositionMs.toDouble() / 1000.0
        updateDuration(durationMs)

        val second = currentTime.toInt()
        val shouldSavePosition = temporaryPlaybackReturnPosition == null && second != lastSavedSecond
        if (shouldSavePosition) {
            lastSavedSecond = second
        }

        val shouldStopPlayback = stopPlaybackTime?.let { stopTime ->
            currentTime >= stopTime && isPlaying
        } == true
        if (shouldStopPlayback) {
            stopPlaybackTime = null
        }
        return SasayakiPlaybackTickUpdate(
            shouldSavePosition = shouldSavePosition,
            shouldStopPlayback = shouldStopPlayback,
        )
    }

    fun restoreTemporaryPlaybackPositionIfNeeded(): Double? {
        val returnPosition = temporaryPlaybackReturnPosition ?: return null
        temporaryPlaybackReturnPosition = null
        currentTime = returnPosition
        lastSavedSecond = returnPosition.toInt()
        return returnPosition
    }

    fun clearAudioState() {
        currentTime = 0.0
        duration = 0.0
        isPlaying = false
        pendingSeek = null
        stopPlaybackTime = null
        temporaryPlaybackReturnPosition = null
        lastSavedSecond = -1
    }
}
