package moe.antimony.hoshi.features.sync

import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    val md5Checksum: String? = null,
    val size: String? = null,
    val parents: List<String>? = null,
    val trashed: Boolean? = null,
    val createdTime: String,
) {
    val isFolder: Boolean get() = mimeType == "application/vnd.google-apps.folder"
    val isRecent: Boolean get() = runCatching { System.currentTimeMillis() - Instant.parse(createdTime).toEpochMilli() < 86_400_000 }.getOrDefault(false)
    val isNewerFormat: Boolean get() = name.startsWith(".format-") && (name.drop(8).toIntOrNull() ?: 0) > 1
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

data class GoogleDriveLayout(val root: String, val state: String, val books: String, val created: Boolean)

@Singleton
class GoogleDriveSyncHandler @Inject constructor(private val client: GoogleDriveClient) {
    private val fileFields = "id,name,mimeType,md5Checksum,size,parents,trashed,createdTime"

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
        val existing = folder("root", "Hoshi Reader", false)
        val root = existing ?: createFolder("root", "Hoshi Reader")
        return GoogleDriveLayout(root, folder(root, "state", true)!!, folder(root, "books", true)!!, existing == null)
    }

    suspend fun renameRoot() {
        for (folder in children("root", "Hoshi Reader")) if (folder.isFolder) client.renameFile(folder.id, "Hoshi Reader (replaced)")
    }

    suspend fun removeReplaced() {
        for (folder in children("root", "Hoshi Reader (replaced)")) deleteTree(folder.id)
    }

    private suspend fun deleteTree(id: String) {
        try {
            return client.deleteFile(id)
        } catch (_: GoogleDriveApiException) {
        }
        val files = children(id)
        coroutineScope {
            val limit = Semaphore(8)
            for (file in files) launch { limit.withPermit { deleteTree(file.id) } }
        }
        client.deleteFile(id)
    }

    suspend fun folder(parent: String, name: String, create: Boolean): String? {
        children(parent, name).firstOrNull { it.isFolder }?.let { return it.id }
        if (!create) return null
        return createFolder(parent, name)
    }

    suspend fun createFolder(parent: String, name: String): String {
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

    suspend fun upload(file: File, fileName: String, folder: String) {
        if (children(folder, fileName).isNotEmpty()) return
        client.write(file, fileName, folder)
    }

    suspend fun download(file: GoogleDriveFile, destination: File, onProgress: (Double) -> Unit) {
        val size = file.size!!.toLong()
        client.performDownload(driveUrl("files/${file.id.urlPathSegment()}", mapOf("alt" to "media")), destination) { received, _ ->
            if (size > 0) onProgress(received.toDouble() / size)
        }
    }

    suspend fun trash(file: GoogleDriveFile) = client.trashFile(file.id)

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
}
