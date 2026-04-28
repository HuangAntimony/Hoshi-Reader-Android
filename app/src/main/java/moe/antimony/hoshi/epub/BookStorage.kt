package moe.antimony.hoshi.epub

import android.content.ContentResolver
import android.net.Uri
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream

@Serializable
data class Bookmark(
    val chapterIndex: Int,
    val progress: Double,
    val characterCount: Int,
    val lastModified: Double? = null,
)

@Serializable
data class BookMetadata(
    val id: String,
    val title: String?,
    val cover: String?,
    val folder: String?,
    val lastAccess: Double,
)

data class BookEntry(
    val root: File,
    val metadata: BookMetadata,
)

class BookStorage(private val filesDir: File) {
    private val booksDirectory = File(filesDir, "Books")
    val currentBookFile: File = File(booksDirectory, "current.epub")
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "    "
        encodeDefaults = true
    }

    fun loadAllBooks(): List<File> =
        booksDirectory
            .listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun loadBookEntries(): List<BookEntry> =
        loadAllBooks()
            .map { root ->
                BookEntry(
                    root = root,
                    metadata = loadMetadata(root) ?: root.fallbackMetadata(),
                )
            }
            .sortedByDescending { it.metadata.lastAccess }

    fun createBookDirectory(folder: String = UUID.randomUUID().toString()): File {
        booksDirectory.mkdirs()
        val root = booksDirectory.resolve(folder).canonicalFile
        val booksRoot = booksDirectory.canonicalFile
        require(root.path == booksRoot.path || root.path.startsWith(booksRoot.path + File.separator)) {
            "Unsafe book folder: $folder"
        }
        root.mkdirs()
        return root
    }

    fun loadMetadata(bookRoot: File): BookMetadata? {
        val file = bookRoot.resolve(METADATA_FILE_NAME)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<BookMetadata>(file.readText()) }.getOrNull()
    }

    fun saveMetadata(bookRoot: File, metadata: BookMetadata) {
        bookRoot.mkdirs()
        bookRoot.resolve(METADATA_FILE_NAME).writeText(json.encodeToString(metadata))
    }

    fun coverFile(entry: BookEntry): File? {
        val cover = entry.metadata.cover?.takeIf { it.isNotBlank() } ?: return null
        val root = entry.root.canonicalFile
        val file = root.resolve(cover).canonicalFile
        if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) return null
        return file.takeIf { it.isFile }
    }

    fun loadBookmark(bookRoot: File): Bookmark? {
        val file = bookRoot.resolve(BOOKMARK_FILE_NAME)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<Bookmark>(file.readText()) }.getOrNull()
    }

    fun saveBookmark(bookRoot: File, bookmark: Bookmark) {
        bookRoot.mkdirs()
        bookRoot.resolve(BOOKMARK_FILE_NAME).writeText(json.encodeToString(bookmark))
    }

    fun currentAppleReferenceDateSeconds(): Double {
        val now = Instant.now()
        return now.epochSecond.toDouble() + (now.nano.toDouble() / 1_000_000_000.0) - APPLE_REFERENCE_EPOCH_SECONDS
    }

    fun importBook(contentResolver: ContentResolver, uri: Uri): File {
        val bookRoot = createBookDirectory()
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected EPUB" }
            runCatching {
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val output = bookRoot.resolve(entry.name).canonicalFile
                        val root = bookRoot.canonicalFile
                        require(output.path == root.path || output.path.startsWith(root.path + File.separator)) {
                            "Unsafe EPUB entry: ${entry.name}"
                        }
                        if (entry.isDirectory) {
                            output.mkdirs()
                        } else {
                            output.parentFile?.mkdirs()
                            output.outputStream().use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }.onFailure {
                bookRoot.deleteRecursively()
                throw it
            }
        }
        return bookRoot
    }

    private companion object {
        const val METADATA_FILE_NAME = "metadata.json"
        const val BOOKMARK_FILE_NAME = "bookmark.json"
        const val APPLE_REFERENCE_EPOCH_SECONDS = 978_307_200.0
    }

    private fun File.fallbackMetadata(): BookMetadata =
        BookMetadata(
            id = name,
            title = null,
            cover = null,
            folder = name,
            lastAccess = (lastModified().toDouble() / 1000.0) - APPLE_REFERENCE_EPOCH_SECONDS,
        )
}
