package moe.antimony.hoshi.features.reader

import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.filteredReaderText
import moe.antimony.hoshi.epub.isReaderMatchableCodePoint
import moe.antimony.hoshi.epub.visibleReaderText

internal data class ReaderSearchResult(
    val chapterIndex: Int,
    val chapterLabel: String,
    val character: Int,
    val snippet: String,
    val snippetMatchStart: Int,
    val snippetMatchEnd: Int,
)

internal class ReaderSearchEngine(
    private val book: EpubBook,
) {
    private val document: ReaderSearchDocument by lazy { ReaderSearchDocument.from(book) }

    fun search(query: String, maxResults: Int = DefaultMaxResults): List<ReaderSearchResult> {
        val normalizedQuery = query.filteredReaderText()
        if (normalizedQuery.isBlank()) return emptyList()

        val results = mutableListOf<ReaderSearchResult>()
        val queryCodePoints = normalizedQuery.codePointCount(0, normalizedQuery.length)
        var fromIndex = 0
        while (fromIndex <= document.searchText.length && results.size < maxResults) {
            val matchIndex = document.searchText.indexOf(normalizedQuery, startIndex = fromIndex, ignoreCase = true)
            if (matchIndex < 0) break

            val character = document.searchText.codePointCount(0, matchIndex)
            val chapter = document.chapterForCharacter(character)
            val matchEndCharacter = character + queryCodePoints
            if (chapter != null && matchEndCharacter <= chapter.endSearchCharacter) {
                results += document.resultFor(
                    chapter = chapter,
                    character = character,
                    queryCodePoints = queryCodePoints,
                )
            }

            val matchEndIndex = document.searchText.offsetByCodePointsSafe(matchIndex, queryCodePoints)
            fromIndex = if (chapter != null && matchEndCharacter <= chapter.endSearchCharacter && matchEndIndex > matchIndex) {
                matchEndIndex
            } else {
                document.searchText.offsetByCodePointsSafe(matchIndex, 1).takeIf { it > matchIndex } ?: (matchIndex + 1)
            }
        }
        return results
    }

    private companion object {
        const val DefaultMaxResults = 1_000
    }
}

private data class ReaderSearchDocument(
    val searchText: String,
    val displayText: String,
    val searchToDisplayCodePointOffsets: IntArray,
    val chapters: List<ReaderSearchChapterRange>,
) {
    fun chapterForCharacter(character: Int): ReaderSearchChapterRange? =
        chapters.firstOrNull { character >= it.startSearchCharacter && character < it.endSearchCharacter }
            ?: chapters.lastOrNull {
                character == it.endSearchCharacter && it.endSearchCharacter == searchToDisplayCodePointOffsets.size
            }

    fun resultFor(
        chapter: ReaderSearchChapterRange,
        character: Int,
        queryCodePoints: Int,
    ): ReaderSearchResult {
        val displayMatchStart = searchToDisplayCodePointOffsets[character]
        val displayMatchEnd = searchToDisplayCodePointOffsets[character + queryCodePoints - 1] + 1
        val snippetStart = max(chapter.startDisplayCharacter, displayMatchStart - SnippetLeadingCodePoints)
        val snippetEnd = min(chapter.endDisplayCharacter, displayMatchEnd + SnippetTrailingCodePoints)
        val hasPrefix = snippetStart > chapter.startDisplayCharacter
        val hasSuffix = snippetEnd < chapter.endDisplayCharacter
        val startIndex = displayText.codePointIndex(snippetStart)
        val endIndex = displayText.codePointIndex(snippetEnd)
        val prefix = if (hasPrefix) "..." else ""
        val suffix = if (hasSuffix) "..." else ""
        val body = displayText.substring(startIndex, endIndex)
        val snippet = prefix + body + suffix
        val matchStart = (if (hasPrefix) prefix.codePointCount(0, prefix.length) else 0) + displayMatchStart - snippetStart
        return ReaderSearchResult(
            chapterIndex = chapter.index,
            chapterLabel = chapter.label,
            character = character,
            snippet = snippet,
            snippetMatchStart = matchStart,
            snippetMatchEnd = matchStart + displayMatchEnd - displayMatchStart,
        )
    }

    companion object {
        fun from(book: EpubBook): ReaderSearchDocument {
            val builder = ReaderSearchDocumentBuilder()
            val chapters = mutableListOf<ReaderSearchChapterRange>()
            book.chapters.forEachIndexed { fallbackIndex, chapter ->
                val index = chapter.spineIndex ?: fallbackIndex
                val html = book.readResource(chapter.href)?.toString(Charsets.UTF_8) ?: chapter.html
                val bounds = builder.appendChapter(html)
                chapters += ReaderSearchChapterRange(
                    index = index,
                    startSearchCharacter = bounds.startSearchCharacter,
                    endSearchCharacter = bounds.endSearchCharacter,
                    startDisplayCharacter = bounds.startDisplayCharacter,
                    endDisplayCharacter = bounds.endDisplayCharacter,
                    label = ReaderChapterLabels.sectionLabelForIndex(book, index),
                )
            }
            return ReaderSearchDocument(
                searchText = builder.searchText.toString(),
                displayText = builder.displayText.toString(),
                searchToDisplayCodePointOffsets = builder.searchToDisplayCodePointOffsets.toIntArray(),
                chapters = chapters,
            )
        }

        private const val SnippetLeadingCodePoints = 24
        private const val SnippetTrailingCodePoints = 48
    }
}

