package moe.antimony.hoshi.features.tts

import java.util.Locale
import moe.antimony.hoshi.content.ContentLanguageProfile

/** Service and device catalogs use both ISO aliases and BCP-47/underscore locale tags. */
internal fun ttsLanguageId(tag: String): String = when (val language = tag.trim().substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)) {
    "jpn" -> "ja"
    "eng" -> "en"
    "zho", "chi", "cmn", "yue" -> "zh"
    "kor" -> "ko"
    else -> language
}

internal fun ttsLocale(languageId: String): String =
    (ContentLanguageProfile.fromDictionaryLanguageId(languageId) ?: ContentLanguageProfile.Default).composeLocaleTag

/** Match the voice's catalog language. Additional Microsoft locales describe multilingual capability. */
internal fun List<TtsVoiceInfo>.forLanguage(languageId: String, allowUnknown: Boolean = false): List<TtsVoiceInfo> =
    filter { voice ->
        voice.languages.any { ttsLanguageId(it) == languageId } ||
            allowUnknown && voice.languages.isEmpty() && voice.secondaryLanguages.isEmpty()
    }.sortedBy { it.languages.isEmpty() }

internal fun TtsVoiceInfo.localeFor(languageId: String): String =
    languages.firstOrNull { ttsLanguageId(it) == languageId }?.replace('_', '-') ?: ttsLocale(languageId)

internal fun TtsSettings.sampleRes(dialogue: Boolean = routing().enabled): Int = when (languageId) {
    "en" -> if (dialogue) moe.antimony.hoshi.R.string.tts_dialogue_sample_en else moe.antimony.hoshi.R.string.tts_sample_en
    "zh" -> if (dialogue) moe.antimony.hoshi.R.string.tts_dialogue_sample_zh else moe.antimony.hoshi.R.string.tts_sample_zh
    "ko" -> if (dialogue) moe.antimony.hoshi.R.string.tts_dialogue_sample_ko else moe.antimony.hoshi.R.string.tts_sample_ko
    else -> if (dialogue) moe.antimony.hoshi.R.string.tts_dialogue_sample else moe.antimony.hoshi.R.string.tts_sample
}
