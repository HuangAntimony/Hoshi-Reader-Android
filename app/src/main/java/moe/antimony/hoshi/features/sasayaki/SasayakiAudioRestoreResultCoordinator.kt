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
        handlePrepared(
            durationMs = result.durationMs,
            currentTime = currentTime,
            updateCue = updateCue,
            updateMediaSession = updateMediaSession,
        )
    }

    fun handlePrepared(
        durationMs: Int,
        currentTime: Double,
        updateCue: (Double) -> Unit,
        updateMediaSession: () -> Unit,
    ) {
        playbackState.updateDuration(durationMs)
        audioAvailability.markRestoreSucceeded()
        updateCue(currentTime)
        updateMediaSession()
    }
}
