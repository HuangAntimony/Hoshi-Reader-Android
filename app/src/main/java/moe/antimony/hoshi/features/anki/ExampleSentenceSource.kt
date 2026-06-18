package moe.antimony.hoshi.features.anki

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A candidate example sentence plus the character ranges Massif highlighted (the matched word). */
internal data class ExampleSentence(
    val text: String,
    val highlights: List<IntRange> = emptyList(),
)

internal interface ExampleSentenceSource {
    suspend fun candidates(term: String, limit: Int = MASSIF_DEFAULT_LIMIT): List<ExampleSentence>
}

internal class MassifExampleSentenceSource : ExampleSentenceSource {
    override suspend fun candidates(term: String, limit: Int): List<ExampleSentence> =
        withContext(Dispatchers.IO) {
            if (term.isBlank()) return@withContext emptyList()
            runCatching {
                val url = "$MASSIF_SEARCH_URL?q=${URLEncoder.encode(term, "UTF-8")}"
                val body = (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = MASSIF_TIMEOUT_MS
                    readTimeout = MASSIF_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", MASSIF_USER_AGENT)
                    try {
                        if (responseCode !in 200..299) return@run null
                        inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    } finally {
                        disconnect()
                    }
                } ?: return@withContext emptyList()
                parseMassifSentences(body, limit)
            }.getOrDefault(emptyList())
        }
}

private const val MASSIF_SEARCH_URL = "https://massif.la/ja/search"
private const val MASSIF_TIMEOUT_MS = 5_000
private const val MASSIF_DEFAULT_LIMIT = 20
private const val MASSIF_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

// Lazy stop at first </div>; sentence content has <em> but no nested divs.
private val MASSIF_LI_REGEX =
    Regex("""<li\b[^>]*\bclass="text-japanese"[^>]*>\s*<div>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
private val EM_OPEN_REGEX = Regex("""<em\b[^>]*>""")
private val EM_CLOSE_REGEX = Regex("""</em\s*>""")
private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
private val NUMERIC_ENTITY_REGEX = Regex("""&#(x?)([0-9A-Fa-f]+);""")

// Control-char sentinels for Massif's <em> bounds; survive tag-strip + entity-decode, then
// extractHighlights() converts their positions into ranges on the final plain text.
private const val EM_OPEN = '\u0000'
private const val EM_CLOSE = '\u0001'

internal fun parseMassifSentences(body: String, limit: Int): List<ExampleSentence> {
    if (limit <= 0) return emptyList()
    val out = mutableListOf<ExampleSentence>()
    for (match in MASSIF_LI_REGEX.findAll(body)) {
        val marked = match.groupValues[1]
            .replace(EM_OPEN_REGEX, EM_OPEN.toString())
            .replace(EM_CLOSE_REGEX, EM_CLOSE.toString())
        val cleaned = unescapeHtmlEntities(HTML_TAG_REGEX.replace(marked, "")).trim()
        val sentence = extractHighlights(cleaned)
        if (sentence.text.isNotEmpty()) out.add(sentence)
        if (out.size >= limit) break
    }
    return out
}

/** Strips the [EM_OPEN]/[EM_CLOSE] sentinels, returning the plain text and the bold ranges. */
private fun extractHighlights(marked: String): ExampleSentence {
    if (EM_OPEN !in marked) return ExampleSentence(marked)
    val sb = StringBuilder(marked.length)
    val ranges = mutableListOf<IntRange>()
    var openStart = -1
    for (ch in marked) {
        when (ch) {
            EM_OPEN -> openStart = sb.length
            EM_CLOSE -> {
                if (openStart in 0 until sb.length) ranges.add(openStart until sb.length)
                openStart = -1
            }
            else -> sb.append(ch)
        }
    }
    return ExampleSentence(sb.toString(), ranges)
}

/** Minimal pure-JVM HTML entity decode (numeric + the few named entities Massif emits). */
internal fun unescapeHtmlEntities(input: String): String {
    val numeric = NUMERIC_ENTITY_REGEX.replace(input) { match ->
        val radix = if (match.groupValues[1] == "x") 16 else 10
        match.groupValues[2].toIntOrNull(radix)?.let { String(Character.toChars(it)) } ?: match.value
    }
    // Named-entity fallback; numeric forms (incl. &#39;) handled above by NUMERIC_ENTITY_REGEX
    return numeric
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
