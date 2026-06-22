package moe.antimony.hoshi.features.sasayaki

class SasayakiPlaybackTeardownCoordinator(
    private val playbackLifecycle: SasayakiPlaybackLifecycleController,
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
        audioAvailability.markAudioUnavailable()
        if (clearCue) applyCueDisplayAction(cueDisplay.clear())
    }
}
