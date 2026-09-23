package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRevealSource

internal const val ReaderSasayakiFullscreenImageDismissPollMillis = 50L

internal suspend fun awaitReaderSasayakiPresentationIdle(
    nativeSelectionActive: StateFlow<Boolean>? = null,
    currentJob: () -> Job?,
) {
    while (true) {
        nativeSelectionActive?.first { !it }
        val job = currentJob()?.takeUnless { it.isCompleted } ?: return
        job.join()
    }
}

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
    showFullscreenImage: (ReaderFullscreenImage) -> Unit,
): Boolean {
    val resource = imageResourceForUrl(sourceUrl) ?: return false
    closeLookupPopupsAndSelection()
    showFullscreenImage(ReaderFullscreenImage(sourceUrl, resource))
    return true
}

internal suspend fun awaitReaderSasayakiImageHold(
    imageHoldMillis: Long,
    isFullscreenImageVisible: () -> Boolean,
    delayMillis: suspend (Long) -> Unit = { delay(it) },
) {
    delayMillis(imageHoldMillis)
    while (isFullscreenImageVisible()) {
        delayMillis(ReaderSasayakiFullscreenImageDismissPollMillis)
    }
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
    imageHoldMillis: Long,
): Boolean =
    imageHoldMillis > 0L &&
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
    imageHoldMillis: Long,
): ReaderSasayakiTargetChapterMediaPolicy =
    if (
        imageHoldMillis > 0L &&
        source == SasayakiCueRevealSource.NaturalPlayback &&
        cueChapterIndex > currentChapterIndex
    ) {
        ReaderSasayakiTargetChapterMediaPolicy.InspectStopsWithoutPreHold
    } else {
        ReaderSasayakiTargetChapterMediaPolicy.DirectRestore
    }
