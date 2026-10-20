package moe.antimony.hoshi.features.tts

/** Only inputs to speech generation; player timing and catalog/display metadata stay outside. */
internal data class TtsSynthesisConfiguration(
    val engine: String,
    val voice: String,
    val rate: Float,
    val pitch: Float,
    val credentials: TtsOnlineConfig?,
    val options: TtsSynthesisOptions?,
    val custom: TtsCustomSynthesisConfiguration?,
    val useRubyReadings: Boolean,
    val dialogueVoice: String?,
)

internal data class TtsCustomSynthesisConfiguration(
    val protocol: TtsCustomProtocol,
    val baseUrl: String,
    val encryptedKey: String,
    val model: String,
    val format: TtsAudioFormat,
    val preset: String,
)

internal fun TtsSettings.synthesisSettings(): TtsSynthesisConfiguration {
    val custom = customSource()
    val onlineSource = TtsOnlineSource.from(engine)
    return TtsSynthesisConfiguration(engine, voice, rate,
        if (isCustomSource()) 0f else pitch,
        online[engine].takeIf { onlineSource != null }, options().takeIf { onlineSource != null },
        custom?.let { TtsCustomSynthesisConfiguration(it.protocol, it.baseUrl, it.encryptedKey, it.model, it.format, it.preset) },
        useRubyReadings, routing().dialogue.takeIf { routing().enabled })
}
