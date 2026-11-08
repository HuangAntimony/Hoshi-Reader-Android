package moe.antimony.hoshi.features.audio

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.validateImportFile

internal interface LocalAudioDatabaseSession : Closeable {
    val sizeBytes: Long
    val fingerprint: String
    fun queryEntries(term: String, reading: String): List<LocalAudioEntry>
    fun querySources(): List<String>
    fun loadAudio(source: String, file: String): ByteArray?
}

/** The only Android/document boundary; the repository also runs it on its IO dispatcher. */
internal interface LocalAudioAccess {
    fun validateDocument(uri: String): String
    fun documentSize(uri: String): Long?
    fun openStream(uri: String): InputStream
    fun takeReadPermission(uri: String): Boolean
    fun releaseReadPermission(uri: String)
    fun openDocument(uri: String): LocalAudioDatabaseSession
    fun openPrivate(file: File): LocalAudioDatabaseSession
}

internal class AndroidLocalAudioAccess(private val resolver: ContentResolver? = null) : LocalAudioAccess {
    private fun resolver() = checkNotNull(resolver) { "Document access requires a ContentResolver." }

    override fun validateDocument(uri: String): String =
        resolver().validateImportFile(Uri.parse(uri), ImportFileType.LocalAudioDatabase)

    override fun documentSize(uri: String): Long? =
        resolver().query(Uri.parse(uri), arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0).takeIf { it >= 0 }
        }

    override fun openStream(uri: String): InputStream =
        resolver().openInputStream(Uri.parse(uri)) ?: throw IOException("Unable to open audio database document.")

    override fun takeReadPermission(uri: String): Boolean {
        val source = Uri.parse(uri)
        if (resolver().persistedUriPermissions.any { it.uri == source && it.isReadPermission }) return false
        resolver().takePersistableUriPermission(source, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return true
    }

    override fun releaseReadPermission(uri: String) {
        resolver().releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun openDocument(uri: String): LocalAudioDatabaseSession =
        resolver().openAssetFileDescriptor(Uri.parse(uri), "r")?.use { descriptor ->
            open(descriptor.parcelFileDescriptor, descriptor.startOffset, descriptor.declaredLength)
        } ?: throw IOException("Unable to open audio database document.")

    override fun openPrivate(file: File): LocalAudioDatabaseSession =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { open(it, 0, -1) }

    private fun open(descriptor: ParcelFileDescriptor, offset: Long, length: Long): LocalAudioDatabaseSession {
        val database = LocalAudioNativeDatabase.open(descriptor.fd, offset, length)
        try {
            // A quick identity check across process restarts, not a substitute for a static-file contract.
            val header = ByteArray(100)
            var read = 0
            while (read < header.size) {
                val count = Os.pread(descriptor.fileDescriptor, header, read, header.size - read, offset + read)
                if (count <= 0) throw IOException("Incomplete SQLite header.")
                read += count
            }
            val modified = Os.fstat(descriptor.fileDescriptor).st_mtime
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(header)
                .joinToString("") { "%02x".format(it) } + ":${database.sizeBytes}:$modified"
            return object : LocalAudioDatabaseSession {
                override val sizeBytes = database.sizeBytes
                override val fingerprint = fingerprint
                override fun queryEntries(term: String, reading: String) = database.queryEntries(term, reading)
                override fun querySources() = database.querySources()
                override fun loadAudio(source: String, file: String) = database.loadAudio(source, file)
                override fun close() = database.close()
            }
        } catch (error: Throwable) {
            database.close()
            throw error
        }
    }
}
