package moe.antimony.hoshi.features.sasayaki

class SasayakiAudioRestoreCallbacksCoordinator(
    private val playbackLifecycle: SasayakiPlaybackLifecycleController,
) {
    fun build(
        handlePrepared: (Int) -> Unit,
        handleSeekComplete: () -> Unit,
        handlePlaybackActiveChanged: (Boolean) -> Unit,
        handlePositionChanged: (Int, Int) -> Unit,
        handleError: (Throwable) -> Unit,
    ): SasayakiAudioRestoreCallbacks =
        SasayakiAudioRestoreCallbacks(
            onPrepared = handlePrepared,
            onCompletion = {
                playbackLifecycle.markCompleted()
            },
            onSeekComplete = handleSeekComplete,
            onPlaybackActiveChanged = handlePlaybackActiveChanged,
            onPositionChanged = handlePositionChanged,
            onError = handleError,
        )
}
