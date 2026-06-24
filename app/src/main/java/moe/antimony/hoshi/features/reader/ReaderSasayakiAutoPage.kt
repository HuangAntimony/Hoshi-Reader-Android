package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.Job

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
