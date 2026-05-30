package moe.antimony.hoshi.features.audio

import android.content.ContentResolver
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.validateImportFile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class LocalAudioImportProgress(
    val copiedBytes: Long,
    val totalBytes: Long?,
)

class LocalAudioRepository(private val filesDir: File) {
    private val privateDbFile: File
        get() = File(filesDir, AudioSettings.LocalAudioPath)
    private val sourceConfigFile: File
        get() = File(filesDir, AudioSettings.LocalAudioSourceConfigPath)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val sourceConfigCache: LocalAudioSourceConfigCache =
        synchronized(SourceConfigCaches) {
            SourceConfigCaches.getOrPut(sourceConfigFile.absolutePath) {
                LocalAudioSourceConfigCache {
                    loadSourceConfig(reset = false)
                }
            }
        }

    val dbFile: File
        get() = privateDbFile

    fun deleteDatabase() {
        privateDbFile.delete()
        sourceConfigFile.delete()
        sourceConfigCache.clear()
    }

    fun databaseSizeBytes(): Long? =
        dbFile.takeIf { it.isFile }?.length()

    fun canOpenDatabase(): Boolean =
        withReadOnlyDatabase { db ->
            db.rawQuery("SELECT name FROM sqlite_master LIMIT 1", null).use { cursor ->
                cursor.moveToFirst()
                true
            }
        } == true

    fun importDatabase(contentResolver: ContentResolver, uri: Uri, onProgress: (LocalAudioImportProgress) -> Unit = {}): Long {
        contentResolver.validateImportFile(uri, ImportFileType.LocalAudioDatabase)
        val expectedSize = contentResolver.sizeBytes(uri)
        return contentResolver.openInputStream(uri)?.use { input ->
            replacePrivateDatabase(input, expectedSize, onProgress).also {
                ensureSourceConfig(reset = true)
            }
        } ?: error("Unable to open audio database.")
    }

    fun ensureSourceConfig(reset: Boolean = false): LocalAudioSourceConfig =
        if (reset) {
            sourceConfigCache.clear()
            loadSourceConfig(reset = true).also(sourceConfigCache::replace)
        } else {
            sourceConfigCache.get()
        }

    private fun loadSourceConfig(reset: Boolean): LocalAudioSourceConfig {
        if (!reset) {
            readSourceConfig()
                ?.takeIf { it.version == LocalAudioSourceConfig.CurrentVersion && it.sourceOrder.isNotEmpty() }
                ?.let { return it }
        }
        val availableSources = audioSourcesFromDatabase().toSet()
        if (availableSources.isEmpty()) {
            sourceConfigFile.delete()
            return LocalAudioSourceConfig()
        }
        val current = if (reset) null else readSourceConfig()
        val repaired = if (current?.version == LocalAudioSourceConfig.CurrentVersion) {
            current.repair(availableSources)
        } else {
            LocalAudioSourceConfig.defaultFor(availableSources)
        }
        if (current != repaired) {
            writeSourceConfig(repaired)
        }
        return repaired
    }

    fun updateSourceOrder(sourceOrder: List<String>): LocalAudioSourceConfig {
        val availableSources = ensureSourceConfig().sourceOrder.toSet()
        if (availableSources.isEmpty()) {
            sourceConfigFile.delete()
            sourceConfigCache.clear()
            return LocalAudioSourceConfig()
        }
        val next = LocalAudioSourceConfig(sourceOrder = sourceOrder).repair(availableSources)
        writeSourceConfig(next)
        sourceConfigCache.replace(next)
        return next
    }

