package moe.antimony.hoshi.features.bookshelf

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.isUuidString
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first

internal interface BookshelfRepository {
    suspend fun loadBooks(sortOption: BookSortOption): BookshelfLoadResult
    suspend fun openBook(entry: BookEntry): String
    suspend fun importBook(uri: Uri): String
    suspend fun deleteBook(entry: BookEntry)
    suspend fun deleteBooks(entries: Collection<BookEntry>)
    suspend fun moveBooks(bookIds: Set<String>, shelfName: String?)
    suspend fun createShelf(name: String)
    suspend fun deleteShelf(name: String)
    suspend fun moveShelf(fromIndex: Int, toIndex: Int)
    suspend fun markRead(entry: BookEntry)
    suspend fun changeSort(sortOption: BookSortOption)
    suspend fun changeShowReading(showReading: Boolean)
    suspend fun rebuildLookupQuery()
}

internal class AndroidBookshelfRepository(
    private val contentResolver: ContentResolver,
    private val bookRepository: BookRepository,
    private val dictionaryRepository: DictionaryRepository,
    private val settingsRepository: BookshelfSettingsRepository,
    private val bookParser: EpubBookParser = EpubBookParser(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BookshelfRepository {
    override suspend fun loadBooks(sortOption: BookSortOption): BookshelfLoadResult = withContext(ioDispatcher) {
        val entries = bookRepository.loadBookEntries(sortOption)
        val shelves = bookRepository.loadShelves()
        BookshelfLoadResult(
            entries = entries,
            progressById = loadBookProgressById(entries, bookRepository),
            shelves = shelves,
            settings = settingsRepository.settings.first(),
        )
    }

    override suspend fun openBook(entry: BookEntry): String = withContext(ioDispatcher) {
        val parsedBook = bookParser.parse(entry.root)
        saveMetadata(entry.root, parsedBook, bookRepository.loadMetadata(entry.root))
        saveBookInfo(entry.root, parsedBook)
        readerBookId(entry.root)
    }

    override suspend fun importBook(uri: Uri): String = withContext(ioDispatcher) {
        val root = bookRepository.importBook(contentResolver, uri)
        val parsedBook = bookParser.parse(root)
        saveMetadata(root, parsedBook, bookRepository.loadMetadata(root))
        saveBookInfo(root, parsedBook)
        readerBookId(root)
    }

    override suspend fun deleteBook(entry: BookEntry) = withContext(ioDispatcher) {
        bookRepository.deleteBook(entry.root)
    }

    override suspend fun deleteBooks(entries: Collection<BookEntry>) = withContext(ioDispatcher) {
        entries.forEach { bookRepository.deleteBook(it.root) }
    }

    override suspend fun moveBooks(bookIds: Set<String>, shelfName: String?) = withContext(ioDispatcher) {
        val shelves = bookRepository.loadShelves()
            .map { shelf -> shelf.copy(bookIds = shelf.bookIds.filterNot { it in bookIds }) }
            .map { shelf ->
                if (shelf.name == shelfName) {
                    shelf.copy(bookIds = (shelf.bookIds + bookIds).distinct())
                } else {
                    shelf
                }
            }
        bookRepository.saveShelves(shelves)
    }

    override suspend fun createShelf(name: String) = withContext(ioDispatcher) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext
        val shelves = bookRepository.loadShelves()
        if (shelves.none { it.name == trimmed }) {
            bookRepository.saveShelves(shelves + BookShelf(trimmed, emptyList()))
        }
    }

    override suspend fun deleteShelf(name: String) = withContext(ioDispatcher) {
        bookRepository.saveShelves(bookRepository.loadShelves().filterNot { it.name == name })
    }

    override suspend fun moveShelf(fromIndex: Int, toIndex: Int) = withContext(ioDispatcher) {
        val shelves = bookRepository.loadShelves().toMutableList()
        if (fromIndex !in shelves.indices || toIndex !in shelves.indices || fromIndex == toIndex) {
            return@withContext
        }
        val shelf = shelves.removeAt(fromIndex)
        shelves.add(toIndex, shelf)
        bookRepository.saveShelves(shelves)
    }

    override suspend fun markRead(entry: BookEntry) = withContext(ioDispatcher) {
        val bookInfo = bookRepository.loadBookInfo(entry.root) ?: return@withContext
        val lastChapter = bookInfo.chapterInfo.values
            .mapNotNull { it.spineIndex }
            .maxOrNull()
            ?: 0
        bookRepository.saveBookmark(
            entry.root,
            Bookmark(
                chapterIndex = lastChapter,
                progress = 1.0,
                characterCount = bookInfo.characterCount,
                lastModified = bookRepository.currentAppleReferenceDateSeconds(),
            ),
        )
    }

    override suspend fun changeSort(sortOption: BookSortOption) {
        settingsRepository.update { it.copy(sortOption = sortOption) }
    }

    override suspend fun changeShowReading(showReading: Boolean) {
        settingsRepository.update { it.copy(showReading = showReading) }
    }

    override suspend fun rebuildLookupQuery() {
        dictionaryRepository.rebuildLookupQuery()
    }

    private suspend fun saveMetadata(root: File, parsedBook: EpubBook, previous: BookMetadata? = null) {
        val metadata = BookMetadata(
            id = previous?.id?.takeIf { it.isUuidString() } ?: UUID.randomUUID().toString(),
            title = parsedBook.title,
            cover = bookRepository.metadataCoverPath(root, parsedBook.coverHref),
            folder = root.name,
            lastAccess = bookRepository.currentAppleReferenceDateSeconds(),
        )
        bookRepository.saveMetadata(root, metadata)
    }

    private suspend fun saveBookInfo(root: File, parsedBook: EpubBook) {
        bookRepository.saveBookInfo(root, parsedBook.bookInfo)
    }

    private suspend fun readerBookId(root: File): String =
        bookRepository.loadMetadata(root)?.id ?: root.name
}
