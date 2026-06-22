package moe.antimony.hoshi.features.sasayaki

class SasayakiPlaybackStartCoordinator(
    private val playbackCommands: SasayakiPlaybackCommandCoordinator,
    private val cuePresentation: SasayakiCuePresentationState,
) {
    fun start(
        rate: Float,
        currentTime: () -> Double,
        updateMediaSession: () -> Unit,
        redisplayCue: (Double) -> Unit,
    ) {
        playbackCommands.start(
            rate = rate,
            markPlayedOnce = cuePresentation::markPlayedOnce,
            afterMarkedPlaying = {
                updateMediaSession()
                redisplayCue(currentTime())
            },
        )
    }
}
