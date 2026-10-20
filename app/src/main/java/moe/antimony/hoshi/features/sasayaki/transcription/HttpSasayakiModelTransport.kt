package moe.antimony.hoshi.features.sasayaki.transcription

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.suspendCancellableCoroutine

internal class SasayakiHttpException(val status: Int, message: String?) : IOException("HTTP $status ${message.orEmpty()}".trim()) {
    val retryable: Boolean get() = status == 408 || status == 429 || status in 500..599
}

internal class HttpSasayakiModelTransport(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : SasayakiModelTransport {
    override suspend fun open(url: String, offset: Long): SasayakiDownloadResponse {
        require(url.startsWith("https://"))
        require(offset >= 0)
        val connection = connect(URL(url))
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                    throw SasayakiHttpException(status, connection.responseMessage)
                }
                if (connection.url.protocol != "https") throw SasayakiDownloadValidationException("Insecure model redirect")
                var start = 0L
                var total = connection.contentLengthLong
                if (status == HttpURLConnection.HTTP_PARTIAL) {
                    val header = connection.getHeaderField("Content-Range").orEmpty()
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(header)
                    val values = range?.groupValues?.drop(1)?.map { it.toLongOrNull() }
                    val end = values?.get(1)
                    start = values?.get(0) ?: -1
                    total = values?.get(2) ?: -1
                    if (start != offset || end == null || end < start || end != total - 1 ||
                        connection.contentLengthLong >= 0 && connection.contentLengthLong != end - start + 1) {
                        throw SasayakiDownloadValidationException("Invalid Content-Range: $header")
                    }
                }
                val response = SasayakiDownloadResponse(connection.inputStream, connection.contentLengthLong,
                    start, total, connection::disconnect)
                continuation.resume(response) { _, value, _ -> value.close() }
            } catch (error: Exception) {
                connection.disconnect()
                continuation.resumeWith(Result.failure(error))
            }
        }
    }
}
