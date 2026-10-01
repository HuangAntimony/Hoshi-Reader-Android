package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.sasayaki.SasayakiCueRevealSource

internal data class ReaderPlaybackRevealResult(val progress: Double?, val countStatistics: Boolean)

internal data class ReaderPlaybackTargetRestoreResult(
    val restoredCue: Boolean,
    val firstStopWasShown: Boolean = false,
    val firstStopProgress: Double? = null,
    val remainingStops: List<ReaderSasayakiMediaStop> = emptyList(),
)

internal interface ReaderPlaybackPresentationActions {
    suspend fun stopsBeforeCue(): List<ReaderSasayakiMediaStop>
    suspend fun stopsToChapterEnd(): List<ReaderSasayakiMediaStop>
    suspend fun showStops(stops: List<ReaderSasayakiMediaStop>, countStatistics: Boolean = true)
    suspend fun holdVisibleStop(progress: Double?)
    suspend fun loadChapter(chapterIndex: Int): Boolean
    suspend fun restoreTarget(inspectMedia: Boolean, fromChapter: Int): ReaderPlaybackTargetRestoreResult?
    suspend fun reveal(reveal: Boolean): Double?
}

/** One ordered Reader traversal for audiobook cues and synthesized sentences. */
internal suspend fun revealReaderPlaybackWithMediaStops(
    chapterIndex: Int,
    reveal: Boolean,
    source: SasayakiCueRevealSource,
    imageHoldMillis: Long,
    currentChapter: () -> Int,
    actions: ReaderPlaybackPresentationActions,
    canPresent: () -> Boolean = { true },
): ReaderPlaybackRevealResult {
    val abandoned = ReaderPlaybackRevealResult(null, false)
    if (!canPresent()) return abandoned
    if (!reveal) return ReaderPlaybackRevealResult(actions.reveal(false), true)
    val initialChapter = currentChapter()
    var countStatistics = true
    if (chapterIndex == initialChapter) {
        if (readerSasayakiShouldHoldMediaStopsBeforeCue(initialChapter, chapterIndex, source, imageHoldMillis)) {
            actions.showStops(actions.stopsBeforeCue())
        }
    } else {
        val inspectMedia = readerSasayakiTargetChapterMediaPolicy(initialChapter, chapterIndex, source, imageHoldMillis) ==
            ReaderSasayakiTargetChapterMediaPolicy.InspectStopsWithoutPreHold
        if (inspectMedia) {
            actions.showStops(actions.stopsToChapterEnd())
            for (chapter in (initialChapter + 1) until chapterIndex) {
                if (!canPresent() || !actions.loadChapter(chapter)) return abandoned
                actions.showStops(actions.stopsToChapterEnd())
            }
        }
        if (!canPresent()) return abandoned
        val restored = actions.restoreTarget(inspectMedia, initialChapter) ?: return abandoned
        if (!canPresent() || restored.restoredCue) return abandoned
        countStatistics = false
        if (restored.firstStopWasShown) actions.holdVisibleStop(restored.firstStopProgress)
        actions.showStops(restored.remainingStops, countStatistics = false)
    }
    if (!canPresent()) return abandoned
    return ReaderPlaybackRevealResult(actions.reveal(true), countStatistics)
}
