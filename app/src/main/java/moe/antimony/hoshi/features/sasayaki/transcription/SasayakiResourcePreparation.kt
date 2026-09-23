package moe.antimony.hoshi.features.sasayaki.transcription

import java.io.File
import java.io.IOException

/** One consent decision and one byte-weighted progress bar for all missing resources. */
internal suspend fun prepareSasayakiResources(
    stores: List<SasayakiModelStore>,
    onDownloadRequired: suspend (Long) -> Unit,
    onProgress: suspend (Double) -> Unit,
): List<File> {
    val missing = stores.map { it.missingBytes() }
    val total = missing.sum()
    if (total > 0) onDownloadRequired(total)
    var completed = 0L
    return stores.mapIndexed { index, store ->
        store.ensure({ bytes ->
            // A cache changing during the confirmation must not silently expand consent.
            if (bytes > missing[index]) throw IOException("Transcription resources changed during preparation")
        }) { progress ->
            if (total > 0) onProgress((completed + progress * missing[index]) / total)
        }.also { completed += missing[index] }
    }
}
