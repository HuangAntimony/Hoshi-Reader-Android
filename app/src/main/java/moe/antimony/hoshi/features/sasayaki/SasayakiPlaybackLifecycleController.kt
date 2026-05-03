package moe.antimony.hoshi.features.sasayaki

import android.os.Handler

class SasayakiPlaybackLifecycleController(
    private val playbackState: SasayakiPlaybackStateCoordinator,
    private val handler: Handler,
    private val tickRunnable: Runnable,
) {
    private var engine: SasayakiPlaybackEngine? = null

    fun attachEngine(engine: SasayakiPlaybackEngine) {
        this.engine = engine
    }

    fun setRateIfPlaying(rate: Float) {
        if (playbackState.isPlaying) {
            engine?.setRate(rate)
        }
    }

    fun start(
        rate: Float,
        markPlayedOnce: () -> Unit,
        afterMarkedPlaying: () -> Unit,
    ): Boolean {
        val engine = engine ?: return false
        engine.start(rate)
        markPlayedOnce()
        playbackState.markPlaying()
        afterMarkedPlaying()
        restartTicking()
        return true
    }

    fun pause(
        restoreTemporaryPosition: Boolean,
        updateMediaSession: () -> Unit,
        restoreTemporaryPositionIfNeeded: () -> Unit,
    ) {
        engine?.pause()
        playbackState.markPaused()
        stopTicking()
        updateMediaSession()
        if (restoreTemporaryPosition) {
            restoreTemporaryPositionIfNeeded()
        }
    }

    fun beginSeek(
        seconds: Double,
        startPlayback: Boolean,
        updateCue: Boolean,
        savePosition: Boolean,
        displayCue: SasayakiMatch?,
    ): Boolean {
        val engine = engine ?: return false
        playbackState.beginSeek(
            seconds = seconds,
            startPlayback = startPlayback,
            updateCue = updateCue,
            savePosition = savePosition,
            displayCue = displayCue,
        )
        stopTicking()
        if (playbackState.isPlaying) {
            engine.pause()
            playbackState.markPaused()
        }
        engine.seekTo((seconds * 1000.0).toInt())
        return true
    }

    fun markCompleted(updateMediaSession: () -> Unit) {
        playbackState.markCompleted()
        stopTicking()
        updateMediaSession()
    }

    fun updateTick(): SasayakiPlaybackTickUpdate? {
        if (playbackState.hasPendingSeek) return null
        val engine = engine ?: return null
        return playbackState.updateTick(
            currentPositionMs = engine.currentPositionMs,
            durationMs = engine.durationMs,
        )
    }

    fun seekTo(positionMs: Int) {
        engine?.seekTo(positionMs)
    }

    fun releaseEngine() {
        stopTicking()
        engine?.release()
        engine = null
    }

    private fun restartTicking() {
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    private fun stopTicking() {
        handler.removeCallbacks(tickRunnable)
    }
}
