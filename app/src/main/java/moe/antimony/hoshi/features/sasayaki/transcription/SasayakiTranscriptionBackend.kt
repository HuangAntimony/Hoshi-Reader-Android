package moe.antimony.hoshi.features.sasayaki.transcription

import moe.antimony.hoshi.features.sasayaki.SasayakiToken

data class SasayakiTranscriptionBatch(
    val tokens: List<SasayakiToken>,
    /** Resume checkpoint, including silence; recognized tokens may extend into trailing context. */
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
        previousTokens: List<SasayakiToken> = emptyList(),
    )
}
