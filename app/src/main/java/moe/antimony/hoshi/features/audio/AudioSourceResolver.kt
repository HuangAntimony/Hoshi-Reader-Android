package moe.antimony.hoshi.features.audio

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class LocalAudioEntry(
    val source: String,
    val expression: String,
    val reading: String?,
    val file: String,
)

data class LocalAudioFile(
    val source: String,
    val file: String,
)

object AudioSourceResolver {
    fun expandTemplate(template: String, term: String, reading: String): String =
        template
            .replace("{term}", term.urlEncode())
            .replace("{reading}", reading.urlEncode())
}

object LocalAudioResolver {
    private val supportedAudioExtensions = setOf("mp3", "opus", "ogg")

    fun resolve(
        term: String,
        reading: String,
        rows: List<LocalAudioEntry>,
        sourceOrder: List<String> = LocalAudioSourceOrder.defaultOrder(rows.map { it.source }),
    ): LocalAudioEntry? {
        val normalizedReading = katakanaToHiragana(reading)
        val sourceRank = sourceOrder.withIndex().associate { it.value to it.index }
        return rows
            .asSequence()
            .filter { it.expression == term || (!it.reading.isNullOrBlank() && it.reading == normalizedReading) }
            .filter { isSupportedAudioFile(it.file) }
            .sortedWith(
                compareBy<LocalAudioEntry> {
                    it.localAudioMatchRank(term, normalizedReading)
                }.thenBy {
                    sourceRank[it.source] ?: Int.MAX_VALUE
                }.thenBy {
                    it.source
                },
            )
            .firstOrNull()
    }

    fun audioUrl(source: String, file: String): String =
        "$LOCAL_AUDIO_SCHEME://${source.urlEncode()}/${file.urlEncode()}"

    fun parseAudioUrl(url: String): LocalAudioFile? {
        if (!url.startsWith("$LOCAL_AUDIO_SCHEME://")) return null
        val tail = url.removePrefix("$LOCAL_AUDIO_SCHEME://")
        val slash = tail.indexOf('/')
        if (slash <= 0 || slash == tail.lastIndex) return null
        return LocalAudioFile(
            source = tail.substring(0, slash).urlDecode(),
            file = tail.substring(slash + 1).urlDecode(),
        )
    }

    fun isSupportedAudioFile(file: String): Boolean =
        audioExtension(file) in supportedAudioExtensions

    fun audioExtension(file: String): String =
        file.substringAfterLast('.', missingDelimiterValue = "").lowercase()

    fun mimeType(file: String): String =
        when (audioExtension(file)) {
            "mp3" -> "audio/mpeg"
            "opus", "ogg" -> "audio/ogg"
            else -> "application/octet-stream"
        }

    private fun LocalAudioEntry.localAudioMatchRank(term: String, normalizedReading: String): Int {
        val readingMatches = normalizedReading.isNotBlank() && reading == normalizedReading
        val expressionMatches = expression == term
        return when {
            readingMatches && expressionMatches -> 0
            readingMatches -> 1
            expressionMatches -> 2
            else -> 3
        }
    }

    fun katakanaToHiragana(text: String): String {
        val builder = StringBuilder()
        text.codePoints().forEach { codePoint ->
            val converted = if (codePoint in 0x30A1..0x30F6) codePoint - 0x60 else codePoint
            builder.appendCodePoint(converted)
        }
        return builder.toString()
    }

    const val LOCAL_AUDIO_SCHEME = "hoshi-local-audio"
}

internal fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun String.urlDecode(): String =
    URLDecoder.decode(this, StandardCharsets.UTF_8.name())
