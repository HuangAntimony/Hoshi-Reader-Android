package moe.antimony.hoshi.features.sasayaki.transcription

import javax.inject.Inject
import moe.antimony.hoshi.features.sasayaki.SasayakiTranscriptionModel

internal class SasayakiBackendRouter @Inject constructor(
    private val reazon: AndroidSasayakiTranscriptionBackend,
    private val nakuru: NakuruTranscriptionBackend,
) : SasayakiTranscriptionBackend by reazon {
    override fun forModel(model: SasayakiTranscriptionModel): SasayakiTranscriptionBackend = when (model) {
        SasayakiTranscriptionModel.ReazonSpeech -> reazon
        SasayakiTranscriptionModel.Nakuru -> nakuru
    }
}
