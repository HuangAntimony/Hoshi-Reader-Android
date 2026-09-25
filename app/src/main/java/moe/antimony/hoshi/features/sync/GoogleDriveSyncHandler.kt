package moe.antimony.hoshi.features.sync

import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class GoogleDriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val version: String,
    val size: String? = null,
    val parents: List<String>? = null,
    val trashed: Boolean? = null,
    val createdTime: String,
) {
    val isFolder: Boolean get() = mimeType == "application/vnd.google-apps.folder"
    val isRecent: Boolean get() = runCatching { System.currentTimeMillis() - Instant.parse(createdTime).toEpochMilli() < 86_400_000 }.getOrDefault(false)
    val stateKey: String? get() = if (name.endsWith(".json") && !isFolder) name.dropLast(5).syncKey() else null
}

@Serializable
private data class GoogleDriveFileList(val files: List<GoogleDriveFile>, val nextPageToken: String? = null)

@Serializable
data class GoogleDriveChanges(
    val changes: List<Change>,
    val nextPageToken: String? = null,
    val newStartPageToken: String? = null,
) {
    @Serializable
    data class Change(val removed: Boolean, val file: GoogleDriveFile? = null)
}

data class GoogleDriveLayout(val root: String, val state: String, val books: String)

@Singleton
class GoogleDriveSyncHandler @Inject constructor(private val client: GoogleDriveClient) {
    private val fileFields = "id,name,mimeType,version,size,parents,trashed,createdTime"

    suspend fun startToken(): String = SyncFormat.json.parseToJsonElement(
        client.request("changes/startPageToken").decodeToString(),
    ).jsonObject.getValue("startPageToken").jsonPrimitive.content

    suspend fun changes(cursor: String): GoogleDriveChanges = SyncFormat.json.decodeFromString(
        client.request("changes", mapOf(
            "pageToken" to cursor,
            "pageSize" to "1000",
            "spaces" to "drive",
            "includeRemoved" to "true",
            "fields" to "nextPageToken,newStartPageToken,changes(removed,file($fileFields))",
        )).decodeToString(),
    )

    suspend fun layout(): GoogleDriveLayout {
        val root = folder("root", "Hoshi Reader", true)!!
        return GoogleDriveLayout(root, folder(root, "state", true)!!, folder(root, "books", true)!!)
    }

    suspend fun fileFolder(books: String, key: String, generation: Int, create: Boolean): String? {
        val book = folder(books, key, create) ?: return null
        return folder(book, generation.toString(), create)
    }

    suspend fun folder(parent: String, name: String, create: Boolean): String? {
        children(parent, name).firstOrNull { it.isFolder }?.let { return it.id }
        if (!create) return null
        val body = buildJsonObject {
            put("name", name)
            put("parents", JsonArray(listOf(JsonPrimitive(parent))))
            put("mimeType", "application/vnd.google-apps.folder")
        }
        return SyncFormat.json.decodeFromString<GoogleDriveFile>(client.request(
            "files", mapOf("fields" to fileFields), "POST", body.toString().toByteArray(),
        ).decodeToString()).id
    }

    suspend fun children(parent: String, name: String? = null): List<GoogleDriveFile> {
        var query = "'${escape(parent)}' in parents"
        if (name != null) query += " and name='${escape(name)}'"
        return list(query)
    }

    suspend fun list(query: String): List<GoogleDriveFile> {
        val result = mutableListOf<GoogleDriveFile>()
        var cursor: String? = null
        do {
            val parameters = mutableMapOf(
                "q" to "trashed=false and ($query)",
                "pageSize" to "1000",
                "spaces" to "drive",
                "fields" to "nextPageToken,files($fileFields)",
            )
            cursor?.let { parameters["pageToken"] = it }
            val page = SyncFormat.json.decodeFromString<GoogleDriveFileList>(client.request("files", parameters).decodeToString())
            result += page.files
            cursor = page.nextPageToken
        } while (cursor != null)
        return result.sortedBy { it.id }
    }

    suspend fun read(file: GoogleDriveFile): ByteArray = client.request("files/${file.id.urlPathSegment()}", mapOf("alt" to "media"))

    suspend fun upload(data: ByteArray, fileName: String, folder: String) {
        if (children(folder, fileName).isNotEmpty()) return
        client.write(data, fileName, folder)
    }

    suspend fun download(fileName: String, folder: String?, destination: File, onProgress: (Double) -> Unit) {
        val file = folder?.let { children(it, fileName).firstOrNull() }
            ?: throw GoogleDriveApiException("$fileName is missing from Google Drive.", 404)
        val size = file.size!!.toLong()
        client.performDownload(driveUrl("files/${file.id.urlPathSegment()}", mapOf("alt" to "media")), destination) { received, _ ->
            if (size > 0) onProgress(received.toDouble() / size)
        }
    }

    suspend fun trash(file: GoogleDriveFile) = client.trashFile(file.id)

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
}
