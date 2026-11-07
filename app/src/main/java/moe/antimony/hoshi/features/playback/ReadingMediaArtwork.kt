package moe.antimony.hoshi.features.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.antimony.hoshi.features.bookshelf.BookCoverSource

/** Main-thread, playback-owned artwork. A new book invalidates even an identical cover request. */
internal class ReadingMediaArtwork(
    private val scope: CoroutineScope,
    private val load: suspend (BookCoverSource) -> ByteArray?,
    private val onChanged: () -> Unit,
) {
    var data: ByteArray? = null
        private set
    private var key: Pair<String?, BookCoverSource?>? = null
    private var generation = 0L
    private var job: Job? = null

    fun dataFor(bookId: String?, filePath: String?): ByteArray? =
        data.takeIf { key?.first == bookId && key?.second?.path == filePath }

    fun update(bookId: String?, source: BookCoverSource?) {
        val next = bookId to source
        if (key == next) return
        clear()
        key = next
        if (bookId == null || source == null) return
        val request = generation
        job = scope.launch {
            val loaded = load(source)
            if (generation != request) return@launch
            data = loaded
            onChanged()
        }
    }

    fun clear() {
        generation++
        job?.cancel()
        job = null
        key = null
        if (data != null) {
            data = null
            onChanged()
        }
    }
}
