package moe.antimony.hoshi.features.sasayaki

class SasayakiCueUpdateCoordinator(
    private val playbackEvents: SasayakiPlaybackEventCoordinator,
    private val cuePresentation: SasayakiCuePresentationState,
    private val getCurrentChapterIndex: () -> Int,
) {
    fun update(
        hasAudio: Boolean,
        hasMatch: Boolean,
        time: Double,
        delay: Double,
        forceDisplay: Boolean,
        applyCueDisplayAction: (SasayakiCueDisplayAction) -> Unit,
    ) {
        playbackEvents.updateCue(
            hasAudio = hasAudio,
            hasMatch = hasMatch,
            time = time,
            delay = delay,
            currentChapterIndex = getCurrentChapterIndex(),
            autoScroll = cuePresentation.autoScroll,
            hasPlayedOnce = cuePresentation.hasPlayedOnce,
            forceDisplay = forceDisplay,
            applyCueDisplayAction = applyCueDisplayAction,
        )
    }
}
