package moe.antimony.hoshi.features.tts

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import moe.antimony.hoshi.R
import moe.antimony.hoshi.network.isPrivateHttpHost
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
enum class TtsCustomProtocol(val labelRes: Int) {
    OpenAi(R.string.tts_protocol_openai), Vits(R.string.tts_protocol_vits)
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class TtsCustomSource(
    val id: String,
    val name: String = "",
    val protocol: TtsCustomProtocol = TtsCustomProtocol.OpenAi,
    val baseUrl: String = "",
    val encryptedKey: String = "",
    val model: String = "",
    val voice: String = "",
    val rate: Float = 1f,
    @EncodeDefault val format: TtsAudioFormat = TtsAudioFormat.Mp3,
    val preset: String = "",
    val voiceIds: List<String> = emptyList(),
) {
    fun normalized(): TtsCustomSource = copy(name = name.trim(), baseUrl = customTtsBaseUrl(baseUrl).toString(),
        model = model.trim(), voice = voice.trim(), preset = preset.trim(), rate = normalizeRate(rate),
        voiceIds = voiceIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
    fun validate(): TtsCustomSource {
        val value = normalized()
        if (!id.startsWith("custom:") || id.length <= 7 || value.name.isBlank() ||
            protocol == TtsCustomProtocol.OpenAi && value.model.isBlank() ||
            protocol == TtsCustomProtocol.Vits && value.voice.isNotBlank() && vitsSpeaker(value.voice) == null)
            throw TtsException(TtsFailure.Configuration)
        return value
    }
    fun normalizeRate(value: Float): Float = if (!value.isFinite() || !supportsRate) 1f else value.coerceIn(rateRange)
    val rateRange: ClosedFloatingPointRange<Float> get() = if (protocol == TtsCustomProtocol.OpenAi) .25f..4f else .5f..3f
    val supportsRate: Boolean get() = protocol != TtsCustomProtocol.Vits || !voice.startsWith("GPT-SOVITS:")
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

internal fun TtsCustomSource.voiceChoices(discovered: List<TtsVoiceInfo>): List<TtsVoiceInfo> {
    val configured = if (protocol == TtsCustomProtocol.OpenAi) voiceIds.map { TtsVoiceInfo(it, true) } else emptyList()
    val choices = (discovered + configured).distinctBy { it.id }
    return if (voice.isNotBlank() && choices.none { it.id == voice }) choices + TtsVoiceInfo(voice, true) else choices
}

/** Only the selected source influences synthesis; display names and unrelated profiles do not. */
internal fun TtsSettings.synthesisSettings(): TtsSettings {
    val custom = customSource()
    return copy(voice = custom?.voice ?: voice, rate = custom?.rate ?: rate, pitch = if (isCustomSource()) 0f else pitch,
        online = online.filterKeys { it == engine }, parameters = parameters.filterKeys { it == engine },
        customSources = listOfNotNull(custom?.copy(name = "", voiceIds = emptyList())), prefetchSentences = 0, playbackRate = 1f,
        routingBySource = if (routing().enabled) mapOf(engine to routing()) else emptyMap())
}

internal fun TtsSettings.selectSource(id: String): TtsSettings {
    val custom = customSources.firstOrNull { it.id == id }
    val selectedRate = custom?.rate ?: TtsOnlineSource.from(id)?.normalizeRate(rate)
        ?: rate.takeIf { it.isFinite() }?.coerceIn(.5f, 3f) ?: 1f
    return copy(engine = id, voice = routingBySource[id]?.narration ?: custom?.voice ?: "", rate = selectedRate, pitch = 0f,
        routingBySource = routingBySource + (engine to routing().copy(narration = voice)))
}
