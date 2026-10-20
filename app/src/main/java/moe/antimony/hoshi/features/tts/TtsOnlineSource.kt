package moe.antimony.hoshi.features.tts

import kotlinx.serialization.Serializable
import moe.antimony.hoshi.R

enum class TtsOnlineSource(val id: String, val labelRes: Int, val needsKey: Boolean = false) {
    Edge("online:edge", R.string.tts_source_edge),
    Azure("online:azure", R.string.tts_source_azure, true),
    MicrosoftTranslator("online:microsoft-translator", R.string.tts_source_microsoft_translator),
    GoogleCloud("online:google-cloud", R.string.tts_source_google_cloud, true),
    GoogleTranslate("online:google-translate", R.string.tts_source_google_translate);

    fun normalizeRate(rate: Float): Float = when {
        !rate.isFinite() -> 1f
        this == GoogleTranslate -> if (rate < .75f) .5f else 1f
        this == GoogleCloud -> rate.coerceIn(.25f, 2f)
        usesAzureRest -> rate.coerceIn(.5f, 2f)
        else -> rate.coerceIn(.5f, 3f)
    }

    val usesAzureRest: Boolean get() = this == Azure || this == MicrosoftTranslator

    companion object { fun from(id: String) = entries.firstOrNull { it.id == id } }
}

/** Only encrypted key material is persisted; it is never included in exported settings. */
@Serializable
data class TtsOnlineConfig(val region: String = "", val encryptedKey: String = "")

/** Edge uses Hertz; other supported sources use semitones relative to the selected voice. */
internal fun ttsPitchRange(source: TtsOnlineSource?, voice: String): ClosedFloatingPointRange<Float>? = when {
    source == TtsOnlineSource.GoogleTranslate -> null
    source == TtsOnlineSource.GoogleCloud && (voice.isBlank() || "Chirp" in voice || "Journey" in voice) -> null
    source == TtsOnlineSource.Edge -> -50f..50f
    source == TtsOnlineSource.GoogleCloud -> -20f..20f
    source?.usesAzureRest == true -> -12f..7f
    else -> -12f..12f
}
