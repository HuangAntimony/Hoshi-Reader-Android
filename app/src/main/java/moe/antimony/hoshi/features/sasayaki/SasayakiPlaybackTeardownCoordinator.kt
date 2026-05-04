package moe.antimony.hoshi.features.sasayaki

class SasayakiPlaybackTeardownCoordinator(
    private val playbackLifecycle: SasayakiPlaybackLifecycleController,
    private val mediaSessionHandle: SasayakiMediaSessionHandleCoordinator,
    private val audioAvailability: SasayakiAudioAvailabilityState,
    private val cueDisplay: SasayakiCueDisplayCoordinator,
) {
    fun teardown(
        clearCue: Boolean,
        pausePlayback: () -> Unit,
        applyCueDisplayAction: (SasayakiCueDisplayAction) -> Unit,
    ) {
        pausePlayback()
        playbackLifecycle.releaseEngine()
        mediaSessionHandle.releaseAndClear()
        audioAvailability.markAudioUnavailable()
        if (clearCue) applyCueDisplayAction(cueDisplay.clear())
    }
}
