package moe.antimony.hoshi.features.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import moe.antimony.hoshi.di.IoDispatcher
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/** Hoshi payloads only; metadata and TTU retain their existing transport. */
@Singleton
class GoogleDriveFileTransfer internal constructor(
    private val auth: DriveAccessTokenProvider,
    private val sessions: DriveUploadSessions,
    private val io: CoroutineDispatcher,
    client: OkHttpClient,
    private val endpoint: String = "https://www.googleapis.com/upload/drive/v3/files",
    private val checkInternet: () -> Unit,
    private val retryDelay: suspend (Int) -> Unit = { delay(1_000L * (1L shl it) + Random.nextLong(1_000)) },
) : DriveFileTransfer {
    @Inject constructor(@ApplicationContext context: Context, auth: GoogleDriveAuth, @IoDispatcher io: CoroutineDispatcher) : this(
        auth, DriveUploadSessions(context.noBackupFilesDir.resolve("drive-uploads")), io,
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build(), checkInternet = { checkValidatedInternet(context) },
    )
    private val client = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
    private val uploadClient = this.client.newBuilder().callTimeout(5, TimeUnit.MINUTES).build()

    override suspend fun upload(file: File, request: DriveUploadRequest, progress: (DriveTransferUpdate) -> Unit): GoogleDriveFile = withContext(io) {
        val size = file.length()
        val modified = file.lastModified()
        var session = sessions.get(request, file)
        session?.completed?.let { return@withContext it }
        var query = session != null
        var offset = 0L
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!file.isFile || file.length() != size || file.lastModified() != modified) {
                sessions.remove(request)
                throw IOException("Upload source changed during transfer")
            }
            try {
                if (session == null) {
                    progress(DriveTransferUpdate(0, size, phase = SyncTransferPhase.Preparing))
                    val metadata = buildJsonObject { put("name", request.name); put("parents", JsonArray(listOf(JsonPrimitive(request.parent)))) }
                    val result = bytes(Request.Builder().url("$endpoint?uploadType=resumable&fields=id,name,mimeType,md5Checksum,size,createdTime")
                        .header("X-Upload-Content-Type", "application/octet-stream").header("X-Upload-Content-Length", "$size")
                        .post(metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType())))
                    requireSuccess(result)
                    val location = result.headers["Location"] ?: throw IOException("Drive upload response has no session location")
                    validateSessionLocation(location)
                    session = DriveUploadSession(request, file.absolutePath, size, modified, location)
                    sessions.put(session)
                    query = false
                    offset = 0
                }
                val held = session
                val response = if (query) {
                    progress(DriveTransferUpdate(offset, size, offset, SyncTransferPhase.Retrying))
                    bytes(Request.Builder().url(held.url).header("Content-Range", "bytes */$size").put(byteArrayOf().toRequestBody()))
                } else {
                    val start = offset
                    val length = minOf(1024L * 1024, size - start)
                    val body = object : RequestBody() {
                        override fun contentType() = "application/octet-stream".toMediaType()
                        override fun contentLength() = length
                        override fun writeTo(sink: BufferedSink) {
                            RandomAccessFile(file, "r").use { source ->
                                source.seek(start)
                                var written = 0L
                                val buffer = ByteArray(8192)
                                while (written < length) {
                                    val count = source.read(buffer, 0, minOf(buffer.size.toLong(), length - written).toInt())
                                    if (count < 0) throw IOException("Upload source ended unexpectedly")
                                    sink.write(buffer, 0, count)
                                    sink.emitCompleteSegments()
                                    written += count
                                    progress(DriveTransferUpdate(start + written, size, start, deltaBytes = count.toLong()))
                                }
                            }
                            progress(DriveTransferUpdate(start + length, size, start, SyncTransferPhase.AwaitingResponse))
                        }
                    }
                    val range = if (size == 0L) "bytes */0" else "bytes $start-${start + length - 1}/$size"
                    bytes(Request.Builder().url(held.url).header("Content-Range", range).put(body))
                }
                when (response.code) {
                    200, 201 -> {
                        val completed = SyncFormat.json.decodeFromString<GoogleDriveFile>(response.body)
                        sessions.put(held.copy(completed = completed))
                        progress(DriveTransferUpdate(size, size, size, SyncTransferPhase.AwaitingResponse))
                        return@withContext completed
                    }
                    308 -> {
                        val next = confirmedOffset(response.headers["Range"], size)
                        if (next > offset) failures = 0
                        else if (!query) throw IOException("Drive upload made no confirmed progress")
                        offset = next
                        query = next == size
                        progress(DriveTransferUpdate(offset, size, offset))
                        if (query) throw IOException("Drive has not finalized the upload")
                    }
                    404, 410 -> {
                        if (failures++ >= 4) throw GoogleDriveApiException("Drive upload session repeatedly expired", response.code)
                        sessions.remove(request)
                        session = null
                        query = false
                        offset = 0
                        retryDelay(failures - 1)
                    }
                    else -> requireSuccess(response)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                val retryable = error is IOException || (error is GoogleDriveApiException && (error.isRateLimited || (error.statusCode ?: 0) >= 500))
                if (!retryable || failures >= 4) {
                    if (error is IOException) throw GoogleDriveUnavailableException(error)
                    throw error
                }
                progress(DriveTransferUpdate(offset, size, offset, SyncTransferPhase.Retrying))
                retryDelay(failures++)
                query = session != null
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    override suspend fun download(url: String, destination: File, size: Long?, progress: (DriveTransferUpdate) -> Unit) = withContext(io) {
        var failures = 0
        while (true) {
            try {
                execute(Request.Builder().url(url), upload = false) { response, _ ->
                    if (!response.isSuccessful) throw apiError(response.code, response.body?.string().orEmpty())
                    val body = response.body ?: throw IOException("Drive download has no response body")
                    val total = body.contentLength().takeIf { it >= 0 } ?: size
                    var received = 0L
                    body.byteStream().use { input ->
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count == -1) break
                                output.write(buffer, 0, count)
                                received += count
                                progress(DriveTransferUpdate(received, total, received, deltaBytes = count.toLong()))
                            }
                        }
                    }
                    if (total != null && received != total) throw IOException("Incomplete Drive download: $received of $total bytes")
                    progress(DriveTransferUpdate(received, total, received, SyncTransferPhase.AwaitingResponse))
                }
                return@withContext
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                val retryable = error is IOException || (error is GoogleDriveApiException && (error.isRateLimited || (error.statusCode ?: 0) >= 500))
                if (!retryable || failures >= 4) {
                    if (error is IOException) throw GoogleDriveUnavailableException(error)
                    throw error
                }
                progress(DriveTransferUpdate(0, size, phase = SyncTransferPhase.Retrying))
                retryDelay(failures++)
            }
        }
    }

    override suspend fun acknowledge(request: DriveUploadRequest) = withContext(io) { sessions.remove(request) }
    override suspend fun discardObsolete(keep: (DriveUploadRequest) -> Boolean) = withContext(io) { sessions.discardObsolete(keep) }
    override suspend fun reset() = withContext(io) { sessions.reset() }

    private data class Reply(val code: Int, val headers: Headers, val body: String)
    private suspend fun bytes(builder: Request.Builder): Reply = execute(builder, upload = true) { response, token ->
        val body = response.body?.string().orEmpty()
        Reply(response.code, response.headers, if (response.code >= 400) redactText(body, response.request.url.toString(), token) else body)
    }

    private suspend fun <T> execute(builder: Request.Builder, upload: Boolean, read: (Response, String) -> T): T {
        checkInternet()
        var refreshed = false
        while (true) {
            val token = auth.accessToken()
            val request = builder.header("Authorization", "Bearer $token").build()
            try {
                return coroutineScope {
                    suspendCancellableCoroutine { continuation ->
                        val call = (if (upload) uploadClient else client).newCall(request)
                        // Join the response consumer before callers clean up files or transfer state.
                        val consumer = launch(io) {
                            try {
                                val result = call.execute().use {
                                    if (it.code == 401) throw RefreshToken()
                                    read(it, token)
                                }
                                continuation.resume(result)
                            } catch (error: Exception) {
                                continuation.resumeWithException(redact(error, request.url.toString(), token))
                            }
                        }
                        continuation.invokeOnCancellation {
                            call.cancel()
                            consumer.cancel()
                        }
                    }
                }
            } catch (error: RefreshToken) {
                if (refreshed) throw DriveAuthorizationRequiredException()
                auth.clearAccessToken(token)
                refreshed = true
            }
        }
    }

    private class RefreshToken : Exception()
    private fun requireSuccess(reply: Reply) {
        if (reply.code !in 200..299) throw apiError(reply.code, reply.body)
    }
    private fun apiError(status: Int, body: String): GoogleDriveApiException {
        val error = runCatching { SyncFormat.json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
        val message = runCatching { error?.get("message")?.jsonPrimitive?.content }.getOrNull()
        val reasons = runCatching { error?.get("errors")?.jsonArray?.mapNotNull { it.jsonObject["reason"]?.jsonPrimitive?.content }?.toSet() }.getOrNull().orEmpty()
        return GoogleDriveApiException(redactText(message ?: "Drive request failed", "", ""), status, reasons)
    }
    private fun validateSessionLocation(location: String) {
        val uri = runCatching { java.net.URI(location) }.getOrNull()
        val origin = java.net.URI(endpoint)
        if (uri == null || uri.userInfo != null || uri.host != origin.host || uri.scheme != origin.scheme || uri.port != origin.port) {
            throw IOException("Invalid Drive upload session location")
        }
    }
    private fun redactText(text: String, url: String, token: String): String {
        var result = text.replace(Regex("https?://[^\\s\"<>]+"), "[redacted URL]")
        if (url.isNotEmpty()) result = result.replace(url, "[redacted URL]")
        if (token.isNotEmpty()) result = result.replace(token, "[redacted]")
        return result
    }
    private fun redact(error: Exception, url: String, token: String): Exception {
        if (error is RefreshToken || error is CancellationException) return error
        val message = redactText(error.message ?: error.javaClass.simpleName, url, token)
        val clean = when (error) {
            is GoogleDriveApiException -> GoogleDriveApiException(message, error.statusCode, error.reasons.map { redactText(it, url, token) }.toSet())
            is java.net.SocketTimeoutException -> java.net.SocketTimeoutException(message)
            is IOException -> IOException(message)
            else -> IOException(message)
        }
        clean.stackTrace = error.stackTrace
        (error.cause as? Exception)?.takeIf { it !== error }?.let { clean.initCause(redact(it, url, token)) }
        return clean
    }
}

internal fun confirmedOffset(range: String?, size: Long): Long {
    if (range == null) return 0
    val last = Regex("bytes=0-([0-9]+)").matchEntire(range)?.groupValues?.get(1)?.toLongOrNull()
        ?: throw IOException("Invalid Drive upload acknowledgement")
    if (last < 0 || last >= size) throw IOException("Drive upload acknowledgement exceeds file length")
    return last + 1
}
