package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch

class SasayakiPlaybackCommandCoordinator(
    private val playbackState: SasayakiPlaybackStateCoordinator,
    private val playbackLifecycle: SasayakiPlaybackLifecycleController,
    private val cueNavigation: SasayakiCueNavigationController,
) {
    fun toggle(
        isPlaying: Boolean,
        startPlayback: () -> Unit,
        pausePlayback: () -> Unit,
    ) {
        if (isPlaying) pausePlayback() else startPlayback()
    }

    fun start(
        rate: Float,
        markPlayedOnce: () -> Unit,
        afterMarkedPlaying: () -> Unit,
    ) {
        playbackLifecycle.start(
            rate = rate,
            markPlayedOnce = markPlayedOnce,
            afterMarkedPlaying = afterMarkedPlaying,
        )
    }

    fun pause(
        restoreTemporaryPosition: Boolean,
        updateMediaSession: () -> Unit,
        restoreTemporaryPositionIfNeeded: () -> Unit,
    ) {
        playbackLifecycle.pause(
            restoreTemporaryPosition = restoreTemporaryPosition,
            updateMediaSession = updateMediaSession,
            restoreTemporaryPositionIfNeeded = restoreTemporaryPositionIfNeeded,
        )
    }

    fun nextCue(
        currentTime: Double,
        delay: Double,
        isPlaying: Boolean,
    ) {
        val next = cueNavigation.nextCueSeekTime(
            currentTime = currentTime,
            delay = delay,
        ) ?: return
        playbackState.clearStopPlaybackTime()
        seek(next, startPlayback = isPlaying, revealCue = true)
    }

    fun previousCue(
        currentTime: Double,
        delay: Double,
        isPlaying: Boolean,
    ) {
        val previous = cueNavigation.previousCueSeekTime(
            currentTime = currentTime,
            delay = delay,
        )
        playbackState.clearStopPlaybackTime()
        seek(previous, startPlayback = isPlaying, revealCue = true)
    }

    fun playCue(
        cue: SasayakiMatch,
        stop: Boolean,
        isPlaying: Boolean,
        lastPosition: Double,
        delay: Double,
        pauseWithoutRestore: () -> Unit,
    ) {
        playbackState.clearStopPlaybackTime()
        if (isPlaying) pauseWithoutRestore()
        playbackState.setTemporaryPlaybackReturnPosition(if (stop) lastPosition else null)
        playbackState.setStopPlaybackTime(if (stop) cue.endTime + delay else null)
        seek(
            seconds = cue.startTime + delay,
            startPlayback = true,
            updateCue = false,
            savePosition = !stop,
            displayCue = cue,
        )
    }

    fun mediaSessionSeek(
        positionMs: Long,
        isPlaying: Boolean,
    ) {
        playbackState.clearStopPlaybackTime()
        seek(positionMs.toDouble() / 1000.0, startPlayback = isPlaying)
    }

    fun seek(
        seconds: Double,
        startPlayback: Boolean,
        updateCue: Boolean = true,
        savePosition: Boolean = true,
        displayCue: SasayakiMatch? = null,
        revealCue: Boolean = false,
    ) {
        playbackLifecycle.beginSeek(
            seconds = seconds,
            startPlayback = startPlayback,
            updateCue = updateCue,
            savePosition = savePosition,
            displayCue = displayCue,
            revealCue = revealCue,
        )
    }
}
