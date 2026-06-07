package moe.antimony.hoshi.features.audio

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class AudioRequestHandler(
    private val localAudioRepository: LocalAudioRepository,
    private val fetchRemoteAudioList: (String) -> ByteArray = ::fetchRemoteAudioList,
    private val findLocalAudio: (term: String, reading: String) -> LocalAudioEntry? = localAudioRepository::findAudio,
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
        } else {
            fetchRemoteAudioList(target)
        }
    }

    private fun localAudioResponse(targetUrl: String): ByteArray {
        val uri = URI(targetUrl)
        val query = queryParameters(uri.rawQuery.orEmpty())
        val term = query["term"].orEmpty()
        val reading = query["reading"].orEmpty()
        val entry = findLocalAudio(term, reading) ?: return emptyAudioResponse()
        val audioUrl = LocalAudioResolver.audioUrl(entry.source, entry.file)
        return """{"type":"audioSourceList","audioSources":[{"name":${entry.source.jsonString()},"url":${audioUrl.jsonString()}}]}""".toByteArray()
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
