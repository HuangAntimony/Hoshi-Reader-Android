package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch

import android.os.Handler

interface SasayakiTickScheduler {
    fun postTick()
    fun stopTicking()
}

class HandlerSasayakiTickScheduler(
    private val handler: Handler,
    private val tickRunnable: Runnable,
) : SasayakiTickScheduler {
    override fun postTick() {
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    override fun stopTicking() {
        handler.removeCallbacks(tickRunnable)
    }
}

class SasayakiPlaybackLifecycleController(
    private val playbackState: SasayakiPlaybackStateCoordinator,
    private val tickScheduler: SasayakiTickScheduler,
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
        revealCue: Boolean = false,
    ): Boolean {
        val engine = engine ?: return false
        playbackState.beginSeek(
            seconds = seconds,
            startPlayback = startPlayback,
            updateCue = updateCue,
            savePosition = savePosition,
            displayCue = displayCue,
            revealCue = revealCue,
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
        tickScheduler.postTick()
    }

    private fun stopTicking() {
        tickScheduler.stopTicking()
    }
}
