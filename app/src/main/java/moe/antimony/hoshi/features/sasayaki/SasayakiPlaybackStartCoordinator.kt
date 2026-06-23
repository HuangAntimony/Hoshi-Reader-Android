package moe.antimony.hoshi.features.sasayaki

class SasayakiPlaybackStartCoordinator(
    private val playbackCommands: SasayakiPlaybackCommandCoordinator,
    private val cuePresentation: SasayakiCuePresentationState,
) {
    fun start(
        rate: Float,
        beforeStart: () -> Unit,
        currentTime: () -> Double,
        redisplayCue: (Double) -> Unit,
    ) {
        playbackCommands.start(
            rate = rate,
            beforeStart = beforeStart,
            markPlayedOnce = cuePresentation::markPlayedOnce,
            afterMarkedPlaying = {
                redisplayCue(currentTime())
            },
        )
    }
}