    fun findAudio(term: String, reading: String): LocalAudioEntry? {
        val normalizedReading = LocalAudioResolver.katakanaToHiragana(reading)
        val sourceOrder = ensureSourceConfig().sourceOrder
        val rows = withReadOnlyDatabase { db ->
            val args: Array<String>
            val selection: String
            if (normalizedReading.isBlank()) {
                selection = "expression = ?"
                args = arrayOf(term)
            } else {
                selection = "(expression = ? OR reading = ?)"
                args = arrayOf(term, normalizedReading)
            }
            val rows = mutableListOf<LocalAudioEntry>()
            db.query(
                "entries",
                arrayOf("source", "expression", "reading", "file"),
                selection,
                args,
                null,
                null,
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += LocalAudioEntry(
                        source = cursor.getString(0),
                        expression = cursor.getString(1),
                        reading = cursor.getString(2),
                        file = cursor.getString(3),
                    )
                }
            }
            rows
        } ?: return null
        return LocalAudioResolver.resolve(term, normalizedReading, rows, sourceOrder)
    }

    fun audioSourcesFromDatabase(): List<String> {
        val sources = withReadOnlyDatabase { db ->
            val rows = mutableListOf<String>()
            db.query(
                true,
                "entries",
                arrayOf("source"),
                "lower(file) LIKE ? OR lower(file) LIKE ? OR lower(file) LIKE ?",
                arrayOf("%.mp3", "%.opus", "%.ogg"),
                null,
                null,
                null,
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += cursor.getString(0)
                }
            }
            rows
        }.orEmpty()
        return LocalAudioSourceOrder.defaultOrder(sources)
    }

    fun loadAudio(file: LocalAudioFile): ByteArray? {
        return withReadOnlyDatabase { db ->
            db.query(
                "android",
                arrayOf("data"),
                "source = ? AND file = ?",
                arrayOf(file.source, file.file),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getBlob(0)
            }
        }
    }

    private inline fun <T> withReadOnlyDatabase(block: (SQLiteDatabase) -> T): T? {
        return runCatching {
            if (!dbFile.isFile) return null
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ).use(block)
        }.onFailure { error ->
            Log.w("HoshiLocalAudio", "Unable to open local audio database.", error)
        }.getOrNull()
    }

    private fun readSourceConfig(): LocalAudioSourceConfig? =
        runCatching {
            sourceConfigFile
                .takeIf { it.isFile }
                ?.readText()
                ?.let { json.decodeFromString<LocalAudioSourceConfig>(it) }
        }.onFailure { error ->
            Log.w("HoshiLocalAudio", "Unable to read local audio source config.", error)
        }.getOrNull()

    private fun writeSourceConfig(config: LocalAudioSourceConfig) {
        sourceConfigFile.parentFile?.mkdirs()
        sourceConfigFile.writeText(json.encodeToString(config))
    }

    internal fun replacePrivateDatabase(
        input: InputStream,
        expectedSizeBytes: Long?,
        onProgress: (LocalAudioImportProgress) -> Unit = {},
    ): Long {
        privateDbFile.parentFile?.mkdirs()
        val tempFile = File(privateDbFile.parentFile, "${privateDbFile.name}.tmp")
        tempFile.delete()
        var copied = 0L
        val buffer = ByteArray(DatabaseCopyBufferSizeBytes)
        try {
            input.use { source ->
                FileOutputStream(tempFile).use { output ->
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(LocalAudioImportProgress(copiedBytes = copied, totalBytes = expectedSizeBytes))
                    }
                    output.fd.sync()
                }
            }
            if (expectedSizeBytes != null && copied != expectedSizeBytes) {
                error("Incomplete audio database copy: copied $copied of $expectedSizeBytes bytes.")
            }
            moveReplacing(tempFile, privateDbFile)
            return copied
        } catch (error: Throwable) {
            tempFile.delete()
            throw error
        }
    }

    private fun moveReplacing(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching { error ->
            if (error !is AtomicMoveNotSupportedException) throw error
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
    }

    private fun ContentResolver.sizeBytes(uri: Uri): Long? {
        openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0 }?.let { return it }
        }
        return query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column < 0 || cursor.isNull(column)) null else cursor.getLong(column).takeIf { it >= 0 }
        }
    }

    companion object {
        private const val DatabaseCopyBufferSizeBytes = 1024 * 1024
        private val SourceConfigCaches = mutableMapOf<String, LocalAudioSourceConfigCache>()

        fun fromContext(context: Context): LocalAudioRepository =
            LocalAudioRepository(context.filesDir)
    }
}
