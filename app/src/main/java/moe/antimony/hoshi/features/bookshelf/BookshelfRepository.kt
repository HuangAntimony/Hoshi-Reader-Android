package moe.antimony.hoshi.features.bookshelf

import android.content.ContentResolver
import android.net.Uri
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubBookParser
import java.io.File

internal interface BookshelfRepository {
    suspend fun loadBooks(sortOption: BookSortOption): BookshelfLoadResult
    suspend fun openBook(entry: BookEntry): String
    suspend fun importBook(uri: Uri): String
    suspend fun deleteBook(entry: BookEntry)
    suspend fun rebuildLookupQuery()
}

internal class AndroidBookshelfRepository(
    private val contentResolver: ContentResolver,
    private val bookRepository: BookRepository,
    private val dictionaryRepository: DictionaryRepository,
    private val bookParser: EpubBookParser = EpubBookParser(),
) : BookshelfRepository {
    override suspend fun loadBooks(sortOption: BookSortOption): BookshelfLoadResult {
        val entries = bookRepository.loadBookEntries(sortOption)
        return BookshelfLoadResult(
            entries = entries,
            progressById = loadBookProgressById(entries, bookRepository),
        )
    }

    override suspend fun openBook(entry: BookEntry): String {
        val parsedBook = bookParser.parse(entry.root)
        saveMetadata(entry.root, parsedBook, bookRepository.loadMetadata(entry.root))
        saveBookInfo(entry.root, parsedBook)
        return readerBookId(entry.root)
    }

    override suspend fun importBook(uri: Uri): String {
        val root = bookRepository.importBook(contentResolver, uri)
        val parsedBook = bookParser.parse(root)
        saveMetadata(root, parsedBook, bookRepository.loadMetadata(root))
        saveBookInfo(root, parsedBook)
        return readerBookId(root)
    }

    override suspend fun deleteBook(entry: BookEntry) {
        bookRepository.deleteBook(entry.root)
    }

    override suspend fun rebuildLookupQuery() {
        dictionaryRepository.rebuildLookupQuery()
    }

    private fun saveMetadata(root: File, parsedBook: EpubBook, previous: BookMetadata? = null) {
        val metadata = BookMetadata(
            id = previous?.id ?: root.name,
            title = parsedBook.title,
            cover = parsedBook.coverHref,
            folder = root.name,
            lastAccess = bookRepository.currentAppleReferenceDateSeconds(),
        )
        bookRepository.saveMetadata(root, metadata)
    }

    private fun saveBookInfo(root: File, parsedBook: EpubBook) {
        bookRepository.saveBookInfo(root, parsedBook.bookInfo)
    }

    private fun readerBookId(root: File): String =
        bookRepository.loadMetadata(root)?.id ?: root.name
}
