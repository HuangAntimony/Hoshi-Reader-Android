package moe.antimony.hoshi.features.sasayaki.transcription

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.suspendCancellableCoroutine

internal class HttpSasayakiModelTransport : SasayakiModelTransport {
    override suspend fun open(url: String): SasayakiDownloadResponse {
        require(url.startsWith("https://"))
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept-Encoding", "identity")
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Model server returned HTTP ${connection.responseCode}")
                if (connection.url.protocol != "https") throw IOException("Insecure model redirect")
                val response = SasayakiDownloadResponse(connection.inputStream, connection.contentLengthLong, connection::disconnect)
                continuation.resume(response) { _, value, _ -> value.close() }
            } catch (error: Exception) {
                connection.disconnect()
                continuation.resumeWith(Result.failure(error))
            }
        }
    }
}
