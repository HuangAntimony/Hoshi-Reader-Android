package moe.antimony.hoshi.features.sasayaki

class SasayakiSeekCompleteCoordinator(
    private val playbackEvents: SasayakiPlaybackEventCoordinator,
    private val cuePresentation: SasayakiCuePresentationState,
    private val getCurrentChapterIndex: () -> Int,
) {
    fun handle(
        hasAudio: Boolean,
        hasMatch: Boolean,
        delay: Double,
        startPlayback: () -> Unit,
        applyCueDisplayAction: (SasayakiCueDisplayAction) -> Unit,
    ) {
        playbackEvents.handleSeekComplete(
            hasAudio = hasAudio,
            hasMatch = hasMatch,
            delay = delay,
            currentChapterIndex = getCurrentChapterIndex(),
            autoScroll = cuePresentation.autoScroll,
            hasPlayedOnce = cuePresentation.hasPlayedOnce,
            startPlayback = startPlayback,
            applyCueDisplayAction = applyCueDisplayAction,
        )
    }
}
