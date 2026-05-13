package moe.antimony.hoshi.features.sync

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import moe.antimony.hoshi.epub.ReadingStatistics

class GoogleDriveClient(
    context: Context,
    private val tokenProvider: DriveAccessTokenProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DriveSyncDataSource {
    private val cachePreferences = context.applicationContext.getSharedPreferences(CacheName, Context.MODE_PRIVATE)
    private var rootFolderId: String? = cachePreferences.getString(RootFolderIdKey, null)
    private var titleToFolderId: MutableMap<String, String> = cachePreferences
        .getStringSet(TitleFolderIdsKey, emptySet())
        ?.mapNotNull { encoded ->
            val separator = encoded.indexOf('=')
            if (separator <= 0) {
                null
            } else {
                encoded.substring(0, separator).urlQueryDecoded() to
                    encoded.substring(separator + 1).urlQueryDecoded()
            }
        }
        ?.toMap()
        ?.toMutableMap()
        ?: mutableMapOf()

    override suspend fun findRootFolder(): String {
        rootFolderId?.let { return it }
        val list = listFiles(
            query = "trashed=false and 'root' in parents and mimeType='$FolderMimeType' and name = '$RootFolderName'",
            fields = "files(id, name)",
        )
        val folderId = list.files.firstOrNull()?.id ?: createFolder(RootFolderName, parentId = "root")
        rootFolderId = folderId
        cachePreferences.edit().putString(RootFolderIdKey, folderId).apply()
        return folderId
    }

    override suspend fun ensureBookFolder(
        bookTitle: String,
        rootFolderId: String,
        coverImageDataProvider: (suspend () -> ByteArray?)?,
    ): String {
        val sanitizedTitle = TtuSyncRules.sanitizeTtuFilename(bookTitle)
        titleToFolderId[sanitizedTitle]?.let { return it }
        val list = listFiles(
            query = "trashed=false and '${rootFolderId.driveQueryLiteral()}' in parents and " +
                "mimeType='$FolderMimeType' and name='${sanitizedTitle.driveQueryLiteral()}'",
            fields = "files(id, name)",
        )
        val folderId = list.files.firstOrNull()?.id ?: createFolder(sanitizedTitle, parentId = rootFolderId).also {
            val coverData = coverImageDataProvider?.invoke()
            if (coverData != null) {
                runCatching { uploadCoverImage(folderId = it, coverData = coverData) }
            }
        }
        cacheBookFolder(sanitizedTitle, folderId)
        return folderId
    }

    override suspend fun listSyncFiles(folderId: String): DriveSyncFiles {
        val list = listFiles(
            query = "trashed=false and '${folderId.driveQueryLiteral()}' in parents and mimeType != '$FolderMimeType'",
            fields = "files(id, name)",
        )
        return DriveSyncFiles(
            progress = list.files.firstOrNull { it.name.startsWith("progress_") }?.toDriveFile(),
            statistics = list.files.firstOrNull { it.name.startsWith("statistics_") }?.toDriveFile(),
            audioBook = list.files.firstOrNull { it.name.startsWith("audioBook_") }?.toDriveFile(),
        )
    }

    override suspend fun getProgressFile(fileId: String): TtuProgress =
        json.decodeFromString(TtuProgress.serializer(), downloadFile(fileId).decodeToString())

    override suspend fun getStatsFile(fileId: String): List<ReadingStatistics> =
        json.decodeFromString(ListSerializer(ReadingStatistics.serializer()), downloadFile(fileId).decodeToString())

    override suspend fun getAudioBookFile(fileId: String): TtuAudioBook =
        json.decodeFromString(TtuAudioBook.serializer(), downloadFile(fileId).decodeToString())

    override suspend fun updateProgressFile(folderId: String, fileId: String?, progress: TtuProgress) {
        uploadJsonFile(
            folderId = folderId,
            fileId = fileId,
            name = TtuSyncRules.progressFileName(progress),
            content = json.encodeToString(progress).toByteArray(),
        )
    }

    override suspend fun updateStatsFile(folderId: String, fileId: String?, stats: List<ReadingStatistics>) {
        uploadJsonFile(
            folderId = folderId,
            fileId = fileId,
            name = TtuSyncRules.statisticsFileName(stats),
            content = json.encodeToString(ListSerializer(ReadingStatistics.serializer()), stats).toByteArray(),
        )
    }

    override suspend fun updateAudioBookFile(folderId: String, fileId: String?, audioBook: TtuAudioBook) {
        uploadJsonFile(
            folderId = folderId,
            fileId = fileId,
            name = TtuSyncRules.audioBookFileName(audioBook),
            content = json.encodeToString(audioBook).toByteArray(),
        )
    }

    override fun clearCache() {
        rootFolderId = null
        titleToFolderId.clear()
        cachePreferences.edit()
            .remove(RootFolderIdKey)
            .remove(TitleFolderIdsKey)
            .apply()
    }

    private suspend fun listFiles(query: String, fields: String): DriveFileListResponse {
        val url = driveUrl(
            endpoint = "files",
            queryParameters = mapOf(
                "q" to query,
                "fields" to fields,
            ),
        )
        val data = performRequest(url = url, method = "GET")
        return json.decodeFromString(DriveFileListResponse.serializer(), data.decodeToString())
    }

    private suspend fun createFolder(name: String, parentId: String): String {
        val url = driveUrl(endpoint = "files", queryParameters = mapOf("fields" to "id"))
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", FolderMimeType)
            put("parents", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(parentId))))
        }
        val data = performRequest(
            url = url,
            method = "POST",
            body = metadata.toString().toByteArray(),
            contentType = "application/json",
        )
        return json.decodeFromString(DriveIdResponse.serializer(), data.decodeToString()).id
    }

    private suspend fun downloadFile(fileId: String): ByteArray {
        val url = driveUrl(endpoint = "files/${fileId.urlPathSegment()}", queryParameters = mapOf("alt" to "media"))
        return performRequest(url = url, method = "GET")
    }

    private suspend fun uploadJsonFile(folderId: String, fileId: String?, name: String, content: ByteArray) {
        uploadMultipartFile(
            folderId = folderId,
            fileId = fileId,
            name = name,
            content = content,
            contentType = "application/json",
        )
    }

    private suspend fun uploadCoverImage(folderId: String, coverData: ByteArray) {
        val metadata = TtuSyncRules.coverMetadata(coverData)
        uploadMultipartFile(
            folderId = folderId,
            fileId = null,
            name = "cover_1_6.${metadata.extension}",
            content = coverData,
            contentType = metadata.mimeType,
        )
    }

    private suspend fun uploadMultipartFile(
        folderId: String,
        fileId: String?,
        name: String,
        content: ByteArray,
        contentType: String,
    ) {
        val metadata = buildJsonObject {
            put("name", name)
            if (fileId == null) {
                put("parents", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(folderId))))
            }
        }.toString().toByteArray()
        val boundary = UUID.randomUUID().toString()
        val url = if (fileId == null) {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files/${fileId.urlPathSegment()}?uploadType=multipart"
        }
        val body = ByteArrayOutputStream().apply {
            writeUtf8("--$boundary\r\n")
            writeUtf8("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            write(metadata)
            writeUtf8("\r\n--$boundary\r\n")
            writeUtf8("Content-Type: $contentType\r\n\r\n")
            write(content)
            writeUtf8("\r\n--$boundary--\r\n")
        }.toByteArray()
        performRequest(
            url = url,
            method = if (fileId == null) "POST" else "PATCH",
            body = body,
            contentType = "multipart/related; boundary=$boundary",
        )
    }

    private suspend fun performRequest(
        url: String,
        method: String,
        body: ByteArray? = null,
        contentType: String? = null,
        retry: Boolean = true,
    ): ByteArray = withContext(ioDispatcher) {
        val token = tokenProvider.accessToken()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $token")
            contentType?.let { setRequestProperty("Content-Type", it) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body) }
            }
        }
        val statusCode = connection.responseCode
        if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED && retry) {
            connection.disconnect()
            tokenProvider.clearAccessToken(token)
            return@withContext performRequest(url, method, body, contentType, retry = false)
        }
        val responseBytes = if (statusCode >= 400) {
            connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
        } else {
            connection.inputStream.use { it.readBytes() }
        }
        connection.disconnect()
        if (statusCode >= 400) {
            throw GoogleDriveApiException(
                message = responseBytes.driveErrorMessage() ?: "Request failed with status $statusCode",
                statusCode = statusCode,
            )
        }
        responseBytes
    }

    private fun cacheBookFolder(sanitizedTitle: String, folderId: String) {
        titleToFolderId[sanitizedTitle] = folderId
        cachePreferences.edit()
            .putStringSet(
                TitleFolderIdsKey,
                titleToFolderId.mapTo(mutableSetOf()) {
                    "${it.key.urlQueryComponent()}=${it.value.urlQueryComponent()}"
                },
            )
            .apply()
    }

    private fun ByteArray.driveErrorMessage(): String? =
        runCatching {
            val errorObject = json.parseToJsonElement(decodeToString()).jsonObject["error"]?.jsonObject
            errorObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull()

    companion object {
        private const val CacheName = "google-drive-sync-cache"
        private const val RootFolderIdKey = "rootFolderId"
        private const val TitleFolderIdsKey = "titleFolderIds"
        private const val FolderMimeType = "application/vnd.google-apps.folder"
        private const val RootFolderName = "ttu-reader-data"
    }
}

@Serializable
private data class DriveFileListResponse(
    val files: List<DriveFileResponse> = emptyList(),
)

@Serializable
private data class DriveFileResponse(
    val id: String,
    val name: String,
) {
    fun toDriveFile(): DriveFile = DriveFile(id = id, name = name)
}

@Serializable
private data class DriveIdResponse(
    val id: String,
)

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private fun driveUrl(endpoint: String, queryParameters: Map<String, String>): String {
    val query = queryParameters.entries.joinToString("&") { (name, value) ->
        "${name.urlQueryComponent()}=${value.urlQueryComponent()}"
    }
    return "https://www.googleapis.com/drive/v3/$endpoint?$query"
}

private fun String.urlQueryComponent(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name())

private fun String.urlQueryDecoded(): String =
    URLDecoder.decode(this, StandardCharsets.UTF_8.name())

private fun String.urlPathSegment(): String =
    split("/").joinToString("/") { it.urlQueryComponent() }

private fun String.driveQueryLiteral(): String =
    replace("'", "\\'")

private fun ByteArrayOutputStream.writeUtf8(text: String) {
    write(text.toByteArray(StandardCharsets.UTF_8))
}
