package moe.antimony.hoshi.features.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
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
import dagger.hilt.android.qualifiers.ApplicationContext
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.di.CacheDir
import moe.antimony.hoshi.di.IoDispatcher

@Singleton
class GoogleDriveClient @Inject constructor(
    @ApplicationContext context: Context,
    private val tokenProvider: DriveAccessTokenProvider,
    @param:CacheDir private val cacheDir: File,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DriveSyncDataSource {
    private val cachePreferences = context.applicationContext.getSharedPreferences(CacheName, Context.MODE_PRIVATE)
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
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

    override suspend fun listBooks(rootFolderId: String): List<DriveFile> {
        val list = listFiles(
            query = "trashed=false and '${rootFolderId.driveQueryLiteral()}' in parents and mimeType='$FolderMimeType'",
            fields = "nextPageToken, files(id, name, thumbnailLink)",
        )
        return list.files.map { it.toDriveFile() }
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
            fields = "nextPageToken, files(id, name, parents, thumbnailLink)",
        )
        return list.files.map { it.toDriveFile() }.toDriveSyncFiles()
    }

    override suspend fun listSyncFiles(folderIds: List<String>): Map<String, DriveSyncFiles> {
        if (folderIds.isEmpty()) return emptyMap()
        val grouped = folderIds.associateWith { mutableListOf<DriveFile>() }.toMutableMap()
        folderIds.chunked(MaxParentsPerSyncFileQuery).forEach { chunk ->
            val parentQuery = chunk.joinToString(separator = " or ") { folderId ->
                "'${folderId.driveQueryLiteral()}' in parents"
            }
            val list = listFiles(
                query = "trashed=false and mimeType != '$FolderMimeType' and ($parentQuery)",
                fields = "nextPageToken, files(id, name, parents, thumbnailLink)",
            )
            list.files.map { it.toDriveFile() }.forEach { file ->
                file.parents.forEach { parent ->
                    grouped[parent]?.add(file)
                }
            }
        }
        return grouped.mapValues { (_, files) -> files.toDriveSyncFiles() }
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

    override suspend fun uploadBookData(folderId: String, file: File) {
        uploadMultipartFile(
            folderId = folderId,
            fileId = null,
            name = file.name,
            file = file,
            contentType = "application/zip",
        )
    }

    override suspend fun trashFile(fileId: String) {
        val url = driveUrl(endpoint = "files/${fileId.urlPathSegment()}", queryParameters = mapOf("fields" to "id, trashed"))
        val metadata = buildJsonObject { put("trashed", true) }.toString().toByteArray()
        performRequest(
            url = url,
            method = "PATCH",
            body = metadata,
            contentType = "application/json",
        )
    }

    override fun clearCache() {
        rootFolderId = null
        titleToFolderId.clear()
        clearGoogleDriveCoverCache(cacheDir)
        cachePreferences.edit()
            .remove(RootFolderIdKey)
            .remove(TitleFolderIdsKey)
            .apply()
    }

    private suspend fun listFiles(query: String, fields: String): DriveFileListResponse {
        val files = mutableListOf<DriveFileResponse>()
        var pageToken: String? = null
        do {
            val queryParameters = buildMap {
                put("q", query)
                put("fields", fields)
                pageToken?.let { put("pageToken", it) }
            }
            val url = driveUrl(endpoint = "files", queryParameters = queryParameters)
            val data = performRequest(url = url, method = "GET")
            val response = json.decodeFromString(DriveFileListResponse.serializer(), data.decodeToString())
            files += response.files
            pageToken = response.nextPageToken
        } while (!pageToken.isNullOrBlank())
        return DriveFileListResponse(files = files)
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

    override suspend fun downloadFile(
        fileId: String,
        progress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): ByteArray {
        val url = driveUrl(endpoint = "files/${fileId.urlPathSegment()}", queryParameters = mapOf("alt" to "media"))
        return performRequest(url = url, method = "GET").also { data ->
            progress(data.size.toLong(), data.size.toLong())
        }
    }

    override suspend fun downloadFileTo(
        fileId: String,
        destination: File,
        progress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        val url = driveUrl(endpoint = "files/${fileId.urlPathSegment()}", queryParameters = mapOf("alt" to "media"))
        performDownload(url = url, destination = destination, progress = progress)
    }

    override suspend fun downloadThumbnailTo(
        thumbnailLink: String,
        destination: File,
        progress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        performDownload(url = thumbnailLink, destination = destination, progress = progress)
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

    private suspend fun uploadMultipartFile(
        folderId: String,
        fileId: String?,
        name: String,
        file: File,
        contentType: String,
    ) {
        val metadata = buildJsonObject {
            put("name", name)
            if (fileId == null) {
                put("parents", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(folderId))))
            }
        }.toString().toByteArray()
        val boundary = UUID.randomUUID().toString()
        val prefix = ByteArrayOutputStream().apply {
            writeUtf8("--$boundary\r\n")
            writeUtf8("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            write(metadata)
            writeUtf8("\r\n--$boundary\r\n")
            writeUtf8("Content-Type: $contentType\r\n\r\n")
        }.toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
        val url = if (fileId == null) {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files/${fileId.urlPathSegment()}?uploadType=multipart"
        }
        performStreamingUpload(
            url = url,
            method = if (fileId == null) "POST" else "PATCH",
            contentType = "multipart/related; boundary=$boundary",
            contentLength = prefix.size.toLong() + file.length() + suffix.size.toLong(),
        ) { output ->
            output.write(prefix)
            file.inputStream().use { input -> input.copyTo(output) }
            output.write(suffix)
        }
    }

    private suspend fun performRequest(
        url: String,
        method: String,
        body: ByteArray? = null,
        contentType: String? = null,
        retry: Boolean = true,
    ): ByteArray = withContext(ioDispatcher) {
        checkValidatedInternet()
        val token = tokenProvider.accessToken()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = HttpConnectTimeoutMillis
            readTimeout = HttpReadTimeoutMillis
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

    private suspend fun performDownload(
        url: String,
        destination: File,
        progress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        retry: Boolean = true,
    ): Unit = withContext(ioDispatcher) {
        checkValidatedInternet()
        val token = tokenProvider.accessToken()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = HttpConnectTimeoutMillis
            readTimeout = HttpReadTimeoutMillis
            setRequestProperty("Authorization", "Bearer $token")
        }
        val statusCode = connection.responseCode
        if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED && retry) {
            connection.disconnect()
            tokenProvider.clearAccessToken(token)
            return@withContext performDownload(url, destination, progress, retry = false)
        }
        if (statusCode >= 400) {
            val responseBytes = connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            connection.disconnect()
            throw GoogleDriveApiException(
                message = responseBytes.driveErrorMessage() ?: "Request failed with status $statusCode",
                statusCode = statusCode,
            )
        }
        destination.parentFile?.mkdirs()
        val total = connection.contentLengthLong.takeIf { it >= 0 }
        var downloaded = 0L
        connection.inputStream.use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read.toLong()
                    progress(downloaded, total)
                }
            }
        }
        connection.disconnect()
        progress(downloaded, total)
    }

    private suspend fun performStreamingUpload(
        url: String,
        method: String,
        contentType: String,
        contentLength: Long,
        retry: Boolean = true,
        writer: (java.io.OutputStream) -> Unit,
    ): ByteArray = withContext(ioDispatcher) {
        checkValidatedInternet()
        val token = tokenProvider.accessToken()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = HttpConnectTimeoutMillis
            readTimeout = HttpReadTimeoutMillis
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", contentType)
            setFixedLengthStreamingMode(contentLength)
            doOutput = true
        }
        connection.outputStream.use(writer)
        val statusCode = connection.responseCode
        if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED && retry) {
            connection.disconnect()
            tokenProvider.clearAccessToken(token)
            return@withContext performStreamingUpload(url, method, contentType, contentLength, retry = false, writer)
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
        private const val MaxParentsPerSyncFileQuery = 50
        private const val HttpConnectTimeoutMillis = 15_000
        private const val HttpReadTimeoutMillis = 30_000
    }
}

