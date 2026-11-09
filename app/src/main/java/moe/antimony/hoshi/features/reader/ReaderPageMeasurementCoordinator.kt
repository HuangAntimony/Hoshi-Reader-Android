package moe.antimony.hoshi.features.reader

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText

/** Called on Reader's UI scope. Cancellation retains only completed spines for this layout. */
internal class ReaderPageMeasurementCoordinator(private val repository: ReaderPageCountRepository) {
    private var partialKey: String? = null
    private val partial = mutableListOf<List<Int>>()
    private var generation = 0L

    suspend fun pagesFor(book: EpubBook, bookId: String, key: String, measure: suspend (Int) -> List<Int>): ReaderPageIndex {
        val request = ++generation
        fun checkGeneration() { if (request != generation) throw CancellationException("Page measurement superseded") }
        if (partialKey != key) { partial.clear(); partialKey = key }
        repository.load(bookId, key, book)?.let { coroutineContext.ensureActive(); checkGeneration(); return it }
        while (partial.size < book.chapters.size) {
            val spine = partial.size
            val starts = measure(spine)
            coroutineContext.ensureActive()
            checkGeneration()
            val count = book.bookInfo.chapterInfo.getValue(book.chapters[spine].href).chapterCount
            require(starts.isNotEmpty() && starts.all { it in 0..count } && starts.zipWithNext().all { (a, b) -> a <= b }) {
                "Invalid page offsets for spine $spine"
            }
            partial += starts
        }
        val index = requireNotNull(ReaderPageIndex.create(book, partial))
        try { repository.save(bookId, key, index) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { recordPageMeasurementFailure(error) }
        coroutineContext.ensureActive()
        checkGeneration()
        return index
    }
}

internal fun recordPageMeasurementFailure(error: Throwable) {
    ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.reader_page_measurement_failed), error)
}
