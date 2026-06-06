package moe.antimony.hoshi.epub


import android.content.ContentResolver
import android.net.Uri
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
data class Bookmark(
    val chapterIndex: Int,
    val progress: Double,
    val characterCount: Int,
    val lastModified: Double? = null,
)

@Serializable
data class BookInfo(
    val characterCount: Int,
    val chapterInfo: Map<String, ChapterInfo>,
) {
    @Serializable
    data class ChapterInfo(
        val spineIndex: Int?,
        val currentTotal: Int,
        val chapterCount: Int,
    )
}

@Serializable
data class BookMetadata(
    val id: String,
    val title: String?,
    val cover: String?,
    val folder: String?,
    val lastAccess: Double,
    val renamedTitle: String? = null,
    val epub: String? = null,
) {
    val displayTitle: String
        get() = renamedTitle?.takeIf { it.isNotBlank() } ?: title.orEmpty()
}

@Serializable
data class BookShelf(
    val name: String,
    val bookIds: List<String>,
)

data class BookEntry(
    val root: File,
    val metadata: BookMetadata,
) {
    val displayTitle: String
        get() = metadata.displayTitle.ifBlank { root.name }
}

enum class BookSortOption {
    Recent,
    Title,
}

class BookStorage(filesDir: File) {
    private val repository = BookRepository(filesDir)

    val currentBookFile: File get() = repository.currentBookFile

    suspend fun loadAllBooks(): List<File> = repository.loadAllBooks()

    suspend fun loadBookEntries(sortOption: BookSortOption = BookSortOption.Recent): List<BookEntry> =
        repository.loadBookEntries(sortOption)

    suspend fun loadBookEntry(bookId: String): BookEntry? = repository.loadBookEntry(bookId)

    suspend fun createBookDirectory(folder: String = UUID.randomUUID().toString()): File =
        repository.createBookDirectory(folder)

    suspend fun createBookDirectoryForImportedTitle(title: String): File =
        repository.createBookDirectoryForImportedTitle(title)

    suspend fun loadMetadata(bookRoot: File): BookMetadata? = repository.loadMetadata(bookRoot)

    suspend fun saveMetadata(bookRoot: File, metadata: BookMetadata) {
        repository.saveMetadata(bookRoot, metadata)
    }

    suspend fun coverFile(entry: BookEntry): File? = repository.coverFile(entry)

    suspend fun epubFile(entry: BookEntry): File? = repository.epubFile(entry)

    suspend fun metadataCoverPath(bookRoot: File, coverHref: String?): String? =
        repository.metadataCoverPath(bookRoot, coverHref)

    suspend fun deleteBook(bookRoot: File) {
        repository.deleteBook(bookRoot)
    }

    suspend fun loadShelves(): List<BookShelf> = repository.loadShelves()

    suspend fun saveShelves(shelves: List<BookShelf>) {
        repository.saveShelves(shelves)
    }

    suspend fun loadBookmark(bookRoot: File): Bookmark? = repository.loadBookmark(bookRoot)

    suspend fun saveBookmark(bookRoot: File, bookmark: Bookmark) {
        repository.saveBookmark(bookRoot, bookmark)
    }

    suspend fun loadStatistics(bookRoot: File): List<ReadingStatistics> =
        repository.loadStatistics(bookRoot)

    suspend fun saveStatistics(bookRoot: File, statistics: List<ReadingStatistics>) {
        repository.saveStatistics(bookRoot, statistics)
    }

    suspend fun loadHighlights(bookRoot: File): List<ReaderHighlight> =
        repository.loadHighlights(bookRoot)

    suspend fun saveHighlights(bookRoot: File, highlights: List<ReaderHighlight>) {
        repository.saveHighlights(bookRoot, highlights)
    }

    suspend fun loadBookInfo(bookRoot: File): BookInfo? = repository.loadBookInfo(bookRoot)

    suspend fun saveBookInfo(bookRoot: File, bookInfo: BookInfo) {
        repository.saveBookInfo(bookRoot, bookInfo)
    }

    suspend fun loadSasayakiMatch(bookRoot: File): SasayakiMatchData? =
        repository.loadSasayakiMatch(bookRoot)

    suspend fun saveSasayakiMatch(bookRoot: File, match: SasayakiMatchData) {
        repository.saveSasayakiMatch(bookRoot, match)
    }

    suspend fun loadSasayakiPlayback(bookRoot: File): SasayakiPlaybackData? =
        repository.loadSasayakiPlayback(bookRoot)

    suspend fun saveSasayakiPlayback(bookRoot: File, playback: SasayakiPlaybackData) {
        repository.saveSasayakiPlayback(bookRoot, playback)
    }

    suspend fun loadReadingProgress(bookRoot: File): Double = repository.loadReadingProgress(bookRoot)

    fun currentAppleReferenceDateSeconds(): Double = repository.currentAppleReferenceDateSeconds()

    suspend fun importBook(contentResolver: ContentResolver, uri: Uri): File =
        repository.importBook(contentResolver, uri)
}
