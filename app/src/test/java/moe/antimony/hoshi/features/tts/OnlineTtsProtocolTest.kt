package moe.antimony.hoshi.features.tts

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OnlineTtsProtocolTest {
    @Test fun edgeV2TokenMatchesBrowserCaptureAndUpstreamVectors() {
        // Browser capture: https://github.com/rany2/edge-tts/pull/492
        // Remaining vectors: edge-tts 7.3.1, including the next window and post-2038 time.
        val vectors = listOf(
            1_791_888_000L to "492D7694A183A45E-56E821F95D4579C5B7C7E33DC7303FF379EC36D510213555BBBB9EB17EF15876",
            1_791_888_300L to "6D26B5F5B70269AD-280E82E1BBE24E4E8866E3502EB6277B9436F61ABDC1C224518A038BBD7BBDAD",
            1_793_037_600L to "854DFD8C38425A55-2A1C787288C73B0E8403376D6BC9D96603287CB3292EEEF10D458FCAB18871DF",
            4_102_444_800L to "DD230DB44FEE2FD7-DA0D4FA797866E36C992FABB81DD2F9DB37EB74D675D63228277003DCD3F9F8C",
        )
        for ((seconds, token) in vectors) assertEquals("Unix seconds: $seconds", token, OnlineTtsProtocol.edgeGec(seconds))
    }

    @Test fun edgeV2TokenUsesThePrecedingFiveMinuteWindow() {
        val token = "492D7694A183A45E-56E821F95D4579C5B7C7E33DC7303FF379EC36D510213555BBBB9EB17EF15876"
        assertEquals(token, OnlineTtsProtocol.edgeGec(1_791_888_000L))
        assertEquals(token, OnlineTtsProtocol.edgeGec(1_791_888_299L))
        assertEquals("DAAEAD2FD5438630-897933E9C951AADAA0A792FFD23FBB12339BBBBA71EA7BF84FFA0C777C3C9550",
            OnlineTtsProtocol.edgeGec(1_791_887_999L))
    }

    @Test fun literalReplacementsAreEscapedForMicrosoftSpeech() {
        val sentence = TtsText.chapter(0, "<p>待って――まだ……続く。</p>").single()
        val rules = listOf(TtsWord("dash", "――", "、"), TtsWord("ellipsis", "……", "、<break/>"))
        val spoken = TtsText.spoken(sentence, null, rules)
        assertEquals("待って、まだ、<break/>続く。", spoken)
        val edgeXml = OnlineTtsProtocol.ssml(spoken, "ja-JP-NanamiNeural", 1f)
        val edgeDocument = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(edgeXml.byteInputStream())
        assertEquals(spoken, edgeDocument.documentElement.textContent)
        assertEquals(0, edgeDocument.getElementsByTagName("break").length)
        val xml = OnlineTtsProtocol.azureSsml(spoken, TtsVoiceInfo("ja-JP-NanamiNeural", true), 1f, 0f,
            TtsSynthesisOptions())
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals(spoken, document.documentElement.textContent)
        assertEquals(0, document.getElementsByTagName("break").length)
    }

    @Test fun azureCarriesOnlyAdvertisedStylesAndPrecisePauseSettings() {
        val voices = OnlineTtsProtocol.microsoftVoices("""[{"ShortName":"ja-JP-NanamiNeural","Locale":"ja-JP","StyleList":["cheerful","sad"],"RolePlayList":["YoungAdultFemale"]}]""")
        assertEquals(listOf("cheerful", "sad"), voices.single().styles)
        val options = TtsSynthesisOptions(style = "cheerful", role = "YoungAdultFemale", styleDegree = 1.5f,
            volume = -20f, leadingMs = 0, trailingMs = 200, commaMs = 300)
        val xml = OnlineTtsProtocol.azureSsml("日本。", voices.single(), 1f, 2f, options)
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals("日本。", document.documentElement.textContent)
        val express = document.getElementsByTagName("mstts:express-as").item(0)
        assertEquals("cheerful", express.attributes.getNamedItem("style").nodeValue)
        assertEquals("1.5", express.attributes.getNamedItem("styledegree").nodeValue)
        assertEquals(3, document.getElementsByTagName("mstts:silence").length)
        assertThrows(TtsException::class.java) {
            OnlineTtsProtocol.azureSsml("日本", voices.single(), 1f, 0f, options.copy(style = "unsupported"))
        }
    }

    @Test fun cloudCarriesGainSampleRateProfileAndRequestedMp3Format() {
        val options = TtsSynthesisOptions(volume = -6f, sampleRate = 48000, format = TtsAudioFormat.Mp3,
            effectsProfile = "headphone-class-device")
        val body = Json.parseToJsonElement(OnlineTtsProtocol.googleBody("日本", "ja-JP-Neural2-B", 1f, 0f, options)).jsonObject
        val config = body.getValue("audioConfig").jsonObject
        assertEquals("MP3", config.getValue("audioEncoding").jsonPrimitive.content)
        assertEquals(-6f, config.getValue("volumeGainDb").jsonPrimitive.float)
        assertEquals(48000, config.getValue("sampleRateHertz").jsonPrimitive.int)
        assertEquals("headphone-class-device", config.getValue("effectsProfileId").jsonArray.single().jsonPrimitive.content)
    }
    @Test fun pitchUsesProviderUnitsAndUnavailableControlsAreNotSent() {
        val edge = OnlineTtsProtocol.ssml("日本", "ja-JP-NanamiNeural", 1f, "+20Hz")
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(edge.byteInputStream())
        assertEquals("+20Hz", document.getElementsByTagName("prosody").item(0).attributes.getNamedItem("pitch").nodeValue)
        val cloud = Json.parseToJsonElement(OnlineTtsProtocol.googleBody("日本", "ja-JP-Neural2-B", 1f, 3f)).jsonObject
        assertEquals(3f, cloud.getValue("audioConfig").jsonObject.getValue("pitch").jsonPrimitive.float)
        assertNull(ttsPitchRange(TtsOnlineSource.GoogleTranslate, "ja"))
        assertNull(ttsPitchRange(TtsOnlineSource.GoogleCloud, "ja-JP-Chirp3-HD-Aoede"))
        val hd = Json.parseToJsonElement(OnlineTtsProtocol.googleBody("日本", "ja-JP-Chirp3-HD-Aoede", 1f, 3f)).jsonObject
        assertFalse(hd.getValue("audioConfig").jsonObject.containsKey("pitch"))
    }

    @Test fun microsoftCatalogFiltersJapaneseAndPreservesVoiceNames() {
        val voices = OnlineTtsProtocol.microsoftVoices("""[
            {"ShortName":"en-US-JennyNeural","Locale":"en-US"},
            {"ShortName":"ja-JP-NanamiNeural","Locale":"ja-JP","LocalName":"七海"}
        ]""")
        assertEquals(listOf("ja-JP-NanamiNeural"), voices.map { it.id })
        assertTrue(voices.single().network)
    }

    @Test fun googleCatalogIncludesJapaneseStandardAndHdVoices() {
        val voices = OnlineTtsProtocol.googleVoices("""{"voices":[
          {"name":"ja-JP-Neural2-B","languageCodes":["ja-JP"]},
          {"name":"en-US-Standard-A","languageCodes":["en-US"]},
          {"name":"ja-JP-Chirp3-HD-Aoede","languageCodes":["ja-JP"]}
        ]}""")
        assertEquals(setOf("ja-JP-Neural2-B", "ja-JP-Chirp3-HD-Aoede"), voices.map { it.id }.toSet())
    }

    @Test fun ssmlEscapesLiteralTextAndVoiceAndAppliesRate() {
        val xml = OnlineTtsProtocol.ssml("a<&\"b", "ja-JP-X\"", 1.5f)
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals("a<&\"b", document.documentElement.textContent)
        assertEquals("ja-JP-X\"", document.getElementsByTagName("voice").item(0).attributes.getNamedItem("name").nodeValue)
        assertEquals("+50%", document.getElementsByTagName("prosody").item(0).attributes.getNamedItem("rate").nodeValue)
    }

    @Test fun chunksRespectUtf8BudgetWithoutBreakingSurrogatePairs() {
        val original = "こんにちは。𠮷野家で食べる。".repeat(100)
        val chunks = OnlineTtsProtocol.chunks(original, 100)
        assertEquals(original, chunks.joinToString(""))
        assertTrue(chunks.all { it.toByteArray().size <= 100 })
        assertTrue(chunks.none { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
    }

    @Test fun googleSynthesisUsesLiteralTextAndDefaultsToMp3() {
        val body = Json.parseToJsonElement(OnlineTtsProtocol.googleBody("日本<&", "ja-JP-Neural2-B", 1.25f)).jsonObject
        assertEquals("日本<&", body.getValue("input").jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("MP3", body.getValue("audioConfig").jsonObject.getValue("audioEncoding").jsonPrimitive.content)
        assertEquals(1.25f, body.getValue("audioConfig").jsonObject.getValue("speakingRate").jsonPrimitive.float)
    }

    @Test fun translateResponseParsesRpcInsteadOfAcceptingHtml() {
        val payload = Json.encodeToString(JsonArray(listOf(JsonPrimitive("YWJj"))))
        val response = ")]}'\n\n99\n" + Json.encodeToString(buildJsonArray { add(buildJsonArray { add("wrb.fr"); add("jQ1olc"); add(payload); add(JsonNull) }) })
        assertArrayEquals("abc".toByteArray(), OnlineTtsProtocol.translateAudio(response))
        assertThrows(TtsException::class.java) { OnlineTtsProtocol.translateAudio("<html>blocked</html>") }
    }

    @Test fun edgeFramesStripHeaderAndRejectTruncation() {
        val header = "Path:audio\r\nContent-Type:audio/mpeg\r\n".toByteArray()
        val frame = byteArrayOf((header.size shr 8).toByte(), header.size.toByte()) + header + byteArrayOf(1, 2, 3)
        assertArrayEquals(byteArrayOf(1, 2, 3), OnlineTtsProtocol.edgeAudio(frame))
        assertThrows(TtsException::class.java) { OnlineTtsProtocol.edgeAudio(byteArrayOf(0, 30, 1)) }
    }

    @Test fun credentialsAndRateValidationIsSourceSpecific() {
        assertEquals(2f, TtsOnlineSource.GoogleCloud.normalizeRate(3f))
        assertEquals(.5f, TtsOnlineSource.GoogleTranslate.normalizeRate(.7f))
        assertEquals(1f, TtsOnlineSource.GoogleTranslate.normalizeRate(1.2f))
        assertThrows(TtsException::class.java) { OnlineTtsProtocol.azureBase("eastus/evil") }
        assertEquals("https://eastus.tts.speech.microsoft.com", OnlineTtsProtocol.azureBase("eastus"))
    }
}
