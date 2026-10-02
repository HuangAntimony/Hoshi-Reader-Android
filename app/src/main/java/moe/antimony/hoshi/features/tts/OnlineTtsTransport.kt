package moe.antimony.hoshi.features.tts

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okio.ByteString

@Singleton
class OnlineTtsTransport internal constructor(private val client: OkHttpClient,
    private val customClient: OkHttpClient = client.newBuilder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) {
    @Inject constructor() : this(OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build())

    internal suspend fun request(request: Request, selfHosted: Boolean = false): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = (if (selfHosted) customClient else client).newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(TtsException(if (e is InterruptedIOException) TtsFailure.Timeout else TtsFailure.Network))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (!it.isSuccessful) throw TtsException(httpFailure(it.code), statusCode = it.code)
                        val body = it.body ?: throw TtsException(TtsFailure.InvalidResponse)
                        if (body.contentLength() > maxResponseBytes) throw TtsException(TtsFailure.InvalidResponse)
                        body.byteStream().use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (output.size() + read > maxResponseBytes) throw TtsException(TtsFailure.InvalidResponse)
                                output.write(buffer, 0, read)
                            }
                            output.toByteArray()
                        }
                    }
                    continuation.resume(bytes)
                } catch (error: Exception) {
                    continuation.resumeWithException(if (error is TtsException) error else TtsException(
                        if (error is InterruptedIOException) TtsFailure.Timeout else TtsFailure.Network))
                }
            }
        })
    }

    internal suspend fun edgeAudio(request: Request, ssml: String): ByteArray = withTimeout(60_000) {
        val complete = CompletableDeferred<ByteArray>()
        val buffer = ByteArrayOutputStream()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val timestamp = DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
                    .withZone(ZoneOffset.UTC).format(Instant.now())
                val config = "X-Timestamp:$timestamp\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                val speech = "X-RequestId:${UUID.randomUUID().toString().replace("-", "")}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${timestamp}Z\r\nPath:ssml\r\n\r\n$ssml"
                if (!webSocket.send(config) || !webSocket.send(speech)) complete.completeExceptionally(TtsException(TtsFailure.Network))
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val audio = OnlineTtsProtocol.edgeAudio(bytes.toByteArray())
                    if (buffer.size() + audio.size > maxResponseBytes) throw TtsException(TtsFailure.InvalidResponse)
                    buffer.write(audio)
                } catch (error: Exception) { complete.completeExceptionally(error); webSocket.cancel() }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val path = text.substringBefore("\r\n\r\n").lineSequence().firstOrNull { it.startsWith("Path:") }?.trim()
                if (path == "Path:turn.end") {
                    if (buffer.size() == 0) complete.completeExceptionally(TtsException(TtsFailure.InvalidResponse))
                    else complete.complete(buffer.toByteArray())
                    webSocket.close(1000, null)
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                complete.completeExceptionally(TtsException(TtsFailure.InvalidResponse))
                webSocket.close(code, null)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                complete.completeExceptionally(TtsException(response?.let { httpFailure(it.code) } ?: TtsFailure.Network))
            }
        })
        try { complete.await() } finally { socket.cancel() }
    }

    companion object {
        private const val maxResponseBytes = 16 * 1024 * 1024
        internal fun httpFailure(code: Int) = when (code) {
            401, 403 -> TtsFailure.Authentication
            429 -> TtsFailure.RateLimited
            in 500..599 -> TtsFailure.Network
            else -> TtsFailure.InvalidResponse
        }
    }
}
