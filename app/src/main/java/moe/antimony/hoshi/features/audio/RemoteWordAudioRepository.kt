package moe.antimony.hoshi.features.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

data class WordAudioCandidate(val url: String, val name: String = "")
data class AudioHttpResponse(val url: String, val status: Int, val contentType: String, val body: ByteArray)
fun interface AudioHttpClient {
    fun request(url: String, form: Map<String, String>?): AudioHttpResponse
}

class RemoteWordAudioRepository(
    private val http: AudioHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
    private val digest: (ByteArray) -> String = ::audioSha256,
) {
    suspend fun resolve(source: BuiltInAudioSource, term: String, reading: String): List<WordAudioCandidate> =
        withContext(ioDispatcher) {
            try {
                when (source) {
                    BuiltInAudioSource.JapanesePod101 -> japanesePod101(term, reading)
                    BuiltInAudioSource.LanguagePod101 -> languagePod101(term, reading)
                    BuiltInAudioSource.Jisho -> jisho(term, reading)
                }.map { WordAudioCandidate(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }

    private fun japanesePod101(term: String, reading: String): List<String> {
        // Match Yomitan's kana-only query rule (U+3040..U+30FF, not half-width kana).
        val kanaOnly = term.isNotEmpty() && term == reading && term.all { it.code in 0x3040..0x30ff }
        val query = buildMap {
            if (term.isNotEmpty() && !kanaOnly) put("kanji", term)
            if (reading.isNotEmpty()) put("kana", reading)
        }
        if (query.isEmpty()) return emptyList()
        val url = "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?${query.audioFormEncode()}"
        val response = http.request(url, null)
        if (response.status !in 200..299 || !response.isMp3()) return emptyList()
        // This endpoint returns an MP3 placeholder rather than a 404 when no recording exists.
        if (digest(response.body) == "ae6398b5a27bc8c0a771df6c907ade794be15518174773c58c7c7ddd17098906") {
            return emptyList()
        }
        return listOf(url)
    }

    private fun languagePod101(term: String, reading: String): List<String> {
        val response = http.request(
            "https://www.japanesepod101.com/learningcenter/reference/dictionary_post",
            linkedMapOf("post" to "dictionary_reference", "match_type" to "exact", "search_query" to term, "vulgar" to "true"),
        )
        val document = response.document() ?: return emptyList()
        return document.getElementsByClass("dc-result-row").mapNotNull { row ->
            val htmlReading = row.getElementsByClass("dc-vocab_kana").firstOrNull()?.wholeText()
            if (htmlReading.isNullOrEmpty() || (reading != term && reading != htmlReading)) return@mapNotNull null
            row.selectFirst("audio source")?.attr("src")?.let { resolveAudioUrl(response.url, it) }
        }.distinct()
    }

    private fun jisho(term: String, reading: String): List<String> {
        val encodedTerm = URLEncoder.encode(term, Charsets.UTF_8.name()).replace("+", "%20")
        val response = http.request("https://jisho.org/search/$encodedTerm", null)
        val document = response.document() ?: return emptyList()
        val source = document.getElementById("audio_$term:$reading")?.getElementsByTag("source")?.firstOrNull()
        return listOfNotNull(source?.attr("src")?.let { resolveAudioUrl(response.url, it) })
    }
}

private fun AudioHttpResponse.isMp3(): Boolean {
    val mime = contentType.substringBefore(';').trim().lowercase()
    if (mime.isNotEmpty() && mime != "application/octet-stream" && !mime.startsWith("audio/")) return false
    return (body.size >= 3 && body[0] == 0x49.toByte() && body[1] == 0x44.toByte() && body[2] == 0x33.toByte()) ||
        (body.size >= 2 && body[0] == 0xff.toByte() && (body[1].toInt() and 0xe0) == 0xe0)
}

private fun AudioHttpResponse.document(): Document? {
    if (status !in 200..299 || body.isEmpty()) return null
    val charset = contentType.split(';').drop(1).firstNotNullOfOrNull { parameter ->
        parameter.trim().takeIf { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() }
    }
    return body.inputStream().use { Jsoup.parse(it, charset, url) }
}

private fun resolveAudioUrl(base: String, value: String): String? = runCatching {
    if (value.isBlank()) return null
    val resolved = URI(base).resolve(value.trim())
    resolved.takeIf { it.scheme in setOf("http", "https") && !it.host.isNullOrEmpty() }?.toASCIIString()
}.getOrNull()

internal fun Map<String, String>.audioFormEncode(): String = entries.joinToString("&") { (key, value) ->
    "${URLEncoder.encode(key, Charsets.UTF_8.name())}=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
}

internal fun audioSha256(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
