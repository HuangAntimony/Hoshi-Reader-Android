package moe.antimony.hoshi.features.audio

import android.webkit.WebResourceResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class AudioRequestHandler(
    private val localAudioRepository: LocalAudioRepository,
    private val remoteAudioRepository: RemoteWordAudioRepository,
    private val fetchRemoteAudioList: (String) -> ByteArray = ::fetchRemoteAudioList,
    private val findLocalAudioCandidates: (term: String, reading: String) -> List<LocalAudioCandidate> =
        localAudioRepository::findAudioCandidates,
) {
    fun handleAudioRequest(url: String): WebResourceResponse? {
        val body = handleAudioRequestBody(url) ?: return null
        return jsonResponse(body)
    }

    internal fun handleAudioRequestBody(url: String): ByteArray? {
        val uri = audioRequestUri(url) ?: return null
        val target = queryParameters(uri.rawQuery.orEmpty())["url"]
            ?: return emptyAudioResponse()

        return if (target.startsWith(AudioSettings.InternalLocalAudioUrl.substringBefore("?"))) {
            localAudioResponse(target)
        } else if (target.startsWith("${BuiltInAudioSource.Scheme}:", ignoreCase = true)) {
            builtInAudioResponse(target)
        } else {
            fetchRemoteAudioList(target)
        }
    }

    private fun builtInAudioResponse(target: String): ByteArray {
        val uri = runCatching { URI(target) }.getOrNull() ?: return emptyAudioResponse()
        val source = BuiltInAudioSource.entries.firstOrNull { it.id == uri.host } ?: return emptyAudioResponse()
        val query = runCatching { queryParameters(uri.rawQuery.orEmpty()) }.getOrNull() ?: return emptyAudioResponse()
        // WebView requires a synchronous interception response on its worker thread.
        // The repository owns the IO dispatcher; no UI or WebView state is accessed here.
        val candidates = runBlocking {
            remoteAudioRepository.resolve(source, query["term"].orEmpty(), query["reading"].orEmpty())
        }
        return buildJsonObject {
            put("type", "audioSourceList")
            put("audioSources", buildJsonArray {
                candidates.forEach { candidate ->
                    add(buildJsonObject {
                        put("name", candidate.name)
                        put("url", candidate.url)
                    })
                }
            })
        }.toString().toByteArray(Charsets.UTF_8)
    }

    private fun localAudioResponse(targetUrl: String): ByteArray {
        val uri = URI(targetUrl)
        val query = queryParameters(uri.rawQuery.orEmpty())
        val term = query["term"].orEmpty()
        val reading = query["reading"].orEmpty()
        val candidates = findLocalAudioCandidates(term, reading)
        if (candidates.isEmpty()) return emptyAudioResponse()
        val sources = candidates.joinToString(",") { candidate ->
            """{"name":${candidate.name.jsonString()},"url":${candidate.url.jsonString()}}"""
        }
        return """{"type":"audioSourceList","audioSources":[$sources]}""".toByteArray()
    }

    private fun jsonResponse(body: ByteArray): WebResourceResponse =
        WebResourceResponse(
            "application/json",
            "UTF-8",
            ByteArrayInputStream(body),
        ).apply {
            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
        }

    private fun emptyAudioResponse(): ByteArray =
        """{"type":"audioSourceList","audioSources":[]}""".toByteArray()

    private fun audioRequestUri(url: String): URI? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val isIosAudioScheme = uri.scheme == "audio"
        val isAndroidAudioEndpoint = uri.scheme == "https" &&
            uri.host == "appassets.androidplatform.net" &&
            uri.path == "/audio"
        if (!isIosAudioScheme && !isAndroidAudioEndpoint) return null
        return uri
    }

    private fun queryParameters(rawQuery: String): Map<String, String> =
        rawQuery
            .split('&')
            .filter { it.contains('=') }
            .associate { part ->
                val name = part.substringBefore('=')
                val value = java.net.URLDecoder.decode(part.substringAfter('='), Charsets.UTF_8.name())
                name to value
            }
}

private fun fetchRemoteAudioList(targetUrl: String): ByteArray =
    runCatching {
        val connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            requestMethod = "GET"
        }
        connection.inputStream.use { it.readBytes() }
    }.getOrElse {
        """{"type":"audioSourceList","audioSources":[]}""".toByteArray()
    }

private fun String.jsonString(): String =
    buildString {
        append('"')
        for (char in this@jsonString) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }
