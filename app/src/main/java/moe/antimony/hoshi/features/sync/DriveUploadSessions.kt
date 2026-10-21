package moe.antimony.hoshi.features.sync

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import moe.antimony.hoshi.epub.writeBookJson

@Serializable
internal data class DriveUploadSession(
    val request: DriveUploadRequest,
    val path: String,
    val length: Long,
    val modified: Long,
    val url: String,
    val completed: GoogleDriveFile? = null,
) {
    fun matches(file: File) = path == file.absolutePath && length == file.length() && modified == file.lastModified()
}

/** Local-only capability URLs. Access is serialized and callers own the IO dispatcher. */
class DriveUploadSessions(private val directory: File) {
    private val mutex = Mutex()
    private val file get() = directory.resolve("sessions.json")

    private fun read(): List<DriveUploadSession> {
        if (!file.exists()) return emptyList()
        return try {
            SyncFormat.json.decodeFromString<List<DriveUploadSession>>(file.readText())
        } catch (_: SerializationException) {
            // Do not report parser input: it can contain a session capability URL.
            throw java.io.IOException("Invalid local Drive upload session data")
        }
    }

    private fun write(sessions: List<DriveUploadSession>) {
        directory.mkdirs()
        writeBookJson(file, SyncFormat.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(DriveUploadSession.serializer()), sessions))
    }

    internal suspend fun get(request: DriveUploadRequest, source: File): DriveUploadSession? = mutex.withLock {
        read().firstOrNull { it.request == request && it.matches(source) }
    }

    internal suspend fun put(session: DriveUploadSession) = mutex.withLock {
        val others = read().filterNot { it.request.book == session.request.book && it.request.type == session.request.type }
        write(others + session)
    }

    suspend fun remove(request: DriveUploadRequest) = mutex.withLock { write(read().filterNot { it.request == request }) }
    suspend fun discardObsolete(keep: (DriveUploadRequest) -> Boolean) = mutex.withLock {
        val sessions = read()
        val remaining = sessions.filter { keep(it.request) && it.matches(File(it.path)) }
        if (sessions != remaining) write(remaining)
    }
    suspend fun reset() = mutex.withLock { write(emptyList()) }
}
