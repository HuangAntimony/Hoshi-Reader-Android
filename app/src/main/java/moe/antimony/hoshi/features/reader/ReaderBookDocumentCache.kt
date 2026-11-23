package moe.antimony.hoshi.features.reader

import coil3.disk.DiskCache
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import moe.antimony.hoshi.R
import moe.antimony.hoshi.di.CacheDir
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import okio.Path.Companion.toOkioPath

/** Accessed on the WebView resource thread. HTML and its scoped stylesheet share one atomic entry. */
@Singleton
internal class ReaderBookDocumentCache @Inject constructor(@CacheDir cacheDir: File) {
    private val disk by lazy {
        DiskCache.Builder()
            .directory(cacheDir.resolve("reader-documents").toOkioPath())
            .maxSizeBytes(32L * 1024 * 1024)
            .build()
    }

    fun document(book: EpubBook, build: () -> ReaderBookDocument = { book.buildReaderBookDocument() }): ReaderBookDocument {
        val extraction = book.packedExtractionKey ?: return build()
        // Bump when the generated markup, stylesheet, or publisher sanitization changes.
        val key = "2\u0000$extraction\u0000${book.title}"
        try {
            disk.openSnapshot(key)?.use { entry ->
                return ReaderBookDocument(entry.data.toFile().readBytes(), entry.metadata.toFile().readBytes())
            }
        } catch (error: IOException) {
            ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.reader_open_failed), error)
        }
        val document = build()
        try {
            disk.openEditor(key)?.let { editor ->
                try {
                    editor.data.toFile().writeBytes(document.html)
                    editor.metadata.toFile().writeBytes(document.css)
                    editor.commit()
                } catch (error: IOException) {
                    editor.abort()
                    throw error
                }
            }
        } catch (error: IOException) {
            ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.reader_open_failed), error)
        }
        return document
    }
}
