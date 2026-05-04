package moe.antimony.hoshi.navigation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.ReaderRouteBookRepository
import java.io.File

internal class ReaderRouteStateHolder(
    private val repository: ReaderRouteBookRepository,
    private val parser: ReaderRouteEpubParser = DefaultReaderRouteEpubParser,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun load(bookId: String): ReaderRouteLoadState = withContext(ioDispatcher) {
        runCatching {
            val entry = repository.loadBookEntry(bookId)
                ?: error("Book not found.")
            val parsedBook = parser.parse(entry.root)
            repository.saveMetadata(
                entry.root,
                entry.metadata.copy(
                    title = parsedBook.title,
                    cover = parsedBook.coverHref,
                    folder = entry.root.name,
                    lastAccess = repository.currentAppleReferenceDateSeconds(),
                ),
            )
            repository.saveBookInfo(entry.root, parsedBook.bookInfo)
            ReaderRouteLoadState.Ready(
                bookRoot = entry.root,
                book = parsedBook,
                bookmark = repository.loadBookmark(entry.root),
            )
        }.getOrElse { error ->
            ReaderRouteLoadState.Error(error.localizedMessage ?: "Failed to open EPUB.")
        }
    }

    suspend fun saveBookmark(
        state: ReaderRouteLoadState.Ready,
        chapterIndex: Int,
        progress: Double,
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
        }
        onBookmarkSaved()
    }
}

internal interface ReaderRouteEpubParser {
    fun parse(root: File): EpubBook
}

private object DefaultReaderRouteEpubParser : ReaderRouteEpubParser {
    override fun parse(root: File): EpubBook =
        EpubBookParser().parse(root)
}

internal sealed interface ReaderRouteLoadState {
    data object Loading : ReaderRouteLoadState

    data class Ready(
        val bookRoot: File,
        val book: EpubBook,
        val bookmark: Bookmark?,
    ) : ReaderRouteLoadState

    data class Error(
        val message: String,
    ) : ReaderRouteLoadState
}
