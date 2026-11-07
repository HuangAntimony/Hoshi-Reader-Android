package moe.antimony.hoshi.features.tts

import org.junit.Assert.*
import org.junit.Test

class TtsLanguageTest {
    @Test fun aliasesScriptsAndRegionsUseContentLanguageWithoutDroppingRegionalVoices() {
        val cases = mapOf("ja" to listOf("ja-JP", "jpn_JPN"), "en" to listOf("en-US", "en-GB", "eng_USA"),
            "zh" to listOf("zh-Hans-CN", "zh-Hant-TW", "zho", "chi", "cmn-CN", "yue-HK"), "ko" to listOf("ko-KR", "kor_KOR"))
        val voices = cases.values.flatten().map { TtsVoiceInfo(it, true, languages = listOf(it)) }
        for ((language, tags) in cases) {
            assertEquals(tags.toSet(), voices.forLanguage(language).map { it.id }.toSet())
            for (tag in tags) assertEquals(tag.replace('_', '-'), voices.first { it.id == tag }.localeFor(language))
        }
        assertTrue(listOf(TtsVoiceInfo("unknown", true)).forLanguage("en").isEmpty())
        assertEquals("unknown", listOf(TtsVoiceInfo("unknown", true)).forLanguage("en", allowUnknown = true).single().id)
    }

    @Test fun microsoftVoiceChoicesMatchThePrimaryLanguageOnly() {
        val raw = OnlineTtsProtocol.microsoftVoices("""[
          {"ShortName":"de-DE-SeraphinaMultilingualNeural","Locale":"de-DE","SecondaryLocaleList":["zh-CN","zh-HK","zh-TW"]},
          {"ShortName":"en-AU-Isla:MAI-Voice-2","Locale":"en-AU","SecondaryLocaleList":["zh-CN"]},
          {"ShortName":"zh-CN-XiaoxiaoMultilingualNeural","Locale":"zh-CN","SecondaryLocaleList":["en-US"]},
          {"ShortName":"zh-TW-HsiaoChenNeural","Locale":"zh-TW"},
          {"ShortName":"yue-CN-XiaoMinNeural","Locale":"yue-CN"}
        ]""")
        val chinese = raw.forLanguage("zh")
        assertEquals(listOf("yue-CN-XiaoMinNeural", "zh-CN-XiaoxiaoMultilingualNeural", "zh-TW-HsiaoChenNeural"), chinese.map { it.id })
        assertEquals(listOf("yue-CN", "zh-CN", "zh-TW"), chinese.map { it.localeFor("zh") })
        assertEquals(listOf("en-AU-Isla:MAI-Voice-2"), raw.forLanguage("en").map { it.id })
    }

    @Test fun requestLanguageComesFromSelectedVoiceAndOptionsAreNormalizedLocally() {
        val voice = TtsVoiceInfo("uk", true, languages = listOf("en-GB"))
        val settings = TtsSettings(engine = TtsOnlineSource.Azure.id, languageId = "en")
            .withVoice("uk").withOptions(TtsSynthesisOptions(style = "cheerful", role = "Girl"))
        val request = settings.withCompatibleVoiceOptions(listOf(voice))
        val xml = OnlineTtsProtocol.azureSsml("hello", voice, 1f, 0f, request.options(), voice.localeFor(settings.languageId))
        assertTrue(xml.contains("xml:lang=\"en-GB\""))
        assertFalse(xml.contains("express-as"))
        assertEquals("cheerful", settings.options().style)
        assertTrue(OnlineTtsProtocol.googleBody("你好", "cantonese", 1f, locale = "yue-HK").contains("\"languageCode\":\"yue-HK\""))
        assertTrue(OnlineTtsProtocol.ssml("안녕", "korean", 1f, locale = "ko-KR").contains("xml:lang=\"ko-KR\""))
        assertTrue(OnlineTtsProtocol.translateBody("你好", false, "zh-CN").contains("zh-CN"))
        assertNotEquals(settings.synthesisSettings(), settings.copy(languageId = "ja").synthesisSettings())
    }
}
