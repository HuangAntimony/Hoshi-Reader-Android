package moe.antimony.hoshi.features.sasayaki

class SasayakiAudioRestoreCallbacksCoordinator(
    private val playbackLifecycle: SasayakiPlaybackLifecycleController,
    private val playbackCommands: SasayakiPlaybackCommandCoordinator,
) {
    fun build(
        updateMediaSession: () -> Unit,
        handlePrepared: (Int) -> Unit,
        handleSeekComplete: () -> Unit,
        handleError: (Throwable) -> Unit,
        startPlayback: () -> Unit,
        pausePlayback: () -> Unit,
        previousCue: () -> Unit,
        nextCue: () -> Unit,
        isPlaying: () -> Boolean,
    ): SasayakiAudioRestoreCallbacks =
        SasayakiAudioRestoreCallbacks(
            onPrepared = handlePrepared,
            onCompletion = {
                playbackLifecycle.markCompleted(updateMediaSession = updateMediaSession)
            },
            onSeekComplete = handleSeekComplete,
            onError = handleError,
            onPlay = startPlayback,
            onPause = pausePlayback,
            onSkipToPrevious = previousCue,
            onSkipToNext = nextCue,
            onSeekTo = { positionMs ->
                playbackCommands.mediaSessionSeek(
                    positionMs = positionMs,
                    isPlaying = isPlaying(),
                )
            },
        )
}
