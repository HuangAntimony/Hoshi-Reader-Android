package moe.antimony.hoshi.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import moe.antimony.hoshi.di.FilesDir
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.features.reader.ReaderSettingsRepository
import moe.antimony.hoshi.features.sync.SyncBook
import moe.antimony.hoshi.features.sync.StatisticsSyncMode
import moe.antimony.hoshi.features.sync.Timestamped
import moe.antimony.hoshi.features.sync.TtuStatistics

internal const val STATISTICS_ARCHIVE_DIRECTORY = "statistics_archive"

internal data class StoredBookStatistics(
    val folder: String,
    val metadata: BookMetadata,
    val isArchived: Boolean,
    val coverPath: String?,
    val sessions: ReadingSessions,
    val days: List<StatisticsDay>,
)

internal data class StoredStatisticsSnapshot(
    val books: List<StoredBookStatistics>,
    val corruptBookIds: Set<String>,
)

@Singleton
class BookStatisticsStore private constructor(
    private val filesDir: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val resetMinutes: suspend () -> Int,
    internal val storageLock: BookStorageLock,
) {
    @Inject constructor(
        @FilesDir filesDir: File,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        settings: ReaderSettingsRepository,
        storageLock: BookStorageLock,
    ) : this(filesDir, ioDispatcher, { settings.settings.first().statisticsResetMinutes }, storageLock)

    constructor(filesDir: File, ioDispatcher: CoroutineDispatcher) : this(filesDir, ioDispatcher, { 0 }, BookStorageLock())

    private var resetTime = 0
    private val booksDirectory get() = filesDir.resolve("Books")
    private val archiveDirectory get() = booksDirectory.resolve(STATISTICS_ARCHIVE_DIRECTORY)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val serializer = MapSerializer(String.serializer(), Timestamped.serializer(ReadingSession.serializer().nullable))
    private val revision = MutableStateFlow(0)
    val changes = revision.asStateFlow()
    var onSave: (suspend (String, ReadingSessions) -> Unit)? = null

    suspend fun loadSessions(bookRoot: File): ReadingSessions = locked { readSessions(bookRoot) }

    suspend fun applySessions(bookRoot: File, sessions: ReadingSessions) = locked {
        if (readSessions(bookRoot) != sessions) writeSessions(bookRoot, sessions)
    }

    suspend fun saveSessions(bookRoot: File, sessions: ReadingSessions) {
        locked { writeSessions(bookRoot, sessions) }
        onSave?.invoke(bookRoot.name, sessions)
    }

    suspend fun saveTrackedSession(bookRoot: File, id: String, session: ReadingSession): ReadingSessions {
        var changed = false
        val sessions = locked {
            val root = activeRoot(bookRoot.name).takeIf { it.isDirectory } ?: archiveRoot(bookRoot.name)
            val stored = readSessions(root)
            if (stored[id]?.let { it.value == null } == true || !session.hasActivity || stored[id]?.value == session) {
                stored
            } else {
                (stored + (id to Timestamped<ReadingSession?>(System.currentTimeMillis(), session))).also { writeSessions(root, it); changed = true }
            }
        }
        if (changed) onSave?.invoke(bookRoot.name, sessions)
        return sessions
    }

    suspend fun load(bookRoot: File): List<ReadingStatistics> = locked {
        TtuStatistics.export(readSessions(bookRoot), readMetadata(bookRoot).displayTitle, resetTime)
    }

    suspend fun save(bookRoot: File, statistics: List<ReadingStatistics>) = importHistory(bookRoot, statistics, StatisticsSyncMode.Replace)

    suspend fun importHistory(bookRoot: File, statistics: List<ReadingStatistics>, mode: StatisticsSyncMode = StatisticsSyncMode.Merge) {
        var changed = false
        val sessions = locked {
            val stored = readSessions(bookRoot)
            TtuStatistics.importHistory(statistics, stored, bookRoot.name, resetTime, mode).also {
                if (it != stored) { writeSessions(bookRoot, it); changed = true }
            }
        }
        if (changed) onSave?.invoke(bookRoot.name, sessions)
    }

    suspend fun update(bookRoot: File, transform: (List<ReadingStatistics>) -> List<ReadingStatistics>) {
        val sessions = locked {
            val stored = readSessions(bookRoot)
            val daily = TtuStatistics.export(stored, readMetadata(bookRoot).displayTitle, resetTime)
            TtuStatistics.importHistory(transform(daily), stored, bookRoot.name, resetTime).also {
                if (it != stored) writeSessions(bookRoot, it)
            }
        }
        onSave?.invoke(bookRoot.name, sessions)
    }

    internal suspend fun archiveAndDelete(bookRoot: File, delete: suspend () -> Unit) = locked {
        archive(bookRoot)
        delete()
    }

    suspend fun archiveBook(bookRoot: File) = locked { archive(bookRoot) }

    private fun archive(bookRoot: File) {
        val metadata = readMetadata(bookRoot)
        val destination = archiveRoot(bookRoot.name)
        val merged = SyncBook.mergeRecords(readSessions(bookRoot), readSessions(destination))
        val cover = if (merged.values.any { it.value != null }) runCatching { writeArchivedCover(bookRoot, metadata, destination) }.getOrNull() else null
        val archivedMetadata = metadata.copy(
            title = metadata.displayTitle.ifBlank { bookRoot.name }, renamedTitle = null,
            folder = destination.name, cover = cover, epub = null, shelves = null,
            characterCount = maxOf(metadata.characterCount ?: 0, runCatching { json.decodeFromString(BookInfo.serializer(), bookRoot.resolve("bookinfo.json").readText()).characterCount }.getOrDefault(0)),
        )
        writeBookJson(destination.resolve("metadata.json"), json.encodeToString(BookMetadata.serializer(), archivedMetadata))
        writeSessions(destination, merged)
    }

    suspend fun restore(folder: String) = locked {
        val active = activeRoot(folder)
        val archived = archiveRoot(folder)
        if (!active.isDirectory || !archived.isDirectory) return@locked
        writeSessions(active, SyncBook.mergeRecords(readSessions(active), readSessions(archived)))
        removeArchive(archived)
    }

    internal suspend fun loadSnapshot(): StoredStatisticsSnapshot = locked {
        val corrupt = linkedSetOf<String>()
        val activeRoots = booksDirectory.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith('.') && it.name != STATISTICS_ARCHIVE_DIRECTORY }
            .associateBy { it.name.normalizedBookFolder() }
        val archiveRoots = archiveDirectory.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith('.') }.associateBy { it.name.normalizedBookFolder() }
        val books = (activeRoots.keys + archiveRoots.keys).sorted().mapNotNull { folder ->
            val root = activeRoots[folder] ?: archiveRoots.getValue(folder)
            var id = folder
            try {
                val metadata = readMetadata(root)
                id = metadata.id
                storedBook(folder, root, metadata, folder !in activeRoots)
            } catch (_: Exception) {
                corrupt += id
                null
            }
        }
        StoredStatisticsSnapshot(books, corrupt)
    }

    internal suspend fun loadBook(folder: String): StoredBookStatistics? = locked {
        val active = activeRoot(folder)
        val archived = archiveRoot(folder)
        val root = active.takeIf { it.isDirectory } ?: archived.takeIf { it.isDirectory } ?: return@locked null
        storedBook(folder, root, readMetadata(root), root == archived)
    }

    private fun storedBook(folder: String, root: File, metadata: BookMetadata, archived: Boolean): StoredBookStatistics {
        val sessions = readCoalesced(folder)
        return StoredBookStatistics(folder, metadata, archived, resolveCover(root, metadata.cover)?.absolutePath,
            sessions, StatisticsDay.grouped(sessions, resetTime).filter { it.total.charactersRead > 0 || it.total.readingTime > 0 })
    }

    suspend fun edit(id: String, folder: String, characters: Int?, readingTime: Double?) = mutate(folder) { sessions ->
        val stored = sessions[id]?.value ?: return@mutate sessions
        val changed = stored.copy(charactersRead = characters ?: stored.charactersRead, readingTime = readingTime ?: stored.readingTime)
        if (changed == stored) sessions else sessions + (id to Timestamped(System.currentTimeMillis(), changed))
    }

    suspend fun delete(ids: Collection<String>, folder: String) = mutate(folder) { sessions ->
        sessions.toMutableMap().apply {
            ids.filter { this[it]?.value != null }.forEach { this[it] = Timestamped(System.currentTimeMillis(), null) }
        }
    }

    suspend fun loadArchiveSummary(): Int = locked {
        archiveDirectory.listFiles().orEmpty().count { root ->
            root.isDirectory && !root.name.startsWith('.') && runCatching { readSessions(root).values.any { it.value != null } }.getOrDefault(false)
        }
    }

    suspend fun clearArchive() {
        val folders = locked { archiveDirectory.listFiles().orEmpty().filter { it.isDirectory }.map { it.name } }
        for (folder in folders) {
            val ids = locked { runCatching { readSessions(archiveRoot(folder)).keys }.getOrNull() } ?: continue
            delete(ids, folder)
        }
    }

    private suspend fun mutate(folder: String, transform: (ReadingSessions) -> ReadingSessions) {
        val records = locked {
            val active = activeRoot(folder)
            val root = active.takeIf { it.isDirectory } ?: archiveRoot(folder)
            val stored = readCoalesced(folder)
            transform(stored).also { records ->
                if (records != stored) writeSessions(root, records)
                if (root == active && archiveRoot(folder).exists()) removeArchive(archiveRoot(folder))
            }
        }
        onSave?.invoke(folder, records)
    }

    private fun readCoalesced(folder: String): ReadingSessions =
        SyncBook.mergeRecords(readSessions(activeRoot(folder)), readSessions(archiveRoot(folder)))

    private fun readSessions(root: File): ReadingSessions {
        val file = root.resolve("statistics.json")
        if (!file.exists()) return emptyMap()
        val element = json.parseToJsonElement(file.readText())
        if (element !is JsonArray) return json.decodeFromJsonElement(serializer, element)
        val daily = json.decodeFromJsonElement(ListSerializer(ReadingStatistics.serializer()), element)
        return TtuStatistics.legacySessions(daily, root.name, resetTime).also { writeSessions(root, it) }
    }

    private fun readMetadata(root: File): BookMetadata {
        val file = root.resolve("metadata.json")
        return if (file.exists()) json.decodeFromString(BookMetadata.serializer(), file.readText()) else
            BookMetadata(root.name, root.name, null, root.name, 0.0)
    }

    private fun writeSessions(root: File, sessions: ReadingSessions) {
        writeBookJson(root.resolve("statistics.json"), json.encodeToString(serializer, sessions))
        if (root.parentFile == archiveDirectory && sessions.values.all { it.value == null }) root.resolve("cover.jpg").delete()
        revision.value += 1
    }


    private fun activeRoot(folder: String): File = safeChild(booksDirectory, folder)
    private fun archiveRoot(folder: String): File = safeChild(archiveDirectory, folder)
    private fun safeChild(parent: File, folder: String): File {
        require(folder.isNotBlank() && folder != "." && folder != ".." && folder != STATISTICS_ARCHIVE_DIRECTORY && '/' !in folder && '\\' !in folder) { "Invalid statistics book folder." }
        val identity = folder.normalizedBookFolder()
        val root = parent.resolve(identity).takeIf { it.exists() }
            ?: parent.listFiles().orEmpty().firstOrNull { it.name.normalizedBookFolder() == identity }
            ?: parent.resolve(identity)
        return root.also { require(it.canonicalFile.parentFile == parent.canonicalFile) { "Unsafe statistics book folder." } }
    }

    private fun removeArchive(root: File) {
        check(root.deleteRecursively()) { "Unable to remove the statistics archive." }
    }

    private fun resolveCover(root: File, path: String?): File? {
        if (path.isNullOrBlank()) return null
        return listOf(root.resolve(path), filesDir.resolve(path)).map { it.canonicalFile }
            .firstOrNull { it.isFile && it.path.startsWith(root.canonicalPath + File.separator) }
    }

    private fun writeArchivedCover(root: File, metadata: BookMetadata, destination: File): String? {
        val source = resolveCover(root, metadata.cover) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (inSampleSize * 2) >= 240) inSampleSize *= 2
        }
        val original = BitmapFactory.decodeFile(source.path, options) ?: return null
        var scaled: Bitmap? = null
        try {
            val scale = minOf(1.0, 240.0 / maxOf(original.width, original.height))
            val thumbnail = Bitmap.createScaledBitmap(original, maxOf(1, (original.width * scale).toInt()), maxOf(1, (original.height * scale).toInt()), true)
            scaled = thumbnail
            check(destination.isDirectory || destination.mkdirs())
            val temporary = File.createTempFile(".cover-", ".jpg", destination)
            try {
                temporary.outputStream().use { check(thumbnail.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                Files.move(temporary.toPath(), destination.resolve("cover.jpg").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
            return "Books/$STATISTICS_ARCHIVE_DIRECTORY/${destination.name}/cover.jpg"
        } finally {
            if (scaled !== original) scaled?.recycle()
            original.recycle()
        }
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(ioDispatcher) {
        storageLock.withLock {
            migrateReservedStatisticsBook(booksDirectory)
            resetTime = resetMinutes()
            block()
        }
    }
}