private data class ReaderSearchChapterRange(
    val index: Int,
    val startSearchCharacter: Int,
    val endSearchCharacter: Int,
    val startDisplayCharacter: Int,
    val endDisplayCharacter: Int,
    val label: String,
)

private data class ReaderSearchChapterBounds(
    val startSearchCharacter: Int,
    val endSearchCharacter: Int,
    val startDisplayCharacter: Int,
    val endDisplayCharacter: Int,
)

private class ReaderSearchDocumentBuilder {
    val searchText = StringBuilder()
    val displayText = StringBuilder()
    val searchToDisplayCodePointOffsets = IntArrayBuilder()
    private var searchCodePointCount = 0
    private var displayCodePointCount = 0

    fun appendChapter(html: String): ReaderSearchChapterBounds {
        val startSearchCharacter = searchCodePointCount
        val startDisplayCharacter = displayCodePointCount
        var hasDisplayContent = false
        var pendingWhitespace = false
        html.visibleReaderText().codePoints().forEach { codePoint ->
            if (Character.isWhitespace(codePoint)) {
                if (hasDisplayContent) {
                    pendingWhitespace = true
                }
                return@forEach
            }

            if (pendingWhitespace) {
                appendDisplayCodePoint(' '.code)
                pendingWhitespace = false
            }

            hasDisplayContent = true
            val displayOffset = displayCodePointCount
            appendDisplayCodePoint(codePoint)
            if (codePoint.isReaderMatchableCodePoint()) {
                searchText.appendCodePoint(codePoint)
                searchToDisplayCodePointOffsets.add(displayOffset)
                searchCodePointCount += 1
            }
        }
        return ReaderSearchChapterBounds(
            startSearchCharacter = startSearchCharacter,
            endSearchCharacter = searchCodePointCount,
            startDisplayCharacter = startDisplayCharacter,
            endDisplayCharacter = displayCodePointCount,
        )
    }

    private fun appendDisplayCodePoint(codePoint: Int) {
        displayText.appendCodePoint(codePoint)
        displayCodePointCount += 1
    }
}

private class IntArrayBuilder(initialCapacity: Int = 256) {
    private var values = IntArray(initialCapacity)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == values.size) {
            values = values.copyOf(max(values.size * 2, 1))
        }
        values[size] = value
        size += 1
    }

    fun toIntArray(): IntArray = values.copyOf(size)
}

internal fun readerSearchQueryHasMatchableText(query: String): Boolean =
    query.filteredReaderText().isNotBlank()

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

private fun String.codePointIndex(codePointOffset: Int): Int =
    offsetByCodePointsSafe(0, codePointOffset)

private fun String.offsetByCodePointsSafe(startIndex: Int, codePointCount: Int): Int {
    val safeStart = startIndex.coerceIn(0, length)
    val available = codePointCount(safeStart, length)
    return offsetByCodePoints(safeStart, codePointCount.coerceIn(0, available))
}
