package moe.antimony.hoshi.features.sasayaki

class SasayakiAudioRestoreCallbacksCoordinator(
    private val playbackLifecycle: SasayakiPlaybackLifecycleController,
) {
    fun build(
        updateMediaSession: () -> Unit,
        handlePrepared: (Int) -> Unit,
        handleSeekComplete: () -> Unit,
        handleError: (Throwable) -> Unit,
    ): SasayakiAudioRestoreCallbacks =
        SasayakiAudioRestoreCallbacks(
            onPrepared = handlePrepared,
            onCompletion = {
                playbackLifecycle.markCompleted(updateMediaSession = updateMediaSession)
            },
            onSeekComplete = handleSeekComplete,
            onError = handleError,
        )
}
