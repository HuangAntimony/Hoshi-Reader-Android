package moe.antimony.hoshi.epub

import android.content.ContentResolver
import android.net.Uri
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.sasayaki.SasayakiMatchData
import moe.antimony.hoshi.features.sasayaki.SasayakiPlaybackData
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.validateImportFile
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream

class BookRepository(
    filesDir: File,
    private val fileDataSource: BookFileDataSource = BookFileDataSource(filesDir),
    private val sidecarDataSource: BookSidecarDataSource = BookSidecarDataSource(),
    private val clock: BookClock = SystemBookClock,
) {
    private val importDataSource = BookImportDataSource(filesDir, fileDataSource)

    val currentBookFile: File get() = fileDataSource.currentBookFile

    fun loadAllBooks(): List<File> = fileDataSource.loadAllBooks()

    fun loadBookEntries(sortOption: BookSortOption = BookSortOption.Recent): List<BookEntry> {
        val entries = loadAllBooks()
            .map { root ->
                BookEntry(
                    root = root,
                    metadata = loadMetadata(root) ?: root.fallbackMetadata(),
                )
            }
        return when (sortOption) {
            BookSortOption.Recent -> entries.sortedByDescending { it.metadata.lastAccess }
            BookSortOption.Title -> entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.metadata.title.orEmpty() })
        }
    }

    fun loadBookEntry(bookId: String): BookEntry? =
        loadAllBooks()
            .asSequence()
            .map { root ->
                BookEntry(
                    root = root,
                    metadata = loadMetadata(root) ?: root.fallbackMetadata(),
                )
            }
            .firstOrNull { it.metadata.id == bookId }

    fun createBookDirectory(folder: String = UUID.randomUUID().toString()): File =
        fileDataSource.createBookDirectory(folder)

    fun createBookDirectoryForImportedTitle(title: String): File =
        fileDataSource.createBookDirectoryForImportedTitle(title)

    fun loadMetadata(bookRoot: File): BookMetadata? =
        sidecarDataSource.loadMetadata(bookRoot)

    fun saveMetadata(bookRoot: File, metadata: BookMetadata) {
        sidecarDataSource.saveMetadata(bookRoot, metadata)
    }

    fun coverFile(entry: BookEntry): File? = fileDataSource.coverFile(entry)

    fun deleteBook(bookRoot: File) {
        fileDataSource.deleteBook(bookRoot)
    }

    fun loadBookmark(bookRoot: File): Bookmark? =
        sidecarDataSource.loadBookmark(bookRoot)

    fun saveBookmark(bookRoot: File, bookmark: Bookmark) {
        sidecarDataSource.saveBookmark(bookRoot, bookmark)
    }

    fun loadBookInfo(bookRoot: File): BookInfo? =
        sidecarDataSource.loadBookInfo(bookRoot)

    fun saveBookInfo(bookRoot: File, bookInfo: BookInfo) {
        sidecarDataSource.saveBookInfo(bookRoot, bookInfo)
    }

    fun loadSasayakiMatch(bookRoot: File): SasayakiMatchData? =
        sidecarDataSource.loadSasayakiMatch(bookRoot)

    fun saveSasayakiMatch(bookRoot: File, match: SasayakiMatchData) {
        sidecarDataSource.saveSasayakiMatch(bookRoot, match)
    }

    fun loadSasayakiPlayback(bookRoot: File): SasayakiPlaybackData? =
        sidecarDataSource.loadSasayakiPlayback(bookRoot)

    fun saveSasayakiPlayback(bookRoot: File, playback: SasayakiPlaybackData) {
        sidecarDataSource.saveSasayakiPlayback(bookRoot, playback)
    }

    fun loadReadingProgress(bookRoot: File): Double {
        val total = loadBookInfo(bookRoot)?.characterCount ?: return 0.0
        if (total <= 0) return 0.0
        val current = loadBookmark(bookRoot)?.characterCount ?: return 0.0
        return current.toDouble().div(total.toDouble()).coerceIn(0.0, 1.0)
    }

    fun currentAppleReferenceDateSeconds(): Double = clock.currentAppleReferenceDateSeconds()

    fun importBook(contentResolver: ContentResolver, uri: Uri): File =
        importDataSource.importBook(contentResolver, uri)

    private fun File.fallbackMetadata(): BookMetadata =
        BookMetadata(
            id = name,
            title = null,
            cover = null,
            folder = name,
            lastAccess = (lastModified().toDouble() / 1000.0) - APPLE_REFERENCE_EPOCH_SECONDS,
        )
}

class BookFileDataSource(filesDir: File) {
    private val booksDirectory = File(filesDir, "Books")

    val currentBookFile: File = File(booksDirectory, "current.epub")

    fun loadAllBooks(): List<File> =
        booksDirectory
            .listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

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

    fun createBookDirectoryForImportedTitle(title: String): File {
        val safeTitle = title.sanitizeImportedBookTitle()
        require(safeTitle.isNotBlank()) { "EPUB title is empty" }
        return createBookDirectory(safeTitle)
    }

