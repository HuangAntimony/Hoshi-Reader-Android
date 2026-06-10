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
import moe.antimony.hoshi.features.diagnostics.PerformanceLog
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
        val totalStart = PerformanceLog.start()
        PerformanceLog.d(PerformanceLog.ReaderTag, "reader route load started bookId=$bookId")
        runCatching {
            val entryStart = PerformanceLog.start()
            val entry = repository.loadBookEntry(bookId)
                ?: error("Book not found.")
            PerformanceLog.dElapsed(
                PerformanceLog.ReaderTag,
                "loadBookEntry",
                entryStart,
                "bookId=$bookId root=${entry.root.name}",
            )
            val bookInfoStart = PerformanceLog.start()
            val cachedBookInfo = repository.loadReaderBookInfo(entry.root)
            PerformanceLog.dElapsed(
                PerformanceLog.ReaderTag,
                "loadReaderBookInfo",
                bookInfoStart,
                "root=${entry.root.name} cached=${cachedBookInfo != null}",
            )
            val parseStart = PerformanceLog.start()
            val parsedBook = parser.parse(entry.root, cachedBookInfo)
            PerformanceLog.dElapsed(
                PerformanceLog.ReaderTag,
                "parse reader EPUB",
                parseStart,
                "root=${entry.root.name} chapters=${parsedBook.chapters.size} chars=${parsedBook.bookInfo.characterCount}",
            )
            val metadataStart = PerformanceLog.start()
            val metadata = entry.metadata.copy(
                title = parsedBook.title,
                cover = repository.metadataCoverPath(entry.root, parsedBook.coverHref) ?: entry.metadata.cover,
                folder = entry.root.name,
                lastAccess = repository.currentAppleReferenceDateSeconds(),
            )
            repository.saveMetadata(
                entry.root,
                metadata,
            )
            PerformanceLog.dElapsed(
                PerformanceLog.ReaderTag,
                "update reader metadata",
                metadataStart,
                "root=${entry.root.name} cover=${metadata.cover != null}",
            )
            val displayEntry = entry.copy(metadata = metadata)
            val displayBook = parsedBook.copy(title = displayEntry.displayTitle)
            if (cachedBookInfo != displayBook.bookInfo) {
                val saveBookInfoStart = PerformanceLog.start()
                repository.saveBookInfo(entry.root, displayBook.bookInfo)
                PerformanceLog.dElapsed(
                    PerformanceLog.ReaderTag,
                    "save reader bookinfo",
                    saveBookInfoStart,
                    "root=${entry.root.name}",
                )
            }
            val bookCoverFile = resolveMetadataCoverFile(entry.root, metadata.cover)
            val beforeBookmarkStart = PerformanceLog.start()
            beforeBookmarkLoad(displayEntry)
            PerformanceLog.dElapsed(
                PerformanceLog.ReaderTag,
                "before bookmark reader hook",
                beforeBookmarkStart,
                "root=${entry.root.name}",
            )
            val bookmarkStart = PerformanceLog.start()
            val bookmark = repository.loadBookmark(entry.root)
            PerformanceLog.dElapsed(
                PerformanceLog.ReaderTag,
                "load reader bookmark",
                bookmarkStart,
                "root=${entry.root.name} bookmark=${bookmark != null}",
            )
            PerformanceLog.dElapsed(
                PerformanceLog.ReaderTag,
                "reader route load ready",
                totalStart,
                "bookId=$bookId root=${entry.root.name}",
            )
            ReaderRouteLoadState.Ready(
                entry = displayEntry,
                bookRoot = entry.root,
                book = displayBook,
                bookCoverFile = bookCoverFile,
                bookmark = bookmark,
            )
        }.getOrElse { error ->
            PerformanceLog.dElapsed(
                PerformanceLog.ReaderTag,
                "reader route load failed",
                totalStart,
                "bookId=$bookId error=${error::class.java.simpleName}",
            )
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

    data class Error(
        val message: String,
    ) : ReaderRouteLoadState
}

internal fun resolveMetadataCoverFile(bookRoot: File, metadataCoverPath: String?): File? {
    val fileName = metadataCoverPath?.takeIf { it.isNotBlank() }?.let { File(it).name } ?: return null
    return bookRoot.resolve(fileName).takeIf { it.isFile }
}
