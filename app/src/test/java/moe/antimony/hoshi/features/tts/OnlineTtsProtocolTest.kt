package moe.antimony.hoshi.features.tts

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OnlineTtsProtocolTest {
    @Test fun japaneseEllipsisUsesOneExplicitPausePerRunWithoutInjectingMarkup() {
        val text = "「待って……まだ‥‥です...<x>」"
        val xml = OnlineTtsProtocol.azureSsml(text, TtsVoiceInfo("ja-JP-NanamiNeural", true), 1f, 0f,
            TtsSynthesisOptions(ellipsisPauseMs = 700))
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val pauses = document.getElementsByTagName("break")
        assertEquals(3, pauses.length)
        repeat(pauses.length) { assertEquals("700ms", pauses.item(it).attributes.getNamedItem("time").nodeValue) }
        assertEquals(0, document.getElementsByTagName("x").length)
        val natural = OnlineTtsProtocol.azureSsml(text, TtsVoiceInfo("ja-JP-NanamiNeural", true), 1f, 0f,
            TtsSynthesisOptions(ellipsisPauseMs = -1))
        assertEquals(text, javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(natural.byteInputStream()).documentElement.textContent)
    }

    @Test fun edgeEllipsisUsesSupportedPunctuationAndCanBeDisabled() {
        assertEquals("待って。まだです。", OnlineTtsProtocol.edgeText("待って……まだです…。", true))
        assertEquals("待って……まだです。", OnlineTtsProtocol.edgeText("待って……まだです。", false))
        assertEquals("3.14", OnlineTtsProtocol.edgeText("3.14", true))
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

    @Test fun googleSynthesisUsesLiteralTextAndPcmForExport() {
        val body = Json.parseToJsonElement(OnlineTtsProtocol.googleBody("日本<&", "ja-JP-Neural2-B", 1.25f)).jsonObject
        assertEquals("日本<&", body.getValue("input").jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("LINEAR16", body.getValue("audioConfig").jsonObject.getValue("audioEncoding").jsonPrimitive.content)
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
