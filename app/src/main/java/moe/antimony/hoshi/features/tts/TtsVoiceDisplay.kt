package moe.antimony.hoshi.features.tts

/** Presentation only. rawId remains the selection, synthesis and persistence key. */
internal data class TtsVoiceDisplay(val title: String, val rawId: String, val locale: String? = null, val model: TtsVoiceModel? = null)
internal enum class TtsVoiceModel { Neural, MultilingualNeural }

private val knownLocaleTags = java.util.Locale.getAvailableLocales().map { it.toLanguageTag() }.toSet()
private val microsoftVoiceName = Regex("^([a-z]{2,3}-[A-Z]{2})-([A-Za-z][A-Za-z0-9]*?)(MultilingualNeural|Neural)$")

internal fun TtsVoiceInfo.display(source: TtsOnlineSource?): TtsVoiceDisplay {
    val microsoft = source == TtsOnlineSource.Edge || source?.usesAzureRest == true
    val parsed = if (microsoft) microsoftVoiceName.matchEntire(id)?.takeIf {
        it.groupValues[1] in knownLocaleTags
    } else null
    val model = parsed?.let { if (it.groupValues[3] == "MultilingualNeural") TtsVoiceModel.MultilingualNeural else TtsVoiceModel.Neural }
    return TtsVoiceDisplay(displayName?.takeIf { it.isNotBlank() } ?: parsed?.groupValues?.get(2) ?: id,
        rawId = id, locale = locale ?: parsed?.groupValues?.get(1), model = model)
}
