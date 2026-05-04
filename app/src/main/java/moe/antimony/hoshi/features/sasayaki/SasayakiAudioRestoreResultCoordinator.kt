package moe.antimony.hoshi.features.sasayaki

class SasayakiAudioRestoreResultCoordinator(
    private val mediaSessionHandle: SasayakiMediaSessionHandleCoordinator,
    private val playbackState: SasayakiPlaybackStateCoordinator,
    private val audioAvailability: SasayakiAudioAvailabilityState,
) {
    fun handleFailure(error: Throwable) {
        audioAvailability.markRestoreFailed(error)
    }

    fun handleSuccess(
        result: SasayakiAudioRestoreResult,
        currentTime: Double,
        updateCue: (Double) -> Unit,
        updateMediaSession: () -> Unit,
    ) {
        mediaSessionHandle.replace(result.mediaSession)
        playbackState.updateDuration(result.durationMs)
        audioAvailability.markRestoreSucceeded()
        updateCue(currentTime)
        updateMediaSession()
    }
}
