package moe.antimony.hoshi.features.tts

import kotlinx.serialization.Serializable

internal const val ttsProviderPauseMaxMs = 1000
internal const val ttsSentencePauseMaxMs = 2000L

@Serializable
enum class TtsAudioFormat { Wav, Mp3 }

/** Provider parameters; unsupported controls are neither shown nor added to requests. */
@Serializable
data class TtsSynthesisOptions(
    val volume: Float = 0f,
    val style: String = "",
    val styleDegree: Float = 1f,
    val role: String = "",
    val leadingMs: Int = -1,
    val trailingMs: Int = -1,
    val commaMs: Int = -1,
    val semicolonMs: Int = -1,
    val enumerationCommaMs: Int = -1,
    val sentenceBoundaryMs: Int = -1,
    val format: TtsAudioFormat = TtsAudioFormat.Mp3,
    val sampleRate: Int = 24000,
    val effectsProfile: String = "",
) {
    fun normalized(source: TtsOnlineSource?) = copy(
        volume = volume.takeIf { it.isFinite() }?.coerceIn(if (source == TtsOnlineSource.GoogleCloud) -96f..16f else -100f..100f) ?: 0f,
        styleDegree = styleDegree.takeIf { it.isFinite() }?.coerceIn(.01f, 2f) ?: 1f,
        leadingMs = leadingMs.coerceIn(-1, ttsProviderPauseMaxMs), trailingMs = trailingMs.coerceIn(-1, ttsProviderPauseMaxMs),
        commaMs = commaMs.coerceIn(-1, ttsProviderPauseMaxMs), semicolonMs = semicolonMs.coerceIn(-1, ttsProviderPauseMaxMs),
        enumerationCommaMs = enumerationCommaMs.coerceIn(-1, ttsProviderPauseMaxMs), sentenceBoundaryMs = sentenceBoundaryMs.coerceIn(-1, ttsProviderPauseMaxMs),
        sampleRate = sampleRate.takeIf { it in listOf(16000, 24000, 48000) } ?: 24000,
        effectsProfile = effectsProfile.takeIf { it in googleAudioProfiles } ?: "",
    )
}

internal val googleAudioProfiles = listOf("", "wearable-class-device", "handset-class-device", "headphone-class-device",
    "small-bluetooth-speaker-class-device", "medium-bluetooth-speaker-class-device", "large-home-entertainment-class-device",
    "large-automotive-class-device", "telephony-class-application")

internal fun TtsSettings.options() = parameters[engine]?.normalized(TtsOnlineSource.from(engine)) ?: TtsSynthesisOptions()
internal fun TtsSettings.withOptions(options: TtsSynthesisOptions) = copy(parameters = parameters + (engine to options))
internal fun TtsSettings.audioExtension(): String = customSource()?.let { if (it.format == TtsAudioFormat.Mp3) "mp3" else "wav" } ?: when (TtsOnlineSource.from(engine)) {
    TtsOnlineSource.Edge, TtsOnlineSource.GoogleTranslate -> "mp3"
    TtsOnlineSource.Azure, TtsOnlineSource.MicrosoftTranslator, TtsOnlineSource.GoogleCloud -> if (options().format == TtsAudioFormat.Mp3) "mp3" else "wav"
    null -> "wav"
}
