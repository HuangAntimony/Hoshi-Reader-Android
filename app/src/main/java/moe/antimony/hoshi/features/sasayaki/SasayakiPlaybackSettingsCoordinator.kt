package moe.antimony.hoshi.features.sasayaki

class SasayakiPlaybackSettingsCoordinator(
    private val playbackPersistence: SasayakiPlaybackPersistenceState,
    private val playbackLifecycle: SasayakiPlaybackLifecycleController,
) {
    fun setDelay(
        value: Double,
        currentTime: Double,
        updateCue: (Double) -> Unit,
    ) {
        playbackPersistence.setDelay(value)
        updateCue(currentTime)
    }

    fun setRate(value: Float) {
        playbackPersistence.setRate(value)
        playbackLifecycle.setRateIfPlaying(value)
    }
}
