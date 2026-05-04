package moe.antimony.hoshi.features.sasayaki

class SasayakiAudioRestoreCallbacksCoordinator(
    private val playbackLifecycle: SasayakiPlaybackLifecycleController,
    private val playbackCommands: SasayakiPlaybackCommandCoordinator,
) {
    fun build(
        updateMediaSession: () -> Unit,
        handleSeekComplete: () -> Unit,
        startPlayback: () -> Unit,
        pausePlayback: () -> Unit,
        previousCue: () -> Unit,
        nextCue: () -> Unit,
        isPlaying: () -> Boolean,
    ): SasayakiAudioRestoreCallbacks =
        SasayakiAudioRestoreCallbacks(
            onCompletion = {
                playbackLifecycle.markCompleted(updateMediaSession = updateMediaSession)
            },
            onSeekComplete = handleSeekComplete,
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
