package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.isReaderMatchableCodePoint
import moe.antimony.hoshi.epub.visibleReaderText

internal data class ReaderSearchResult(
    val chapterIndex: Int,
    val chapterLabel: String,
    val character: Int,
    val snippet: String,
    val snippetMatchStart: Int,
    val snippetMatchEnd: Int,
    val matchLength: Int,
)

internal class ReaderSearchEngine(private val book: EpubBook) {
    private val paragraphs by lazy { buildParagraphs() }

    fun search(query: String, maxResults: Int = 100): List<ReaderSearchResult> {
        val literalQuery = query.trim()
        if (literalQuery.isEmpty() || maxResults <= 0) return emptyList()
        val results = mutableListOf<ReaderSearchResult>()
        for (paragraph in paragraphs) {
            val text = paragraph.text
            var cursor = 0
            while (cursor < text.length) {
                val start = text.indexOf(literalQuery, cursor, ignoreCase = true)
                if (start < 0) break
                val end = start + literalQuery.length
                val bounds = sentenceBounds(text, start, end)
                results += ReaderSearchResult(
                    chapterIndex = paragraph.chapterIndex,
                    chapterLabel = paragraph.chapterLabel,
                    character = paragraph.character + paragraph.normalizedOffsets[start],
                    snippet = text.substring(bounds.first, bounds.second),
                    snippetMatchStart = text.codePointCount(bounds.first, start),
                    snippetMatchEnd = text.codePointCount(bounds.first, end),
                    matchLength = paragraph.normalizedOffsets[end] - paragraph.normalizedOffsets[start],
                )
                if (results.size >= maxResults.coerceAtMost(100)) return results
                cursor = end
            }
        }
        return results
    }

    private fun buildParagraphs(): List<SearchParagraph> = buildList {
        val labels = ReaderChapterLabels.labels(book)
        var nextChapterStart = 0
        book.chapters.forEachIndexed { fallbackIndex, chapter ->
            val index = chapter.spineIndex ?: fallbackIndex
            var character = book.bookInfo.chapterInfo[chapter.href]?.currentTotal ?: nextChapterStart
            val html = book.readResource(chapter.href)?.toString(Charsets.UTF_8) ?: chapter.html
            for (line in html.visibleReaderText(preserveParagraphs = true).split('\n', '\r')) {
                val text = line.trim()
                val offsets = IntArray(text.length + 1)
                var normalized = 0
                var utf16 = 0
                while (utf16 < text.length) {
                    val codePoint = text.codePointAt(utf16)
                    val width = Character.charCount(codePoint)
                    repeat(width) { offsets[utf16 + it] = normalized }
                    if (codePoint.isReaderMatchableCodePoint()) normalized++
                    utf16 += width
                }
                offsets[text.length] = normalized
                if (text.isNotEmpty()) {
                    add(SearchParagraph(
                        chapterIndex = index,
                        chapterLabel = ReaderChapterLabels.sectionLabelForIndex(labels, index),
                        character = character,
                        text = text,
                        normalizedOffsets = offsets,
                    ))
                }
                character += normalized
            }
            nextChapterStart = character
        }
    }
}

private data class SearchParagraph(
    val chapterIndex: Int,
    val chapterLabel: String,
    val character: Int,
    val text: String,
    val normalizedOffsets: IntArray,
)

private val sentenceDelimiters = "。！？.!?\n\r".toSet()
private val trailingSentenceChars = "。、！？」』）)】〉》〕｝}］]".toSet()
private val sentenceBrackets = mapOf(
    '「' to '」', '『' to '』', '（' to '）', '(' to ')',
    '【' to '】', '〈' to '〉', '《' to '》', '〔' to '〕', '｛' to '｝', '{' to '}', '［' to '］', '[' to ']',
)

private fun sentenceBounds(text: String, matchStart: Int, matchEnd: Int): Pair<Int, Int> {
    var start = matchStart
    while (start > 0 && text[start - 1] !in sentenceDelimiters) start--
    var end = matchEnd
    while (end < text.length && text[end] !in sentenceDelimiters) end++
    if (end < text.length) {
        end++
        while (end < text.length && text[end] in trailingSentenceChars) end++
    }
    fun trimBounds() {
        while (start < matchStart && text[start].isWhitespace()) start++
        while (end > matchEnd && text[end - 1].isWhitespace()) end--
    }
    trimBounds()
    val stack = mutableListOf<Char>()
    val unmatched = mutableListOf<Char>()
    for (index in start until end) {
        val char = text[index]
        if (char in sentenceBrackets) stack.add(char)
        else if (char in sentenceBrackets.values) {
            if (stack.lastOrNull()?.let { sentenceBrackets[it] } == char) stack.removeAt(stack.lastIndex)
            else unmatched.add(char)
        }
    }
    while (stack.isNotEmpty() && start < matchStart && text[start] == stack.first()) {
        stack.removeAt(0)
        start++
    }
    var cursor = end
    while (unmatched.isNotEmpty() && cursor > matchEnd) {
        val previous = cursor - 1
        if (text[previous] == unmatched.last()) {
            unmatched.removeAt(unmatched.lastIndex)
            end = previous
        } else if (text[previous] !in sentenceDelimiters) break
        cursor = previous
    }
    trimBounds()
    return start to end
}

internal fun readerSearchQueryHasMatchableText(query: String): Boolean = query.isNotBlank()

internal sealed interface ReaderSearchLoadResult<out T> {
    data class Success<T>(val value: T) : ReaderSearchLoadResult<T>
    data object Failure : ReaderSearchLoadResult<Nothing>
}

internal suspend fun <T> loadReaderSearchResults(
    search: suspend () -> T,
): ReaderSearchLoadResult<T> =
    try {
        ReaderSearchLoadResult.Success(search())
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        ReaderSearchLoadResult.Failure
    }
