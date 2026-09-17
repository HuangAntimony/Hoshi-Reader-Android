package moe.antimony.hoshi.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.di.FilesDir
import moe.antimony.hoshi.di.IoDispatcher

internal const val STATISTICS_ARCHIVE_DIRECTORY = "statistics_archive"

internal data class StoredBookStatistics(
    val folder: String,
    val metadata: BookMetadata,
    val isArchived: Boolean,
    val coverPath: String?,
    val statistics: List<ReadingStatistics>,
)

internal data class StoredStatisticsSnapshot(
    val books: List<StoredBookStatistics>,
    val corruptBookIds: Set<String>,
)

/** One serialization boundary for reader sidecars, editor mutations and deletion archives. */
@Singleton
class BookStatisticsStore @Inject constructor(
    @param:FilesDir private val filesDir: File,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    // Only protects queued in-process Reader writes; no persisted or sync tombstones.
    private val locallyDeletedDays = mutableMapOf<Pair<String, String>, Long>()
    private val locallyClearedBooks = mutableMapOf<String, Long>()
    private val booksDirectory get() = filesDir.resolve("Books")
    private val archiveDirectory get() = booksDirectory.resolve(STATISTICS_ARCHIVE_DIRECTORY)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; prettyPrint = true }
    private val serializer = ListSerializer(ReadingStatistics.serializer())

    suspend fun load(bookRoot: File): List<ReadingStatistics>? = locked { readStatistics(bookRoot) }

    suspend fun save(bookRoot: File, statistics: List<ReadingStatistics>) = locked {
        // Never silently replace corrupt history with a reader's empty fallback.
        readStatistics(bookRoot)
        writeStatistics(bookRoot, statistics)
    }

    suspend fun update(bookRoot: File, transform: (List<ReadingStatistics>) -> List<ReadingStatistics>) = locked {
        check(bookRoot.isDirectory) { "The book no longer exists." }
        writeStatistics(bookRoot, transform(readStatistics(bookRoot).orEmpty()))
    }

    suspend fun saveTrackedDays(bookRoot: File, changedDays: List<ReadingStatistics>) = locked {
        if (!bookRoot.isDirectory) return@locked
        val folder = bookRoot.name.normalizedBookFolder()
        val accepted = changedDays.filter {
            it.lastStatisticModified > maxOf(
                locallyDeletedDays[folder to it.dateKey] ?: Long.MIN_VALUE,
                locallyClearedBooks[folder] ?: Long.MIN_VALUE,
            )
        }
        writeStatistics(bookRoot, (readStatistics(bookRoot).orEmpty() + accepted).deduplicateReadingStatistics())
    }

    internal suspend fun archiveAndDelete(bookRoot: File, delete: suspend () -> Unit) = locked {
        require(bookRoot.canonicalFile.parentFile == booksDirectory.canonicalFile && bookRoot.name != STATISTICS_ARCHIVE_DIRECTORY) {
            "Unsafe book directory."
        }
        archive(bookRoot)
        delete()
    }

    private fun archive(bookRoot: File) {
        val statistics = readStatistics(bookRoot).orEmpty().filter { it.hasActivity }
        if (statistics.isEmpty()) return
        val metadata = readMetadata(bookRoot)
        val destination = archiveRoot(bookRoot.name)
        val merged = (readStatistics(destination).orEmpty() + statistics).deduplicateReadingStatistics().filter { it.hasActivity }
        val cover = runCatching { writeArchivedCover(bookRoot, metadata, destination) }.getOrNull()
        val archivedMetadata = metadata.copy(
            title = metadata.displayTitle.ifBlank { bookRoot.name },
            renamedTitle = null,
            folder = destination.name,
            cover = cover,
            epub = null,
        )
        atomicWrite(destination.resolve("metadata.json"), json.encodeToString(BookMetadata.serializer(), archivedMetadata))
        writeStatistics(destination, merged)
    }

    /** Called only after an import has finished writing its external sidecars. */
    suspend fun restore(folder: String) = locked {
        val active = activeRoot(folder)
        val archived = archiveRoot(folder)
        if (!active.isDirectory || !archived.isDirectory) return@locked
        val merged = (readStatistics(active).orEmpty() + readStatistics(archived).orEmpty()).deduplicateReadingStatistics()
        writeStatistics(active, merged)
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
                val days = readCoalesced(folder).filter { it.hasActivity }
                StoredBookStatistics(folder, metadata, folder !in activeRoots, resolveCover(root, metadata.cover)?.absolutePath, days)
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
        val metadata = readMetadata(root)
        StoredBookStatistics(folder, metadata, root == archived, resolveCover(root, metadata.cover)?.absolutePath, readCoalesced(folder).filter { it.hasActivity })
    }

    suspend fun updateDay(folder: String, dateKey: String, characters: Int, totalMinutes: Int) = mutate(folder) { records ->
        check(records.any { it.dateKey == dateKey }) { "The reading day no longer exists." }
        records.map { if (it.dateKey == dateKey) it.updated(characters, totalMinutes.coerceAtLeast(0).toDouble() * 60.0) else it }
    }

    suspend fun deleteDay(folder: String, dateKey: String) = mutate(folder) { records -> records.filterNot { it.dateKey == dateKey } }

    suspend fun deleteAll(folder: String) = mutate(folder, clearAll = true) { emptyList() }

    suspend fun loadArchiveSummary(): Int = locked {
        archiveDirectory.listFiles().orEmpty().count { root ->
            root.isDirectory && !root.name.startsWith('.') && runCatching {
                readMetadata(root)
                readStatistics(root).orEmpty().any { it.hasActivity }
            }.getOrDefault(false)
        }
    }

    suspend fun clearArchive() = locked {
        if (archiveDirectory.exists()) check(archiveDirectory.deleteRecursively()) { "Unable to clear the statistics archive." }
    }

    private suspend fun mutate(folder: String, clearAll: Boolean = false, transform: (List<ReadingStatistics>) -> List<ReadingStatistics>) = locked {
        val active = activeRoot(folder)
        val archived = archiveRoot(folder)
        val root = active.takeIf { it.isDirectory } ?: archived.takeIf { it.isDirectory }
            ?: error("The statistics book no longer exists.")
        val original = readCoalesced(folder)
        val records = transform(original).deduplicateReadingStatistics().filter { it.hasActivity }
        if (root == archived && records.isEmpty()) {
            removeArchive(archived)
        } else {
            writeStatistics(root, records)
            // A previous interrupted restore must not resurrect an edited or deleted day.
            if (root == active && archived.exists()) removeArchive(archived)
        }
        val modified = System.currentTimeMillis()
        val remainingDates = records.mapTo(hashSetOf()) { it.dateKey }
        original.filterNot { it.dateKey in remainingDates }.forEach {
            locallyDeletedDays[folder.normalizedBookFolder() to it.dateKey] = modified
        }
        if (clearAll) locallyClearedBooks[folder.normalizedBookFolder()] = modified
    }

    private fun readCoalesced(folder: String): List<ReadingStatistics> =
        (readStatistics(activeRoot(folder)).orEmpty() + readStatistics(archiveRoot(folder)).orEmpty()).deduplicateReadingStatistics()

    private fun readStatistics(root: File): List<ReadingStatistics>? {
        val file = root.resolve("statistics.json")
        if (!file.exists()) return null
        if (!file.isFile) throw IOException("Statistics sidecar is not a file.")
        return json.decodeFromString(serializer, file.readText()).deduplicateReadingStatistics()
    }

    private fun readMetadata(root: File): BookMetadata {
        val file = root.resolve("metadata.json")
        return if (file.exists()) json.decodeFromString(BookMetadata.serializer(), file.readText()) else
            BookMetadata(root.name, root.name, null, root.name, 0.0)
    }

    private fun writeStatistics(root: File, statistics: List<ReadingStatistics>) =
        atomicWrite(root.resolve("statistics.json"), json.encodeToString(serializer, statistics.deduplicateReadingStatistics()))

    private fun atomicWrite(file: File, contents: String) {
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Unable to create statistics directory." }
        val temporary = File.createTempFile(".${file.name}-", ".tmp", parent)
        try {
            temporary.outputStream().use { stream ->
                stream.write(contents.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
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
        mutex.withLock {
            migrateReservedStatisticsBook(booksDirectory)
            block()
        }
    }
}