@Serializable
private data class DriveFileListResponse(
    val files: List<DriveFileResponse> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
private data class DriveFileResponse(
    val id: String,
    val name: String,
    val parents: List<String> = emptyList(),
    val thumbnailLink: String? = null,
) {
    fun toDriveFile(): DriveFile = DriveFile(id = id, name = name, parents = parents, thumbnailLink = thumbnailLink)
}

@Serializable
private data class DriveIdResponse(
    val id: String,
)

internal fun List<DriveFile>.toDriveSyncFiles(): DriveSyncFiles =
    DriveSyncFiles(
        bookData = latestTtuFile("bookdata_", TtuSyncRules::parseBookDataTimestampMillis),
        cover = firstOrNull { it.name.startsWith("cover_") },
        progress = latestTtuFile("progress_", TtuSyncRules::parseProgressTimestampMillis),
        statistics = latestTtuFile("statistics_", TtuSyncRules::parseStatisticsTimestampMillis),
        audioBook = latestTtuFile("audioBook_", TtuSyncRules::parseAudioBookTimestampMillis),
    )

private fun List<DriveFile>.latestTtuFile(prefix: String, timestampMillis: (DriveFile) -> Long?): DriveFile? =
    filter { it.name.startsWith(prefix) }
        .maxByOrNull { timestampMillis(it) ?: Long.MIN_VALUE }

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
