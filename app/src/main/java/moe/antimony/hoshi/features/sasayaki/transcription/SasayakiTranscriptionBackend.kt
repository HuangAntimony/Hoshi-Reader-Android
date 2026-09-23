package moe.antimony.hoshi.features.sasayaki.transcription

import moe.antimony.hoshi.features.sasayaki.SasayakiToken

data class SasayakiTranscriptionBatch(
    val tokens: List<SasayakiToken>,
    /** Original audio seconds safely processed, including silence. */
    val through: Double,
)

interface SasayakiTranscriptionBackend {
    suspend fun duration(source: String): Double

    suspend fun transcribe(
        source: String,
        from: Double,
        onDownloadRequired: suspend (Long) -> Unit,
        onDownload: suspend (Double) -> Unit,
        onBatch: suspend (SasayakiTranscriptionBatch) -> Unit,
        parallelism: Int = 2,
    )
}
