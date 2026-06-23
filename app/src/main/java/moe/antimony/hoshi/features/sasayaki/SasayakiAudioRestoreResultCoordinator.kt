package moe.antimony.hoshi.features.sasayaki

class SasayakiAudioRestoreResultCoordinator(
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
    ) {
        handlePrepared(
            durationMs = result.durationMs,
            currentTime = currentTime,
            updateCue = updateCue,
        )
    }

    fun handlePrepared(
        durationMs: Int,
        currentTime: Double,
        updateCue: (Double) -> Unit,
    ) {
        playbackState.updateDuration(durationMs)
        audioAvailability.markRestoreSucceeded()
        updateCue(currentTime)
    }
}
