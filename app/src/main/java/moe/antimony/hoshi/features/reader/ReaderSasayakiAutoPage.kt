package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRevealSource

internal fun cancelReaderSasayakiAutoPage(
    job: Job?,
    clearAutoPageJob: () -> Unit,
    clearPendingRestoreCue: () -> Unit,
    resumeAfterAutoPageHold: () -> Unit,
) {
    job?.cancel()
    clearAutoPageJob()
    clearPendingRestoreCue()
    resumeAfterAutoPageHold()
}

internal fun openReaderFullscreenImage(
    sourceUrl: String,
    imageResourceForUrl: (String) -> ReaderWebResource?,
    closeLookupPopupsAndSelection: () -> Unit,
    cancelAutoPage: () -> Unit,
    showFullscreenImage: (ReaderFullscreenImage) -> Unit,
): Boolean {
    val resource = imageResourceForUrl(sourceUrl) ?: return false
    cancelAutoPage()
    closeLookupPopupsAndSelection()
    showFullscreenImage(ReaderFullscreenImage(sourceUrl, resource))
    return true
}

internal suspend fun awaitReaderSasayakiChapterReady(
    isChapterReady: () -> Boolean,
    delayFrame: suspend () -> Unit = { delay(16L) },
    attempts: Int = 300,
): Boolean {
    repeat(attempts) {
        if (isChapterReady()) return true
        delayFrame()
    }
    return isChapterReady()
}

internal fun readerSasayakiShouldHoldMediaStopsBeforeCue(
    currentChapterIndex: Int,
    cueChapterIndex: Int,
    source: SasayakiCueRevealSource,
): Boolean =
    source == SasayakiCueRevealSource.NaturalPlayback &&
        currentChapterIndex == cueChapterIndex

internal enum class ReaderSasayakiTargetChapterMediaPolicy {
    DirectRestore,
    InspectStopsWithoutPreHold,
}

internal fun readerSasayakiTargetChapterMediaPolicy(
    currentChapterIndex: Int,
    cueChapterIndex: Int,
    source: SasayakiCueRevealSource,
): ReaderSasayakiTargetChapterMediaPolicy =
    if (
        source == SasayakiCueRevealSource.NaturalPlayback &&
        cueChapterIndex > currentChapterIndex
    ) {
        ReaderSasayakiTargetChapterMediaPolicy.InspectStopsWithoutPreHold
    } else {
        ReaderSasayakiTargetChapterMediaPolicy.DirectRestore
    }
