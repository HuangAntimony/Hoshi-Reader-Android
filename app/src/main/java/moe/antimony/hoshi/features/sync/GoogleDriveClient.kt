package moe.antimony.hoshi.features.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

data class DriveHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
)

data class DriveHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
)

interface DriveHttpTransport {
    fun execute(request: DriveHttpRequest): DriveHttpResponse
}

class UrlConnectionDriveHttpTransport : DriveHttpTransport {
    override fun execute(request: DriveHttpRequest): DriveHttpResponse {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            request.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (request.body.isNotEmpty()) {
                doOutput = true
                outputStream.use { it.write(request.body) }
            }
        }
        val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
        return DriveHttpResponse(
            statusCode = connection.responseCode,
            body = stream?.use { it.readBytes() } ?: ByteArray(0),
        )
    }
}

interface DriveFolderCache {
    var rootFolderId: String?
    var titleToFolderId: MutableMap<String, String>

    fun clear()
}

class GoogleDriveClient(
    private val transport: DriveHttpTransport = UrlConnectionDriveHttpTransport(),
    private val cache: DriveFolderCache,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun findOrCreateRootFolder(accessToken: String): String {
        cache.rootFolderId?.let { return it }
        val query = "trashed=false and mimeType='application/vnd.google-apps.folder' and name = 'ttu-reader-data'"
        val existing = listFiles(accessToken, query).firstOrNull()
        if (existing != null) {
            cache.rootFolderId = existing.id
            return existing.id
        }
        val created = createFolder(accessToken, name = "ttu-reader-data", parentId = null)
        cache.rootFolderId = created.id
        return created.id
    }

    fun ensureBookFolder(accessToken: String, rootFolderId: String, bookTitle: String): String {
        val sanitizedTitle = sanitizeTtuFilename(bookTitle)
        cache.titleToFolderId[sanitizedTitle]?.let { return it }
        val query = "trashed=false and '$rootFolderId' in parents and mimeType='application/vnd.google-apps.folder' and name=\"$sanitizedTitle\""
        val existing = listFiles(accessToken, query).firstOrNull()
        val folderId = existing?.id ?: createFolder(accessToken, sanitizedTitle, rootFolderId).id
        cache.titleToFolderId = cache.titleToFolderId.apply {
            put(sanitizedTitle, folderId)
        }
        return folderId
    }

    fun listSyncFiles(accessToken: String, folderId: String): DriveSyncFiles {
        val query = "trashed=false and '$folderId' in parents and mimeType != 'application/vnd.google-apps.folder'"
        val files = listFiles(accessToken, query)
        return DriveSyncFiles(
            progress = files.firstOrNull { it.name.startsWith("progress_") },
            statistics = files.firstOrNull { it.name.startsWith("statistics_") },
            audioBook = files.firstOrNull { it.name.startsWith("audioBook_") },
        )
    }

    fun downloadProgress(accessToken: String, fileId: String): TtuProgress {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
        val response = executeWithRetry(authorizedRequest("GET", url, accessToken))
        return json.decodeFromString<TtuProgress>(response.body.decodeToString())
    }

    fun uploadProgress(accessToken: String, folderId: String, fileId: String?, progress: TtuProgress) {
        val fileName = "progress_1_6_${progress.lastBookmarkModified}_${progress.progress}.json"
        val metadata = if (fileId == null) {
            """{"name":"$fileName","parents":["$folderId"]}"""
        } else {
            """{"name":"$fileName"}"""
        }
        val content = json.encodeToString(progress)
        val boundary = UUID.randomUUID().toString()
        val url = if (fileId == null) {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=multipart"
        }
        val method = if (fileId == null) "POST" else "PATCH"
        val body = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata)
            append("\r\n--$boundary\r\n")
            append("Content-Type: application/json\r\n\r\n")
            append(content)
            append("\r\n--$boundary--\r\n")
        }.encodeToByteArray()

        executeWithRetry(
            authorizedRequest(
                method = method,
                url = url,
                accessToken = accessToken,
                headers = mapOf("Content-Type" to "multipart/related; boundary=$boundary"),
                body = body,
            ),
        )
    }

    private fun listFiles(accessToken: String, query: String): List<DriveFile> {
        val url = "https://www.googleapis.com/drive/v3/files?q=${query.encodeQuery()}&fields=files(id%2C%20name)"
        val response = executeWithRetry(authorizedRequest("GET", url, accessToken))
        return json.decodeFromString<DriveFileList>(response.body.decodeToString()).files
    }

    private fun createFolder(accessToken: String, name: String, parentId: String?): DriveFile {
        val parents = parentId?.let { ""","parents":["$it"]""" }.orEmpty()
        val body = """{"name":"$name","mimeType":"application/vnd.google-apps.folder"$parents}""".encodeToByteArray()
        val response = executeWithRetry(
            authorizedRequest(
                method = "POST",
                url = "https://www.googleapis.com/drive/v3/files",
                accessToken = accessToken,
                headers = mapOf("Content-Type" to "application/json"),
                body = body,
            ),
        )
        return json.decodeFromString<DriveFile>(response.body.decodeToString())
    }

    private fun executeWithRetry(request: DriveHttpRequest): DriveHttpResponse {
        val first = transport.execute(request)
        if (first.statusCode == 404) {
            cache.clear()
            val second = transport.execute(request)
            if (second.statusCode < 400) return second
            throw GoogleDriveException(second.errorMessage(), second.statusCode)
        }
        if (first.statusCode >= 400) throw GoogleDriveException(first.errorMessage(), first.statusCode)
        return first
    }

    private fun authorizedRequest(
        method: String,
        url: String,
        accessToken: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ): DriveHttpRequest =
        DriveHttpRequest(
            method = method,
            url = url,
            headers = headers + mapOf("Authorization" to "Bearer $accessToken"),
            body = body,
        )

    private fun DriveHttpResponse.errorMessage(): String {
        val text = body.decodeToString()
        val parsed = runCatching { json.decodeFromString<DriveErrorResponse>(text) }.getOrNull()
        return parsed?.error?.message ?: "Google Drive request failed with status $statusCode"
    }
}

class GoogleDriveException(message: String, val statusCode: Int) : Exception(message)

@Serializable
private data class DriveErrorResponse(val error: DriveErrorBody? = null)

@Serializable
private data class DriveErrorBody(val message: String? = null)

private fun String.encodeQuery(): String =
    URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
