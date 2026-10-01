package moe.antimony.hoshi.features.tts

import org.junit.Assert.*
import org.junit.Test

class TtsVoiceDisplayTest {
    @Test fun knownMicrosoftFormatsKeepTheirIdentifiers() {
        for (source in listOf(TtsOnlineSource.Edge, TtsOnlineSource.Azure, TtsOnlineSource.MicrosoftTranslator)) {
            val voice = TtsVoiceInfo("ja-JP-NanamiNeural", true).display(source)
            assertEquals("Nanami", voice.title)
            assertEquals("ja-JP", voice.locale)
            assertEquals(TtsVoiceModel.Neural, voice.model)
            assertEquals("ja-JP-NanamiNeural", voice.rawId)
        }
    }
    @Test fun multilingualVoiceIsNotMistakenForPartOfTheName() {
        val voice = TtsVoiceInfo("ja-JP-MasaruMultilingualNeural", true).display(TtsOnlineSource.Azure)
        assertEquals("Masaru", voice.title)
        assertEquals(TtsVoiceModel.MultilingualNeural, voice.model)
    }
    @Test fun unknownAndMalformedNamesStaySelectableWithRawIds() {
        for (id in listOf("voice_13929_x", "CustomVoice-A", "ja-JP-", "ja-JP-Neural", "ja-XX-NanamiNeural", "ja-JP-NanamiExperimental")) {
            val voice = TtsVoiceInfo(id, true).display(TtsOnlineSource.Azure)
            assertEquals(id, voice.title)
            assertEquals(id, voice.rawId)
            assertNull(voice.model)
        }
    }
    @Test fun providerMetadataTakesPrecedenceAndDoesNotChangeStyleOrSelection() {
        val voice = OnlineTtsProtocol.microsoftVoices("""[{"ShortName":"ja-JP-NanamiNeural","DisplayName":"Provider name","Locale":"ja-JP","StyleList":["cheerful"]}]""").single()
        assertEquals("Provider name", voice.display(TtsOnlineSource.Azure).title)
        assertEquals("ja-JP-NanamiNeural", voice.display(TtsOnlineSource.Azure).rawId)
        assertEquals(listOf("cheerful"), voice.styles)
        assertEquals("ja-JP", voice.locale)
    }
    @Test fun blankDisplayMetadataFallsBackSafely() {
        assertEquals("Nanami", TtsVoiceInfo("ja-JP-NanamiNeural", true, displayName = " ").display(TtsOnlineSource.Edge).title)
        assertEquals("Custom name", TtsVoiceInfo("voice_13929_x", true, displayName = "Custom name", locale = "ja-JP").display(null).title)
    }
    @Test fun malformedOptionalMetadataCannotInvalidateAUsableCatalog() {
        for (metadata in listOf("null", "{}", "[]", "42")) {
            val voice = OnlineTtsProtocol.microsoftVoices("""[{"ShortName":"ja-JP-NanamiNeural","DisplayName":$metadata,"Locale":"ja-JP"}]""").single()
            assertEquals("Nanami", voice.display(TtsOnlineSource.Azure).title)
        }
    }
    @Test fun installedProvidersAndGoogleDoNotBorrowMicrosoftNamingRules() {
        for (source in listOf(null, TtsOnlineSource.GoogleCloud, TtsOnlineSource.GoogleTranslate)) {
            assertEquals("ja-JP-NanamiNeural", TtsVoiceInfo("ja-JP-NanamiNeural", false).display(source).title)
        }
    }
}
