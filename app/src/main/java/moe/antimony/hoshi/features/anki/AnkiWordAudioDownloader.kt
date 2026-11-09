package moe.antimony.hoshi.features.anki

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class AnkiWordAudioDownloader @Inject constructor() {
    suspend fun download(url: String): ByteArray {
        val source = URL(url)
        if (source.protocol != "http" && source.protocol != "https") {
            return source.openConnection().apply {
                connectTimeout = SocketTimeoutMillis
                readTimeout = SocketTimeoutMillis
            }.getInputStream().use { it.readBytes() }
        }
        return withTimeoutOrNull(TotalTimeoutMillis) { downloadHttpAudio(url) }
            ?: throw SocketTimeoutException("Word audio download timed out")
    }

    private suspend fun downloadHttpAudio(url: String): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching {
                        response.use {
                            if (!it.isSuccessful) throw IOException("Word audio download failed (HTTP ${it.code})")
                            it.body?.bytes() ?: throw IOException("Word audio response has no body")
                        }
                    })
                }
            })
        }

    private companion object {
        const val SocketTimeoutMillis = 4_000
        const val TotalTimeoutMillis = 15_000L
        val client = OkHttpClient.Builder()
            .connectTimeout(SocketTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(SocketTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(TotalTimeoutMillis, TimeUnit.MILLISECONDS)
            .followSslRedirects(false)
            .build()
    }
}
