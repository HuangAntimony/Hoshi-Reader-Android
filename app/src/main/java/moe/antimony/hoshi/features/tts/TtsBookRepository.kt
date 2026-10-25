package moe.antimony.hoshi.features.tts

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.shouldHoldImage

internal data class TtsBookPosition(
    val sentences: List<TtsSentence>, val index: Int,
    val previousChapter: Int?, val nextChapter: Int?,
) {
    val sentence: TtsSentence get() = sentences[index]
}

/** Full chapters preserve quote/ruby context. Book instances are immutable reading snapshots. */
@Singleton
class TtsBookRepository @Inject constructor(@param:IoDispatcher private val dispatcher: CoroutineDispatcher) {
    private class Key(val book: EpubBook, val chapter: Int) {
        override fun equals(other: Any?) = other is Key && book === other.book && chapter == other.chapter
        override fun hashCode() = 31 * System.identityHashCode(book) + chapter
    }
    private val mutex = Mutex()
    private val chapters = LinkedHashMap<Key, TtsChapterText>(4, .75f, true)

    suspend fun chapter(book: EpubBook, index: Int): List<TtsSentence> = parsedChapter(book, index).sentences

    private suspend fun parsedChapter(book: EpubBook, index: Int): TtsChapterText = withContext(dispatcher) {
        mutex.withLock {
            val key = Key(book, index)
            chapters[key]?.let { return@withLock it }
            val source = book.chapters[index]
            val html = source.html.ifBlank { book.readResource(source.href)?.toString(Charsets.UTF_8).orEmpty() }
            val sentences = TtsText.extractChapter(index, html, source.mediaType) { book.shouldHoldImage(source.href, it) }
            currentCoroutineContext().ensureActive()
            chapters[key] = sentences
            if (chapters.size > 4) chapters.remove(chapters.keys.first())
            sentences
        }
    }

    /** Includes trailing and image-only intermediate chapters that the Reader traverses. */
    internal suspend fun hasImagesBetween(book: EpubBook, from: TtsBookPosition, to: TtsBookPosition): Boolean {
        if (to.sentence.imageBefore) return true
        if (from.sentence.chapter == to.sentence.chapter) return false
        if (parsedChapter(book, from.sentence.chapter).trailingImage) return true
        for (index in (from.sentence.chapter + 1) until to.sentence.chapter) {
            val chapter = parsedChapter(book, index)
            if (chapter.trailingImage || chapter.sentences.any { it.imageBefore }) return true
        }
        return false
    }

    private fun readable(book: EpubBook) = book.chapters.indices.filter { index ->
        val chapter = book.chapters[index]
        chapter.linear && !chapter.isGuideToc && chapter.properties?.split(" ")?.contains("nav") != true
    }

    private suspend fun neighbor(book: EpubBook, index: Int, direction: Int): Int? {
        val candidates = readable(book).filter { if (direction > 0) it > index else it < index }
        for (candidate in if (direction > 0) candidates else candidates.asReversed()) {
            currentCoroutineContext().ensureActive()
            if (chapter(book, candidate).isNotEmpty()) return candidate
        }
        return null
    }

    private suspend fun position(book: EpubBook, sentences: List<TtsSentence>, index: Int) = TtsBookPosition(
        sentences, index, neighbor(book, sentences[index].chapter, -1), neighbor(book, sentences[index].chapter, 1))

    internal suspend fun at(book: EpubBook, chapter: Int, offset: Int): TtsBookPosition? {
        for (candidate in readable(book).filter { it >= chapter }) {
            val sentences = chapter(book, candidate)
            val index = if (candidate == chapter) sentences.indexOfFirst { it.start + it.length > offset } else 0
            if (index in sentences.indices) return position(book, sentences, index)
        }
        // Starting beyond the book's text retains the previous last-sentence behavior.
        for (candidate in readable(book).asReversed()) {
            val sentences = chapter(book, candidate)
            if (sentences.isNotEmpty()) return position(book, sentences, sentences.lastIndex)
        }
        return null
    }

    internal suspend fun adjacent(book: EpubBook, current: TtsBookPosition, direction: Int): TtsBookPosition? {
        val index = current.index + direction
        if (index in current.sentences.indices) return current.copy(index = index)
        val next = (if (direction > 0) current.nextChapter else current.previousChapter) ?: return null
        val sentences = chapter(book, next)
        return position(book, sentences, if (direction > 0) 0 else sentences.lastIndex)
    }

    suspend fun selection(book: EpubBook, index: Int, text: String, normalizedOffset: Int?, sentenceOffset: Int?): TtsSentence =
        withContext(dispatcher) { TtsText.selection(chapter(book, index), text, normalizedOffset, sentenceOffset) }
}