    fun coverFile(entry: BookEntry): File? {
        val cover = entry.metadata.cover?.takeIf { it.isNotBlank() } ?: return null
        val root = entry.root.canonicalFile
        val file = root.resolve(cover).canonicalFile
        if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) return null
        return file.takeIf { it.isFile }
    }

    fun deleteBook(bookRoot: File) {
        val root = bookRoot.canonicalFile
        val booksRoot = booksDirectory.canonicalFile
        require(root.path != booksRoot.path && root.path.startsWith(booksRoot.path + File.separator)) {
            "Unsafe book directory: ${bookRoot.path}"
        }
        if (root.exists()) {
            root.deleteRecursively()
        }
    }
}

class BookImportDataSource(
    private val filesDir: File,
    private val fileDataSource: BookFileDataSource,
    private val parser: EpubBookParser = EpubBookParser(),
) {
    fun importBook(contentResolver: ContentResolver, uri: Uri): File {
        contentResolver.validateImportFile(uri, ImportFileType.Epub)
        val tempRoot = File(filesDir, "ImportTemp/${UUID.randomUUID()}").canonicalFile
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected EPUB" }
            runCatching {
                tempRoot.mkdirs()
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val output = tempRoot.resolve(entry.name).canonicalFile
                        val root = tempRoot.canonicalFile
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
                tempRoot.deleteRecursively()
                throw it
            }
        }
        val parsedBook = runCatching { parser.parse(tempRoot) }
            .onFailure { tempRoot.deleteRecursively() }
            .getOrThrow()
        val targetRoot = fileDataSource.createBookDirectoryForImportedTitle(parsedBook.title)
        if (targetRoot.listFiles()?.isNotEmpty() == true) {
            tempRoot.deleteRecursively()
            return targetRoot
        }
        targetRoot.deleteRecursively()
        check(tempRoot.renameTo(targetRoot)) { "Unable to move imported EPUB into Books/${targetRoot.name}" }
        return targetRoot
    }
}

class BookSidecarDataSource {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "    "
        encodeDefaults = true
    }

    fun loadMetadata(bookRoot: File): BookMetadata? =
        loadJson(BookMetadata.serializer(), bookRoot.resolve(METADATA_FILE_NAME))

    fun saveMetadata(bookRoot: File, metadata: BookMetadata) {
        saveJson(bookRoot, METADATA_FILE_NAME, BookMetadata.serializer(), metadata)
    }

    fun loadBookmark(bookRoot: File): Bookmark? =
        loadJson(Bookmark.serializer(), bookRoot.resolve(BOOKMARK_FILE_NAME))

    fun saveBookmark(bookRoot: File, bookmark: Bookmark) {
        saveJson(bookRoot, BOOKMARK_FILE_NAME, Bookmark.serializer(), bookmark)
    }

    fun loadBookInfo(bookRoot: File): BookInfo? =
        loadJson(BookInfo.serializer(), bookRoot.resolve(BOOKINFO_FILE_NAME))

    fun saveBookInfo(bookRoot: File, bookInfo: BookInfo) {
        saveJson(bookRoot, BOOKINFO_FILE_NAME, BookInfo.serializer(), bookInfo)
    }

    fun loadSasayakiMatch(bookRoot: File): SasayakiMatchData? =
        loadJson(SasayakiMatchData.serializer(), bookRoot.resolve(SASAYAKI_MATCH_FILE_NAME))

    fun saveSasayakiMatch(bookRoot: File, match: SasayakiMatchData) {
        saveJson(bookRoot, SASAYAKI_MATCH_FILE_NAME, SasayakiMatchData.serializer(), match)
    }

    fun loadSasayakiPlayback(bookRoot: File): SasayakiPlaybackData? =
        loadJson(SasayakiPlaybackData.serializer(), bookRoot.resolve(SASAYAKI_PLAYBACK_FILE_NAME))

    fun saveSasayakiPlayback(bookRoot: File, playback: SasayakiPlaybackData) {
        saveJson(bookRoot, SASAYAKI_PLAYBACK_FILE_NAME, SasayakiPlaybackData.serializer(), playback)
    }

    private fun <T> loadJson(serializer: KSerializer<T>, file: File): T? {
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
    }

    private fun <T> saveJson(bookRoot: File, fileName: String, serializer: KSerializer<T>, value: T) {
        bookRoot.mkdirs()
        bookRoot.resolve(fileName).writeText(json.encodeToString(serializer, value))
    }
}

interface BookClock {
    fun currentAppleReferenceDateSeconds(): Double
}

object SystemBookClock : BookClock {
    override fun currentAppleReferenceDateSeconds(): Double {
        val now = Instant.now()
        return now.epochSecond.toDouble() + (now.nano.toDouble() / 1_000_000_000.0) - APPLE_REFERENCE_EPOCH_SECONDS
    }
}

private const val METADATA_FILE_NAME = "metadata.json"
private const val BOOKMARK_FILE_NAME = "bookmark.json"
private const val BOOKINFO_FILE_NAME = "bookinfo.json"
private const val SASAYAKI_MATCH_FILE_NAME = "sasayaki_match.json"
private const val SASAYAKI_PLAYBACK_FILE_NAME = "sasayaki_playback.json"
private const val APPLE_REFERENCE_EPOCH_SECONDS = 978_307_200.0

private fun String.sanitizeImportedBookTitle(): String =
    split(Regex("[\\\\/:*?\"<>|\\n\\r\\u0000-\\u001F]"))
        .joinToString("_")
        .trim()
