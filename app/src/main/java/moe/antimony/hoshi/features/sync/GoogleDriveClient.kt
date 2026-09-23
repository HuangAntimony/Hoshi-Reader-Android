package moe.antimony.hoshi.features.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import moe.antimony.hoshi.di.IoDispatcher

@Singleton
class GoogleDriveClient @Inject constructor(
    @ApplicationContext context: Context,
    private val auth: GoogleDriveAuth,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val connections = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())
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
        upload: Boolean = false,
    ): ByteArray = performRequest(
        url = driveUrl(path, query).let { if (upload) it.replace("/drive/v3/", "/upload/drive/v3/") else it },
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
    ): GoogleDriveFile {
        val metadata = buildJsonObject {
            put("name", name)
            if (fileId == null) put("parents", JsonArray(listOf(JsonPrimitive(parent))))
        }.toString()
        val boundary = UUID.randomUUID().toString()
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata".toByteArray())
            write("\r\n--$boundary\r\nContent-Type: $contentType\r\n\r\n".toByteArray())
            write(data)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        return SyncFormat.json.decodeFromString(request(
            path = fileId?.let { "files/${it.urlPathSegment()}" } ?: "files",
            query = mapOf("uploadType" to "multipart", "fields" to "id,name,mimeType,version,createdTime"),
            method = if (fileId == null) "POST" else "PATCH",
            body = body,
            contentType = "multipart/related; boundary=$boundary",
            upload = true,
        ).decodeToString())
    }

    suspend fun trashFile(fileId: String) {
        request("files/${fileId.urlPathSegment()}", method = "PATCH", body = "{\"trashed\":true}".toByteArray())
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
        perform(url, "GET", null) { connection ->
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
        writer: (OutputStream) -> Unit,
    ): ByteArray = perform(url, method, contentType, configure = { connection ->
        connection.setFixedLengthStreamingMode(contentLength)
        connection.doOutput = true
        connection.outputStream.use(writer)
    }) { connection -> connection.inputStream.use { it.readBytes() } }

    private suspend fun perform(
        url: String,
        method: String,
        contentType: String?,
        retry: Boolean = true,
        configure: (HttpURLConnection) -> Unit = {},
        read: (HttpURLConnection) -> ByteArray,
    ): ByteArray {
        if (isStopped) throw CancellationException()
        checkValidatedInternet()
        val connection = connectionId
        val token = auth.accessToken()
        val timeout = if (auth.provider() == SyncProvider.Ttu) 10_000 else 60_000
        val request = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeout
            readTimeout = timeout
            setRequestProperty("Authorization", "Bearer $token")
            contentType?.let { setRequestProperty("Content-Type", it) }
        }
        val (status, data) = coroutineScope {
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
        }
        checkConnection(connection)
        currentCoroutineContext().ensureActive()
        if (status == 401 && retry) {
            auth.clearAccessToken(token)
            checkConnection(connection)
            currentCoroutineContext().ensureActive()
            return perform(url, method, contentType, false, configure, read)
        }
        if (status >= 400) {
            val message = runCatching {
                SyncFormat.json.parseToJsonElement(data.decodeToString()).jsonObject["error"]?.jsonObject
                    ?.get("message")?.jsonPrimitive?.content
            }.getOrNull()
            throw GoogleDriveApiException(message ?: "Request failed with status $status", status)
        }
        return data
    }

    private fun checkValidatedInternet() {
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
