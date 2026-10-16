package moe.antimony.hoshi.features.tts

import kotlinx.serialization.Serializable
import moe.antimony.hoshi.R
import moe.antimony.hoshi.network.isPrivateHttpHost
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
enum class TtsCustomProtocol(val labelRes: Int) {
    OpenAi(R.string.tts_protocol_openai), Vits(R.string.tts_protocol_vits)
}

@Serializable
data class TtsCustomSource(
    val id: String,
    val name: String = "",
    val protocol: TtsCustomProtocol = TtsCustomProtocol.OpenAi,
    val baseUrl: String = "",
    val encryptedKey: String = "",
    val model: String = "",
    val rate: Float = 1f,
    val format: TtsAudioFormat = TtsAudioFormat.Mp3,
    val preset: String = "",
    val voiceIds: List<String> = emptyList(),
) {
    fun normalized(): TtsCustomSource = copy(name = name.trim(), baseUrl = customTtsBaseUrl(baseUrl).toString(),
        model = model.trim(), preset = preset.trim(), rate = normalizeRate(rate),
        voiceIds = voiceIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
    fun validate(): TtsCustomSource {
        val value = normalized()
        if (!id.startsWith("custom:") || id.length <= 7 || value.name.isBlank() ||
            protocol == TtsCustomProtocol.OpenAi && value.model.isBlank())
            throw TtsException(TtsFailure.Configuration)
        return value
    }
    fun normalizeRate(value: Float, voice: String = ""): Float = if (!value.isFinite() || !supportsRate(voice)) 1f else value.coerceIn(rateRange)
    val rateRange: ClosedFloatingPointRange<Float> get() = if (protocol == TtsCustomProtocol.OpenAi) .25f..4f else .5f..3f
    fun supportsRate(voice: String): Boolean = protocol != TtsCustomProtocol.Vits || !voice.startsWith("GPT-SOVITS:")
}

internal val vitsModels = linkedMapOf("VITS" to "vits", "W2V2-VITS" to "w2v2-vits", "BERT-VITS2" to "bert-vits2", "GPT-SOVITS" to "gpt-sovits")
internal fun vitsSpeaker(value: String): Pair<String, Int>? {
    val model = value.substringBefore(':')
    val id = value.substringAfter(':', "").toIntOrNull()
    return if (model in vitsModels && id != null && id >= 0) model to id else null
}

internal fun customTtsBaseUrl(value: String): HttpUrl {
    val url = value.trim().toHttpUrlOrNull() ?: throw TtsException(TtsFailure.Configuration)
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null)
        throw TtsException(TtsFailure.Configuration)
    if (url.scheme == "http" && !url.host.isPrivateHttpHost()) throw TtsException(TtsFailure.PrivateHttp)
    return if (url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()
}

internal fun TtsSettings.customSource(): TtsCustomSource? = customSources.firstOrNull { it.id == engine }
internal fun TtsSettings.isCustomSource(): Boolean = engine.startsWith("custom:")

internal fun TtsCustomSource.voiceChoices(discovered: List<TtsVoiceInfo>, voice: String): List<TtsVoiceInfo> {
    val configured = if (protocol == TtsCustomProtocol.OpenAi) voiceIds.map { TtsVoiceInfo(it, true) } else emptyList()
    val choices = (discovered + configured).distinctBy { it.id }
    return if (voice.isNotBlank() && choices.none { it.id == voice }) choices + TtsVoiceInfo(voice, true) else choices
}

internal fun TtsSettings.selectSource(id: String): TtsSettings {
    val custom = customSources.firstOrNull { it.id == id }
    val selectedRate = custom?.rate ?: TtsOnlineSource.from(id)?.normalizeRate(rate)
        ?: rate.takeIf { it.isFinite() }?.coerceIn(.5f, 3f) ?: 1f
    return copy(engine = id, pitch = 0f).withRate(selectedRate)
}

internal fun TtsSettings.withRate(value: Float): TtsSettings = if (customSource() != null)
    copy(customSources = customSources.map { if (it.id == engine) it.copy(rate = value) else it })
else copy(sharedRate = value)

internal fun TtsSettings.withVoice(value: String): TtsSettings = withRouting(routing().copy(narration = value))
