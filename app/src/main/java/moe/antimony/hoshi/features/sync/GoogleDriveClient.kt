package moe.antimony.hoshi.features.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import moe.antimony.hoshi.di.IoDispatcher

@Singleton
class GoogleDriveClient internal constructor(
    private val auth: DriveAccessTokenProvider,
    private val provider: suspend () -> SyncProvider,
    private val checkInternet: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher,
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        auth: GoogleDriveAuth,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(auth, auth::provider, { checkValidatedInternet(context) }, ioDispatcher)
    private val connections = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())
    private val limit = Semaphore(8)
    @Volatile private var isStopped = false
    @Volatile var connectionId = 0
        private set

    fun stop() {
        isStopped = true
        connectionId += 1
        synchronized(connections) { connections.forEach { it.disconnect() } }
    }

    fun resume() {
        isStopped = false
    }

    fun checkConnection(connection: Int) {
        if (connection != connectionId) throw CancellationException()
    }

    suspend fun request(
        path: String,
        query: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: ByteArray? = null,
        contentType: String? = "application/json",
    ): ByteArray = performRequest(
        url = driveUrl(path, query),
        method = method,
        body = body,
        contentType = contentType,
    )

    suspend fun write(
        data: ByteArray,
        name: String,
        parent: String,
        fileId: String? = null,
        contentType: String = "application/octet-stream",
    ): GoogleDriveFile = write(data.size.toLong(), name, parent, fileId, contentType) { it.write(data) }

    suspend fun write(file: File, name: String, parent: String): GoogleDriveFile =
        write(file.length(), name, parent, limited = false) { output -> file.inputStream().use { it.copyTo(output) } }

    private suspend fun write(
        length: Long,
        name: String,
        parent: String,
        fileId: String? = null,
        contentType: String = "application/octet-stream",
        limited: Boolean = true,
        writer: (OutputStream) -> Unit,
    ): GoogleDriveFile {
        val metadata = buildJsonObject {
            put("name", name)
            if (fileId == null) put("parents", JsonArray(listOf(JsonPrimitive(parent))))
        }.toString()
        val boundary = UUID.randomUUID().toString()
        val prefix = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n--$boundary\r\nContent-Type: $contentType\r\n\r\n".toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        return SyncFormat.json.decodeFromString(performStreamingUpload(
            url = driveUrl(
                fileId?.let { "files/${it.urlPathSegment()}" } ?: "files",
                mapOf("uploadType" to "multipart", "fields" to "id,name,mimeType,md5Checksum,createdTime"),
            ).replace("/drive/v3/", "/upload/drive/v3/"),
            method = if (fileId == null) "POST" else "PATCH",
            contentType = "multipart/related; boundary=$boundary",
            contentLength = prefix.size + length + suffix.size,
            limited = limited,
        ) { output ->
            output.write(prefix)
            writer(output)
            output.write(suffix)
        }.decodeToString())
    }

    suspend fun trashFile(fileId: String) {
        request("files/${fileId.urlPathSegment()}", method = "PATCH", body = "{\"trashed\":true}".toByteArray())
    }

    suspend fun renameFile(fileId: String, name: String) {
        request("files/${fileId.urlPathSegment()}", method = "PATCH", body = buildJsonObject { put("name", name) }.toString().toByteArray())
    }

    suspend fun deleteFile(fileId: String) {
        request("files/${fileId.urlPathSegment()}", method = "DELETE", contentType = null)
    }

    suspend fun performRequest(
        url: String,
        method: String,
        body: ByteArray? = null,
        contentType: String? = null,
    ): ByteArray = perform(url, method, contentType, configure = { connection ->
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }
    }) { connection -> connection.inputStream.use { it.readBytes() } }

    suspend fun performDownload(
        url: String,
        destination: File,
        progress: (Long, Long?) -> Unit,
    ) {
        perform(url, "GET", null, limited = false) { connection ->
            destination.parentFile?.mkdirs()
            val total = connection.contentLengthLong.takeIf { it >= 0 }
            var downloaded = 0L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        progress(downloaded, total)
                    }
                }
            }
            progress(downloaded, total)
            byteArrayOf()
        }
    }

    suspend fun performStreamingUpload(
        url: String,
        method: String,
        contentType: String,
        contentLength: Long,
        limited: Boolean = true,
        writer: (OutputStream) -> Unit,
    ): ByteArray = perform(url, method, contentType, limited = limited, configure = { connection ->
        connection.setFixedLengthStreamingMode(contentLength)
        connection.doOutput = true
        connection.outputStream.use(writer)
    }) { connection -> connection.inputStream.use { it.readBytes() } }

    private suspend fun perform(
        url: String,
        method: String,
        contentType: String?,
        retry: Boolean = true,
        attempt: Int = 0,
        limited: Boolean = true,
        configure: (HttpURLConnection) -> Unit = {},
        read: (HttpURLConnection) -> ByteArray,
    ): ByteArray {
        if (isStopped) throw CancellationException()
        unavailable { checkInternet() }
        val connection = connectionId
        val token = unavailable { auth.accessToken() }
        val timeout = if (provider() == SyncProvider.Ttu) 10_000 else 60_000
        val request = openConnection(url).apply {
            requestMethod = method
            connectTimeout = timeout
            readTimeout = timeout
            setRequestProperty("Authorization", "Bearer $token")
            contentType?.let { setRequestProperty("Content-Type", it) }
        }
        val (status, data) = limit.withPermitIf(limited) { coroutineScope {
            suspendCancellableCoroutine { continuation ->
                val job = launch(ioDispatcher) {
                    continuation.resumeWith(runCatching {
                        connections.add(request)
                        try {
                            checkConnection(connection)
                            ensureActive()
                            configure(request)
                            val status = request.responseCode
                            status to if (status >= 400) request.errorStream?.use { it.readBytes() } ?: byteArrayOf() else read(request)
                        } catch (error: IOException) {
                            throw GoogleDriveUnavailableException(error)
                        } finally {
                            connections.remove(request)
                            request.disconnect()
                        }
                    })
                }
                continuation.invokeOnCancellation {
                    request.disconnect()
                    job.cancel()
                }
            }
        } }
        checkConnection(connection)
        currentCoroutineContext().ensureActive()
        if (status == 401 && retry) {
            auth.clearAccessToken(token)
            checkConnection(connection)
            currentCoroutineContext().ensureActive()
            return perform(url, method, contentType, false, attempt, limited, configure, read)
        }
        if (status >= 400) {
            val error = runCatching { SyncFormat.json.parseToJsonElement(data.decodeToString()).jsonObject["error"]?.jsonObject }.getOrNull()
            val reasons = runCatching {
                error?.get("errors")?.jsonArray?.mapNotNull { it.jsonObject["reason"]?.jsonPrimitive?.content }?.toSet()
            }.getOrNull().orEmpty()
            val message = runCatching { error?.get("message")?.jsonPrimitive?.content }.getOrNull()
            val failure = GoogleDriveApiException(message ?: "Request failed with status $status", status, reasons)
            if (attempt < 4 && (failure.isRateLimited || (status >= 500 && method != "POST"))) {
                delay(1_000L * (1L shl attempt) + Random.nextLong(1_000))
                checkConnection(connection)
                return perform(url, method, contentType, retry, attempt + 1, limited, configure, read)
            }
            throw failure
        }
        return data
    }

    private inline fun <T> unavailable(action: () -> T): T = try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw GoogleDriveUnavailableException(error)
    }

}

internal fun driveUrl(endpoint: String, queryParameters: Map<String, String>): String {
    val query = queryParameters.entries.joinToString("&") { (name, value) ->
        "${name.urlQueryComponent()}=${value.urlQueryComponent()}"
    }
    return "https://www.googleapis.com/drive/v3/$endpoint?$query"
}

internal fun String.urlQueryComponent(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name())

internal fun String.urlPathSegment(): String =
    split("/").joinToString("/") { it.urlQueryComponent() }

private fun checkValidatedInternet(context: Context) {
    val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    val network = connectivityManager?.activeNetwork
        ?: throw GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage)
    val capabilities = connectivityManager.getNetworkCapabilities(network)
        ?: throw GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage)
    if (!shouldAttemptDriveRequest(
            hasActiveNetwork = true,
            hasInternetCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            hasValidatedCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    ) {
        throw GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage)
    }
}

private suspend fun <T> Semaphore.withPermitIf(limited: Boolean, action: suspend () -> T): T =
    if (limited) withPermit { action() } else action()
