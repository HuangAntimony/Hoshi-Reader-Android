package moe.antimony.hoshi.epub

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fileDataSource: BookFileDataSource = BookFileDataSource(filesDir, ioDispatcher),
    private val sidecarDataSource: BookSidecarDataSource = BookSidecarDataSource(ioDispatcher),
    private val clock: BookClock = SystemBookClock,
) : ReaderRouteBookRepository, SasayakiSidecarRepository {
    private val importDataSource = BookImportDataSource(filesDir, fileDataSource, ioDispatcher = ioDispatcher)

    val currentBookFile: File get() = fileDataSource.currentBookFile

    suspend fun loadAllBooks(): List<File> = fileDataSource.loadAllBooks()

    suspend fun loadBookEntries(sortOption: BookSortOption = BookSortOption.Recent): List<BookEntry> {
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

    override suspend fun loadBookEntry(bookId: String): BookEntry? {
        for (root in loadAllBooks()) {
            val entry = BookEntry(
                root = root,
                metadata = loadMetadata(root) ?: root.fallbackMetadata(),
            )
            if (entry.metadata.id == bookId) return entry
        }
        return null
    }

    suspend fun createBookDirectory(folder: String = UUID.randomUUID().toString()): File =
        fileDataSource.createBookDirectory(folder)

    suspend fun createBookDirectoryForImportedTitle(title: String): File =
        fileDataSource.createBookDirectoryForImportedTitle(title)

    suspend fun loadMetadata(bookRoot: File): BookMetadata? =
        sidecarDataSource.loadMetadata(bookRoot)

    override suspend fun saveMetadata(bookRoot: File, metadata: BookMetadata) {
        sidecarDataSource.saveMetadata(bookRoot, metadata)
    }

    suspend fun coverFile(entry: BookEntry): File? = fileDataSource.coverFile(entry)

    suspend fun deleteBook(bookRoot: File) {
        fileDataSource.deleteBook(bookRoot)
    }

    override suspend fun loadBookmark(bookRoot: File): Bookmark? =
        sidecarDataSource.loadBookmark(bookRoot)

    override suspend fun saveBookmark(bookRoot: File, bookmark: Bookmark) {
        sidecarDataSource.saveBookmark(bookRoot, bookmark)
    }

    suspend fun loadBookInfo(bookRoot: File): BookInfo? =
        sidecarDataSource.loadBookInfo(bookRoot)

    override suspend fun saveBookInfo(bookRoot: File, bookInfo: BookInfo) {
        sidecarDataSource.saveBookInfo(bookRoot, bookInfo)
    }

    override suspend fun loadSasayakiMatch(bookRoot: File): SasayakiMatchData? =
        sidecarDataSource.loadSasayakiMatch(bookRoot)

    override suspend fun saveSasayakiMatch(bookRoot: File, match: SasayakiMatchData) {
        sidecarDataSource.saveSasayakiMatch(bookRoot, match)
    }

    override suspend fun loadSasayakiPlayback(bookRoot: File): SasayakiPlaybackData? =
        sidecarDataSource.loadSasayakiPlayback(bookRoot)

    override suspend fun saveSasayakiPlayback(bookRoot: File, playback: SasayakiPlaybackData) {
        sidecarDataSource.saveSasayakiPlayback(bookRoot, playback)
    }

    suspend fun loadReadingProgress(bookRoot: File): Double {
        val total = loadBookInfo(bookRoot)?.characterCount ?: return 0.0
        if (total <= 0) return 0.0
        val current = loadBookmark(bookRoot)?.characterCount ?: return 0.0
        return current.toDouble().div(total.toDouble()).coerceIn(0.0, 1.0)
    }

    override fun currentAppleReferenceDateSeconds(): Double = clock.currentAppleReferenceDateSeconds()

    suspend fun importBook(contentResolver: ContentResolver, uri: Uri): File =
        importDataSource.importBook(contentResolver, uri)

    private suspend fun File.fallbackMetadata(): BookMetadata = withContext(ioDispatcher) {
        BookMetadata(
            id = name,
            title = null,
            cover = null,
            folder = name,
            lastAccess = (lastModified().toDouble() / 1000.0) - APPLE_REFERENCE_EPOCH_SECONDS,
        )
    }
}

interface ReaderRouteBookRepository {
    suspend fun loadBookEntry(bookId: String): BookEntry?
    suspend fun saveMetadata(bookRoot: File, metadata: BookMetadata)
    suspend fun loadBookmark(bookRoot: File): Bookmark?
    suspend fun saveBookmark(bookRoot: File, bookmark: Bookmark)
    suspend fun saveBookInfo(bookRoot: File, bookInfo: BookInfo)
    fun currentAppleReferenceDateSeconds(): Double
}

interface SasayakiSidecarRepository {
    suspend fun loadSasayakiMatch(bookRoot: File): SasayakiMatchData?
    suspend fun saveSasayakiMatch(bookRoot: File, match: SasayakiMatchData)
    suspend fun loadSasayakiPlayback(bookRoot: File): SasayakiPlaybackData?
    suspend fun saveSasayakiPlayback(bookRoot: File, playback: SasayakiPlaybackData)
}

class BookFileDataSource(
    filesDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val booksDirectory = File(filesDir, "Books")

    val currentBookFile: File = File(booksDirectory, "current.epub")

    suspend fun loadAllBooks(): List<File> = withContext(ioDispatcher) {
        booksDirectory
            .listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    suspend fun createBookDirectory(folder: String = UUID.randomUUID().toString()): File = withContext(ioDispatcher) {
        booksDirectory.mkdirs()
        val root = booksDirectory.resolve(folder).canonicalFile
        val booksRoot = booksDirectory.canonicalFile
        require(root.path == booksRoot.path || root.path.startsWith(booksRoot.path + File.separator)) {
            "Unsafe book folder: $folder"
        }
        root.mkdirs()
        root
    }

    suspend fun createBookDirectoryForImportedTitle(title: String): File {
        val safeTitle = title.sanitizeImportedBookTitle()
        require(safeTitle.isNotBlank()) { "EPUB title is empty" }
        return createBookDirectory(safeTitle)
    }

    suspend fun coverFile(entry: BookEntry): File? = withContext(ioDispatcher) {
        val cover = entry.metadata.cover?.takeIf { it.isNotBlank() } ?: return@withContext null
        val root = entry.root.canonicalFile
        val file = root.resolve(cover).canonicalFile
        if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) return@withContext null
        file.takeIf { it.isFile }
    }

    suspend fun deleteBook(bookRoot: File) = withContext(ioDispatcher) {
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun importBook(contentResolver: ContentResolver, uri: Uri): File = withContext(ioDispatcher) {
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
            targetRoot
        } else {
            targetRoot.deleteRecursively()
            check(tempRoot.renameTo(targetRoot)) { "Unable to move imported EPUB into Books/${targetRoot.name}" }
            targetRoot
        }
    }
}

class BookSidecarDataSource(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "    "
        encodeDefaults = true
    }

    suspend fun loadMetadata(bookRoot: File): BookMetadata? =
        loadJson(BookMetadata.serializer(), bookRoot.resolve(METADATA_FILE_NAME))

    suspend fun saveMetadata(bookRoot: File, metadata: BookMetadata) {
        saveJson(bookRoot, METADATA_FILE_NAME, BookMetadata.serializer(), metadata)
    }

    suspend fun loadBookmark(bookRoot: File): Bookmark? =
        loadJson(Bookmark.serializer(), bookRoot.resolve(BOOKMARK_FILE_NAME))

    suspend fun saveBookmark(bookRoot: File, bookmark: Bookmark) {
        saveJson(bookRoot, BOOKMARK_FILE_NAME, Bookmark.serializer(), bookmark)
    }

    suspend fun loadBookInfo(bookRoot: File): BookInfo? =
        loadJson(BookInfo.serializer(), bookRoot.resolve(BOOKINFO_FILE_NAME))

    suspend fun saveBookInfo(bookRoot: File, bookInfo: BookInfo) {
        saveJson(bookRoot, BOOKINFO_FILE_NAME, BookInfo.serializer(), bookInfo)
    }

    suspend fun loadSasayakiMatch(bookRoot: File): SasayakiMatchData? =
        loadJson(SasayakiMatchData.serializer(), bookRoot.resolve(SASAYAKI_MATCH_FILE_NAME))

    suspend fun saveSasayakiMatch(bookRoot: File, match: SasayakiMatchData) {
        saveJson(bookRoot, SASAYAKI_MATCH_FILE_NAME, SasayakiMatchData.serializer(), match)
    }

    suspend fun loadSasayakiPlayback(bookRoot: File): SasayakiPlaybackData? =
        loadJson(SasayakiPlaybackData.serializer(), bookRoot.resolve(SASAYAKI_PLAYBACK_FILE_NAME))

    suspend fun saveSasayakiPlayback(bookRoot: File, playback: SasayakiPlaybackData) {
        saveJson(bookRoot, SASAYAKI_PLAYBACK_FILE_NAME, SasayakiPlaybackData.serializer(), playback)
    }

    private suspend fun <T> loadJson(serializer: KSerializer<T>, file: File): T? = withContext(ioDispatcher) {
        if (!file.isFile) return@withContext null
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
    }

    private suspend fun <T> saveJson(bookRoot: File, fileName: String, serializer: KSerializer<T>, value: T) = withContext(ioDispatcher) {
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
