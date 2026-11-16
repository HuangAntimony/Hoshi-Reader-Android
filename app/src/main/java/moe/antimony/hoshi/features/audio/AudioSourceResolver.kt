package moe.antimony.hoshi.features.audio

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class LocalAudioEntry(
    val source: String,
    val expression: String,
    val reading: String?,
    val file: String,
    val display: String = "",
)

data class LocalAudioCandidate(
    val name: String,
    val url: String,
)

data class LocalAudioFile(
    val source: String,
    val file: String,
    val generation: String? = null,
)

object AudioSourceResolver {
    fun expandTemplate(template: String, term: String, reading: String): String =
        template
            .replace("{term}", term.urlEncode())
            .replace("{reading}", reading.urlEncode())
}

object LocalAudioResolver {
    private val supportedAudioExtensions = setOf("mp3", "opus", "ogg")
    private val sourceDisplayNames = mapOf(
        "nhk16" to "NHK16 %s",
        "daijisen" to "Daijisen %s",
        "shinmeikai8" to "SMK8 %s",
        "jpod" to "JPod101",
        "jpod_alternate" to "JPod101 Alt",
        "taas" to "TAAS",
        "ozk5" to "OZK5 %s",
        "forvo" to "Forvo (%s)",
        "forvo_ext" to "Forvo Ext",
        "forvo_ext2" to "Forvo Ext2",
    )

    fun resolve(
        term: String,
        reading: String,
        rows: List<LocalAudioEntry>,
        sourceOrder: List<String> = emptyList(),
        disabledSources: Set<String> = emptySet(),
        sourcesWithoutReadings: Set<String> = emptySet(),
    ): LocalAudioEntry? = rankedEntries(term, reading, rows, sourceOrder, disabledSources, sourcesWithoutReadings)
        .firstOrNull()?.entry

    fun resolveCandidates(
        term: String,
        reading: String,
        rows: List<LocalAudioEntry>,
        sourceOrder: List<String> = emptyList(),
        disabledSources: Set<String> = emptySet(),
        generation: String? = null,
        sourcesWithoutReadings: Set<String> = emptySet(),
    ): List<LocalAudioCandidate> =
        rankedEntries(term, reading, rows, sourceOrder, disabledSources, sourcesWithoutReadings)
            .map { (entry, match) ->
                LocalAudioCandidate(
                    name = entry.candidateName(match),
                    url = audioUrl(entry.source, entry.file, generation),
                )
            }
            .distinctBy(LocalAudioCandidate::url)

    private enum class Match { Exact, Reading, Expression }

    private data class MatchedEntry(val entry: LocalAudioEntry, val match: Match)

    private fun rankedEntries(
        term: String,
        reading: String,
        rows: List<LocalAudioEntry>,
        sourceOrder: List<String>,
        disabledSources: Set<String>,
        sourcesWithoutReadings: Set<String>,
    ): List<MatchedEntry> {
        val targetReading = katakanaToHiragana(reading).takeUnless(String::isBlank)
        val unlistedSources = rows.map { it.source }.toSet() - sourceOrder.toSet()
        val rankedSources = sourceOrder + LocalAudioSourceOrder.defaultOrder(unlistedSources)
        val sourceRank = rankedSources.withIndex().associate { it.value to it.index }
        return rows
            .asSequence()
            .filterNot { it.source in disabledSources }
            .filter { isSupportedAudioFile(it.file) }
            .mapNotNull { entry ->
                val expressionMatches = entry.expression == term
                val readingMatches = targetReading != null && entry.reading == targetReading
                val match = when {
                    expressionMatches && (targetReading == null || readingMatches ||
                        (entry.reading == null && entry.source in sourcesWithoutReadings)) -> Match.Exact
                    readingMatches -> Match.Reading
                    expressionMatches -> Match.Expression
                    else -> return@mapNotNull null
                }
                MatchedEntry(entry, match)
            }
            .sortedWith(
                compareBy<MatchedEntry> { it.match }
                    .thenBy { sourceRank[it.entry.source] ?: Int.MAX_VALUE }
                    .thenBy { it.entry.reading.orEmpty() },
            )
            .toList()
    }

    fun audioUrl(source: String, file: String, generation: String? = null): String =
        "$LOCAL_AUDIO_SCHEME://${source.urlEncode()}/${file.urlEncode()}" +
            (generation?.let { "?generation=${it.urlEncode()}" } ?: "")

    fun parseAudioUrl(url: String): LocalAudioFile? {
        if (!url.startsWith("$LOCAL_AUDIO_SCHEME://")) return null
        val tail = url.removePrefix("$LOCAL_AUDIO_SCHEME://").substringBefore('?')
        val slash = tail.indexOf('/')
        if (slash <= 0 || slash == tail.lastIndex) return null
        return LocalAudioFile(
            source = tail.substring(0, slash).urlDecode(),
            file = tail.substring(slash + 1).urlDecode(),
            generation = url.substringAfter('?', "").split('&')
                .firstOrNull { it.startsWith("generation=") }?.substringAfter('=')?.urlDecode(),
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

    private fun LocalAudioEntry.candidateName(match: Match): String {
        val template = sourceDisplayNames[source]
        val sourceName = when {
            template == null -> source
            "%s" in template -> template.replace("%s", display)
            else -> template
        }
        val matchDescription = when (match) {
            Match.Exact -> ""
            Match.Reading -> " ($expression)"
            Match.Expression -> reading?.let { " ($it)" }.orEmpty()
        }
        return sourceName.trim() + matchDescription
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
