package moe.antimony.hoshi.navigation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.ReaderRouteBookRepository
import java.io.File

internal class ReaderRouteStateHolder(
    private val repository: ReaderRouteBookRepository,
    private val parser: ReaderRouteEpubParser = DefaultReaderRouteEpubParser,
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
            val metadata = entry.metadata.copy(
                title = parsedBook.title,
                cover = repository.metadataCoverPath(entry.root, parsedBook.coverHref),
                folder = entry.root.name,
                lastAccess = repository.currentAppleReferenceDateSeconds(),
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
            beforeBookmarkLoad(displayEntry)
            val bookmark = repository.loadBookmark(entry.root)
            ReaderRouteLoadState.Ready(
                entry = displayEntry,
                bookRoot = entry.root,
                book = displayBook,
                bookmark = bookmark,
            )
        }.getOrElse { error ->
            ReaderRouteLoadState.Error(error.localizedMessage ?: "Failed to open EPUB.")
        }
    }

    suspend fun saveBookmark(
        state: ReaderRouteLoadState.Ready,
        chapterIndex: Int,
        progress: Double,
        statistics: List<ReadingStatistics>? = null,
        onBookmarkSaved: () -> Unit,
    ) {
        withContext(ioDispatcher) {
            val bookmark = Bookmark(
                chapterIndex = chapterIndex,
                progress = progress,
                characterCount = state.book.characterCountAt(chapterIndex, progress),
                lastModified = repository.currentAppleReferenceDateSeconds(),
            )
            repository.saveBookmark(state.bookRoot, bookmark)
            if (statistics != null) {
                repository.saveStatistics(state.bookRoot, statistics)
            }
        }
        onBookmarkSaved()
    }
}

internal interface ReaderRouteEpubParser {
    fun parse(root: File, cachedBookInfo: BookInfo? = null): EpubBook
}

private object DefaultReaderRouteEpubParser : ReaderRouteEpubParser {
    override fun parse(root: File, cachedBookInfo: BookInfo?): EpubBook =
        EpubBookParser().parse(root, cachedBookInfo = cachedBookInfo)
}

internal sealed interface ReaderRouteLoadState {
    data object Loading : ReaderRouteLoadState

    data class Ready(
        val entry: moe.antimony.hoshi.epub.BookEntry,
        val bookRoot: File,
        val book: EpubBook,
        val bookmark: Bookmark?,
    ) : ReaderRouteLoadState

    data class Error(
        val message: String,
    ) : ReaderRouteLoadState
}
