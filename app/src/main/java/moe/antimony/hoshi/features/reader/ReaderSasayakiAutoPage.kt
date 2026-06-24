package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

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
