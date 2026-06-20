package moe.antimony.hoshi.features.reader

import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.filteredReaderText

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
        while (fromIndex <= document.text.length && results.size < maxResults) {
            val matchIndex = document.text.indexOf(normalizedQuery, startIndex = fromIndex, ignoreCase = true)
            if (matchIndex < 0) break

            val character = document.text.codePointCount(0, matchIndex)
            val chapter = document.chapterForCharacter(character)
            val matchEndCharacter = character + queryCodePoints
            if (chapter != null && matchEndCharacter <= chapter.endCharacter) {
                results += document.resultFor(
                    chapter = chapter,
                    character = character,
                    queryCodePoints = queryCodePoints,
                )
            }

            val matchEndIndex = document.text.offsetByCodePointsSafe(matchIndex, queryCodePoints)
            fromIndex = if (chapter != null && matchEndCharacter <= chapter.endCharacter && matchEndIndex > matchIndex) {
                matchEndIndex
            } else {
                document.text.offsetByCodePointsSafe(matchIndex, 1).takeIf { it > matchIndex } ?: (matchIndex + 1)
            }
        }
        return results
    }

    private companion object {
        const val DefaultMaxResults = 1_000
    }
}

private data class ReaderSearchDocument(
    val text: String,
    val chapters: List<ReaderSearchChapterRange>,
) {
    fun chapterForCharacter(character: Int): ReaderSearchChapterRange? =
        chapters.firstOrNull { character >= it.startCharacter && character < it.endCharacter }
            ?: chapters.lastOrNull {
                character == it.endCharacter && it.endCharacter == text.codePointCount(0, text.length)
            }

    fun resultFor(
        chapter: ReaderSearchChapterRange,
        character: Int,
        queryCodePoints: Int,
    ): ReaderSearchResult {
        val snippetStart = max(chapter.startCharacter, character - SnippetLeadingCodePoints)
        val snippetEnd = min(chapter.endCharacter, character + queryCodePoints + SnippetTrailingCodePoints)
        val hasPrefix = snippetStart > chapter.startCharacter
        val hasSuffix = snippetEnd < chapter.endCharacter
        val startIndex = text.codePointIndex(snippetStart)
        val endIndex = text.codePointIndex(snippetEnd)
        val prefix = if (hasPrefix) "..." else ""
        val suffix = if (hasSuffix) "..." else ""
        val body = text.substring(startIndex, endIndex)
        val snippet = prefix + body + suffix
        val matchStart = (if (hasPrefix) prefix.codePointCount(0, prefix.length) else 0) + character - snippetStart
        return ReaderSearchResult(
            chapterIndex = chapter.index,
            chapterLabel = chapter.label,
            character = character,
            snippet = snippet,
            snippetMatchStart = matchStart,
            snippetMatchEnd = matchStart + queryCodePoints,
        )
    }

    companion object {
        fun from(book: EpubBook): ReaderSearchDocument {
            val text = StringBuilder()
            val chapters = mutableListOf<ReaderSearchChapterRange>()
            book.chapters.forEachIndexed { fallbackIndex, chapter ->
                val index = chapter.spineIndex ?: fallbackIndex
                val start = text.codePointCount(0, text.length)
                val html = book.readResource(chapter.href)?.toString(Charsets.UTF_8) ?: chapter.html
                text.append(html.filteredReaderText())
                val end = text.codePointCount(0, text.length)
                chapters += ReaderSearchChapterRange(
                    index = index,
                    startCharacter = start,
                    endCharacter = end,
                    label = ReaderChapterLabels.sectionLabelForIndex(book, index),
                )
            }
            return ReaderSearchDocument(text = text.toString(), chapters = chapters)
        }

        private const val SnippetLeadingCodePoints = 24
        private const val SnippetTrailingCodePoints = 48
    }
}

private data class ReaderSearchChapterRange(
    val index: Int,
    val startCharacter: Int,
    val endCharacter: Int,
    val label: String,
)

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
