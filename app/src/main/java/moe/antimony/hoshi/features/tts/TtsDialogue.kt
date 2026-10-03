package moe.antimony.hoshi.features.tts

import moe.antimony.hoshi.epub.isReaderMatchableCodePoint

enum class TtsTextRole { Narration, Dialogue }
data class TtsTextRange(val start: Int, val end: Int, val role: TtsTextRole)
data class TtsSpokenPart(val role: TtsTextRole, val text: String)

/** Complete paired ranges are found before sentences are sliced, so unmatched openers cannot leak. */
internal object TtsDialogue {
    fun ranges(text: String): List<TtsTextRange> {
        val stack = ArrayDeque<Pair<Char, Int>>()
        val matched = mutableListOf<IntRange>()
        text.forEachIndexed { index, character -> when (character) {
            '「', '『' -> stack.addLast(character to index)
            '」', '』' -> {
                val opener = if (character == '」') '「' else '『'
                val found = stack.indexOfLast { it.first == opener }
                if (found >= 0) {
                    while (stack.lastIndex > found) stack.removeLast()
                    matched += stack.removeLast().second..index
                }
            }
        } }
        val merged = mutableListOf<IntRange>()
        matched.sortedBy { it.first }.forEach { range ->
            val previous = merged.lastOrNull()
            if (previous != null && range.first <= previous.last + 1) merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
            else merged += range
        }
        return buildList {
            var cursor = 0
            merged.forEach { range ->
                if (cursor < range.first) add(TtsTextRange(cursor, range.first, TtsTextRole.Narration))
                add(TtsTextRange(range.first, range.last + 1, TtsTextRole.Dialogue))
                cursor = range.last + 1
            }
            if (cursor < text.length) add(TtsTextRange(cursor, text.length, TtsTextRole.Narration))
        }
    }

    fun roleAt(ranges: List<TtsTextRange>, position: Int): TtsTextRole {
        val found = ranges.binarySearchBy(position) { it.start }
        val index = if (found >= 0) found else -found - 2
        return ranges.getOrNull(index)?.takeIf { position < it.end }?.role ?: TtsTextRole.Narration
    }

    fun slice(ranges: List<TtsTextRange>, start: Int, end: Int): List<TtsTextRange> {
        val found = ranges.binarySearchBy(start) { it.start }
        val first = if (found >= 0) found else (-found - 2).coerceAtLeast(0)
        return buildList {
            for (index in first until ranges.size) {
                val range = ranges[index]
                if (range.start >= end) break
                val from = maxOf(start, range.start)
                val until = minOf(end, range.end)
                if (from < until) add(TtsTextRange(from - start, until - start, range.role))
            }
        }
    }

    /** Keep punctuation/symbol roles by matching source text at its normalized anchor, not by letters alone. */
    fun selectionRanges(sentences: List<TtsSentence>, text: String, start: Int, length: Int): List<TtsTextRange>? {
        fun whitespace(point: Int) = Character.isWhitespace(point) || Character.isSpaceChar(point)
        val characters = StringBuilder()
        val offsets = mutableListOf<Int>()
        val roles = mutableListOf<TtsTextRole>()
        sentences.filter { it.start <= start + length && it.start + it.length >= start }.forEach { sentence ->
            val ranges = sentence.roles.ifEmpty { ranges(sentence.text) }
            var offset = sentence.start
            var cursor = 0
            sentence.text.codePoints().forEach { point ->
                if (!whitespace(point)) {
                    val role = roleAt(ranges, cursor)
                    val chars = Character.toChars(point)
                    characters.append(chars)
                    repeat(chars.size) { offsets += offset; roles += role }
                }
                if (point.isReaderMatchableCodePoint()) offset++
                cursor += Character.charCount(point)
            }
        }
        val selected = buildString { text.codePoints().forEach { if (!whitespace(it)) appendCodePoint(it) } }
        if (selected.isEmpty()) return null
        var found = characters.indexOf(selected)
        while (found >= 0 && offsets.getOrNull(found) != start) found = characters.indexOf(selected, found + 1)
        if (found < 0) return null
        val result = mutableListOf<TtsTextRange>()
        var sourceIndex = found
        var cursor = 0
        text.codePoints().forEach { point ->
            val role = roles.getOrNull(sourceIndex) ?: result.lastOrNull()?.role ?: TtsTextRole.Narration
            val end = cursor + Character.charCount(point)
            val previous = result.lastOrNull()
            if (previous?.role == role) result[result.lastIndex] = previous.copy(end = end)
            else result += TtsTextRange(cursor, end, role)
            if (!whitespace(point)) sourceIndex += Character.charCount(point)
            cursor = end
        }
        return result
    }
}
