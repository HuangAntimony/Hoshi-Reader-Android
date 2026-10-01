package moe.antimony.hoshi.features.tts

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.*
import kotlin.math.roundToInt

/** Wire formats, independent of Android, storage and transport. */
internal object OnlineTtsProtocol {
    const val edgeToken = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val edgeVersion = "143.0.3650.75"
    const val edgeBase = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud"
    fun microsoftVoices(body: String): List<TtsVoiceInfo> = parse {
        Json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
            .filter { it["Locale"]?.jsonPrimitive?.content.equals("ja-JP", true) }
            .map { TtsVoiceInfo(it.getValue("ShortName").jsonPrimitive.content, true,
                it["StyleList"]?.jsonArray.orEmpty().map { value -> value.jsonPrimitive.content },
                it["RolePlayList"]?.jsonArray.orEmpty().map { value -> value.jsonPrimitive.content }) }
            .distinctBy { it.id }.sortedBy { it.id }
    }

    fun googleVoices(body: String): List<TtsVoiceInfo> = parse {
        Json.parseToJsonElement(body).jsonObject["voices"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { voice -> voice["languageCodes"]?.jsonArray.orEmpty().any { it.jsonPrimitive.content == "ja-JP" } }
            .map { TtsVoiceInfo(it.getValue("name").jsonPrimitive.content, true) }
            .distinctBy { it.id }.sortedBy { it.id }
    }

    fun azureBase(region: String): String {
        if (!region.matches(Regex("[a-z][a-z0-9]{1,39}"))) throw TtsException(TtsFailure.Configuration)
        return "https://$region.tts.speech.microsoft.com"
    }

    fun ssml(text: String, voice: String, rate: Float, pitch: String = "+0Hz", volume: Float = 0f): String {
        val percentage = ((rate - 1f) * 100).roundToInt()
        val speed = if (percentage >= 0) "+$percentage%" else "$percentage%"
        return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"ja-JP\">" +
            "<voice name=\"${xml(voice)}\"><prosody rate=\"$speed\" pitch=\"${xml(pitch)}\" volume=\"${signed(volume.toInt())}%\">${xml(text)}</prosody></voice></speak>"
    }

    fun azureSsml(text: String, voice: TtsVoiceInfo, rate: Float, pitch: Float, options: TtsSynthesisOptions): String {
        val controls = options.normalized(TtsOnlineSource.Azure)
        if (controls.style.isNotBlank() && controls.style !in voice.styles || controls.role.isNotBlank() &&
            (controls.role !in voice.roles || controls.style.isBlank())) throw TtsException(TtsFailure.Configuration)
        val content = "<prosody rate=\"${signed(((rate - 1) * 100).roundToInt())}%\" pitch=\"${signed(pitch.toInt())}st\" volume=\"${signed(controls.volume.toInt())}%\">${xml(text)}</prosody>"
        val styled = if (controls.style.isBlank()) content else
            "<mstts:express-as style=\"${xml(controls.style)}\" styledegree=\"${controls.styleDegree}\"" +
                (if (controls.role.isNotBlank()) " role=\"${xml(controls.role)}\"" else "") + ">$content</mstts:express-as>"
        val pauses = listOf("Leading-exact" to controls.leadingMs, "Tailing-exact" to controls.trailingMs,
            "Comma-exact" to controls.commaMs, "Semicolon-exact" to controls.semicolonMs,
            "Enumerationcomma-exact" to controls.enumerationCommaMs, "Sentenceboundary-exact" to controls.sentenceBoundaryMs)
            .filter { it.second >= 0 }.joinToString("") { (type, duration) -> "<mstts:silence type=\"$type\" value=\"${duration}ms\"/>" }
        return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xmlns:mstts=\"https://www.w3.org/2001/mstts\" xml:lang=\"ja-JP\">" +
            "<voice name=\"${xml(voice.id)}\">$pauses$styled</voice></speak>"
    }

    private fun signed(value: Int) = if (value >= 0) "+$value" else value.toString()

    private fun xml(text: String) = buildString {
        text.forEach { append(when (it) {
            '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&apos;"
            else -> if (it < ' ' && it !in "\n\r\t") " " else it.toString()
        }) }
    }

    /** Service limits count UTF-8 bytes, not Kotlin UTF-16 units. */
    fun chunks(text: String, maxBytes: Int): List<String> {
        require(maxBytes >= 4)
        val result = mutableListOf<String>()
        var start = 0
        var cursor = 0
        var bytes = 0
        var boundary = 0
        while (cursor < text.length) {
            val cp = text.codePointAt(cursor)
            val count = Character.charCount(cp)
            val size = text.substring(cursor, cursor + count).toByteArray().size
            if (bytes + size > maxBytes) {
                val end = if (boundary > start) boundary else cursor
                result += text.substring(start, end)
                start = end; cursor = end; bytes = 0; boundary = end
                continue
            }
            bytes += size
            cursor += count
            if (cp.toChar() in "。！？、.!? \n") boundary = cursor
        }
        if (start < text.length) result += text.substring(start)
        return result
    }

    fun googleBody(text: String, voice: String, rate: Float, pitch: Float = 0f, options: TtsSynthesisOptions = TtsSynthesisOptions()) = buildJsonObject {
        putJsonObject("input") { put("text", text) }
        putJsonObject("voice") { put("languageCode", "ja-JP"); put("name", voice) }
        putJsonObject("audioConfig") {
            put("audioEncoding", if (options.format == TtsAudioFormat.Mp3) "MP3" else "LINEAR16")
            put("speakingRate", rate); put("sampleRateHertz", options.sampleRate)
            put("volumeGainDb", options.volume)
            if (options.effectsProfile.isNotBlank()) putJsonArray("effectsProfileId") { add(options.effectsProfile) }
            if (ttsPitchRange(TtsOnlineSource.GoogleCloud, voice) != null) put("pitch", pitch.coerceIn(-20f, 20f))
        }
    }.toString()

    fun googleAudio(body: String): ByteArray = parse {
        Base64.getDecoder().decode(Json.parseToJsonElement(body).jsonObject.getValue("audioContent").jsonPrimitive.content)
    }

    fun translateBody(text: String, slow: Boolean): String {
        val parameters = buildJsonArray { add(text); add("ja"); add(if (slow) JsonPrimitive(true) else JsonNull); add("null") }
        return buildJsonArray { add(buildJsonArray { add(buildJsonArray {
            add("jQ1olc"); add(parameters.toString()); add(JsonNull); add("generic")
        }) }) }.toString()
    }

    fun translateAudio(body: String): ByteArray = parse {
        body.lineSequence().filter { it.trimStart().startsWith("[") }.forEach { line ->
            val rows = runCatching { Json.parseToJsonElement(line).jsonArray }.getOrNull() ?: return@forEach
            rows.forEach { row ->
                val values = row as? JsonArray ?: return@forEach
                if (values.getOrNull(1)?.jsonPrimitive?.content == "jQ1olc") {
                    val audio = Json.parseToJsonElement(values[2].jsonPrimitive.content).jsonArray[0].jsonPrimitive.content
                    return@parse Base64.getDecoder().decode(audio)
                }
            }
        }
        throw TtsException(TtsFailure.InvalidResponse)
    }

    fun edgeGec(unixSeconds: Long): String {
        val seconds = unixSeconds + 11_644_473_600L
        val ticks = (seconds - seconds % 300) * 10_000_000L
        return MessageDigest.getInstance("SHA-256").digest("$ticks$edgeToken".toByteArray())
            .joinToString("") { "%02X".format(it) }
    }

    fun edgeAudio(frame: ByteArray): ByteArray {
        if (frame.size < 2) throw TtsException(TtsFailure.InvalidResponse)
        val length = (frame[0].toInt() and 255) * 256 + (frame[1].toInt() and 255)
        if (length > frame.size - 2) throw TtsException(TtsFailure.InvalidResponse)
        val headers = frame.copyOfRange(2, 2 + length).toString(Charsets.UTF_8)
        if (!headers.lineSequence().any { it.trim() == "Path:audio" }) throw TtsException(TtsFailure.InvalidResponse)
        return frame.copyOfRange(2 + length, frame.size)
    }

    private inline fun <T> parse(block: () -> T): T = try { block() }
        catch (error: TtsException) { throw error }
        catch (_: Exception) { throw TtsException(TtsFailure.InvalidResponse) }
}
