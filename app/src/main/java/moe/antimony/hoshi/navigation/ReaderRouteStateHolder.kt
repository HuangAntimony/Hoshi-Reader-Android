package moe.antimony.hoshi.navigation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.features.sync.resolveTtuCharacterPosition
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.ReadingSessions
import moe.antimony.hoshi.epub.ReaderRouteBookRepository
import java.io.File

internal class ReaderRouteStateHolder(
    private val repository: ReaderRouteBookRepository,
    private val parser: ReaderRouteEpubParser = DefaultReaderRouteEpubParser(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun load(
        bookId: String,
        beforeBookmarkLoad: suspend (moe.antimony.hoshi.epub.BookEntry) -> Unit = {},
    ): ReaderRouteLoadState = withContext(ioDispatcher) {
        runCatching {
            val entry = repository.loadBookEntry(bookId)
                ?: error("Book not found.")
            val cachedBookInfo = repository.loadReaderBookInfo(entry.root)
            val parsedBook = parser.parse(entry.root, cachedBookInfo)
            val currentMetadata = repository.loadBookEntry(bookId)!!.metadata
            val metadata = currentMetadata.copy(
                title = currentMetadata.title ?: parsedBook.title,
                cover = currentMetadata.cover ?: repository.metadataCoverPath(entry.root, parsedBook.coverHref),
                folder = entry.root.name,
                lastAccess = repository.currentAppleReferenceDateSeconds(),
                bookLanguage = currentMetadata.bookLanguage ?: parsedBook.language,
            )
            repository.saveMetadata(
                entry.root,
                metadata,
            )
            val displayEntry = entry.copy(metadata = metadata)
            val displayBook = parsedBook.copy(title = displayEntry.displayTitle)
            if (cachedBookInfo != displayBook.bookInfo) {
                repository.saveBookInfo(entry.root, displayBook.bookInfo)
            }
            if (cachedBookInfo == null) {
                repository.loadBookmark(entry.root)?.let { bookmark ->
                    val position = displayBook.bookInfo.resolveTtuCharacterPosition(bookmark.characterCount)
                    repository.saveBookmark(entry.root, bookmark.copy(chapterIndex = position?.spineIndex ?: 0, progress = position?.progress ?: 0.0))
                }
            }
            beforeBookmarkLoad(displayEntry)
            val refreshed = repository.loadBookEntry(bookId)!!
            val bookCoverFile = resolveMetadataCoverFile(entry.root, refreshed.metadata.cover)
            val bookmark = repository.loadBookmark(entry.root)
            ReaderRouteLoadState.Ready(
                entry = refreshed,
                bookRoot = entry.root,
                book = displayBook.copy(title = refreshed.displayTitle),
                bookCoverFile = bookCoverFile,
                bookmark = bookmark,
            )
        }.getOrElse { ReaderRouteLoadState.Error }
    }

    suspend fun saveBookmark(
        state: ReaderRouteLoadState.Ready,
        chapterIndex: Int,
        progress: Double,
        statistics: ReadingSessions? = null,
        onBookmarkSaved: () -> Unit,
    ) {
        withContext(ioDispatcher) {
            val old = repository.loadBookmark(state.bookRoot)
            val count = state.book.characterCountAt(chapterIndex, progress)
            val bookmark = Bookmark(
                chapterIndex = chapterIndex,
                progress = progress,
                characterCount = count,
                lastModified = if (old?.characterCount == count) old.lastModified else repository.currentAppleReferenceDateSeconds(),
            )
            repository.saveBookmark(state.bookRoot, bookmark)
            if (statistics != null) {
                repository.saveTrackedSessions(state.bookRoot, statistics)
            }
        }
        onBookmarkSaved()
    }
}

internal interface ReaderRouteEpubParser {
    fun parse(root: File, cachedBookInfo: BookInfo? = null): EpubBook
}

internal class DefaultReaderRouteEpubParser(
    private val parser: EpubBookParser = EpubBookParser(),
) : ReaderRouteEpubParser {
    override fun parse(root: File, cachedBookInfo: BookInfo?): EpubBook =
        parser.parse(root, cachedBookInfo = cachedBookInfo)
}

internal sealed interface ReaderRouteLoadState {
    data object Loading : ReaderRouteLoadState

    data class Ready(
        val entry: moe.antimony.hoshi.epub.BookEntry,
        val bookRoot: File,
        val book: EpubBook,
        val bookCoverFile: File?,
        val bookmark: Bookmark?,
    ) : ReaderRouteLoadState

    data object Error : ReaderRouteLoadState
}

internal fun ReaderRouteLoadState.publishProfileActivation(
    activateForBook: (BookMetadata) -> Unit,
    clearLoadedProfile: () -> Unit,
) {
    when (this) {
        is ReaderRouteLoadState.Ready -> activateForBook(entry.metadata)
        is ReaderRouteLoadState.Error -> clearLoadedProfile()
        ReaderRouteLoadState.Loading -> Unit
    }
}

internal fun resolveMetadataCoverFile(bookRoot: File, metadataCoverPath: String?): File? {
    val fileName = metadataCoverPath?.takeIf { it.isNotBlank() }?.let { File(it).name } ?: return null
    return bookRoot.resolve(fileName).takeIf { it.isFile }
}
