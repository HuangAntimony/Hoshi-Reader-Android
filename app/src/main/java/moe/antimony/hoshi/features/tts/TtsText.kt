package moe.antimony.hoshi.features.tts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import moe.antimony.hoshi.epub.isReaderMatchableCodePoint
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

@Serializable
data class TtsWord(
    val id: String,
    val surface: String,
    @SerialName("reading") val replacement: String,
    val bookId: String? = null,
    val regex: Boolean = false,
)

enum class TtsWordError { Surface, Duplicate, RegexPattern, RegexReplacement, Storage }

fun validateTtsWord(word: TtsWord, words: List<TtsWord>): TtsWordError? = when {
    word.surface.isBlank() -> TtsWordError.Surface
    word.regex && TtsRegexRule.patternError(word) -> TtsWordError.RegexPattern
    word.regex && TtsRegexRule.compile(word) == null -> TtsWordError.RegexReplacement
    words.any { it.id != word.id && it.surface == word.surface && it.bookId == word.bookId && it.regex == word.regex } -> TtsWordError.Duplicate
    else -> null
}

/** A zero-width range inserts the reading of an image-backed glyph without changing source offsets. */
data class TtsRuby(val start: Int, val end: Int, val reading: String)
data class TtsSentence(
    val chapter: Int,
    val start: Int,
    val length: Int,
    val text: String,
    val ruby: List<TtsRuby> = emptyList(),
    val imageBefore: Boolean = false,
    val roles: List<TtsTextRange> = emptyList(),
)

/** Written offsets use the Reader's matchable code points; ruby offsets use UTF-16 within text. */
object TtsText {
    fun chapter(chapter: Int, html: String, mediaType: String = "text/html"): List<TtsSentence> {
        val result = mutableListOf<TtsSentence>()
        val source = StringBuilder()
        val sourceStarts = mutableListOf<Int>()
        val text = StringBuilder()
        val rubies = mutableListOf<TtsRuby>()
        var offset = 0
        var imageBefore = false
        var sentenceEnded = false
        fun flush() {
            val value = text.toString()
            val length = value.codePoints().filter { it.isReaderMatchableCodePoint() }.count().toInt()
            if (length > 0) {
                sourceStarts += source.length
                result += TtsSentence(chapter, offset, length, value, rubies.toList(), imageBefore)
                imageBefore = false
            }
            offset += length
            source.append(value).append('\n')
            text.clear()
            rubies.clear()
            sentenceEnded = false
        }
        fun append(value: String, reading: String? = null) {
            if (reading != null) {
                if (sentenceEnded) flush()
                rubies += TtsRuby(text.length, text.length + value.length, reading)
                text.append(value)
                return
            }
            value.forEach { c ->
                if (sentenceEnded && c !in "」』）】\"’”！？!?。") flush()
                if (c == '\n' || c == '\r') flush()
                else if (text.isNotEmpty() || !c.isWhitespace()) text.append(c)
                if (c in "。！？!?") sentenceEnded = true
            }
        }
        fun rubyBase(node: Node): String = when (node) {
            is TextNode -> node.wholeText
            is Element -> if (node.normalName() in setOf("rt", "rp", "script", "style")) "" else node.childNodes().joinToString("") { rubyBase(it) }
            else -> ""
        }
        fun visit(node: Node) {
            when (node) {
                is TextNode -> append(node.wholeText)
                is Element -> when (node.normalName()) {
                    "head", "rt", "rp", "script", "style" -> Unit
                    "img", "svg" -> { flush(); imageBefore = true }
                    "br" -> flush()
                    "ruby" -> {
                        val reading = node.select("rt").joinToString("") { it.wholeText() }
                        val base = rubyBase(node)
                        if (base.isNotEmpty() || node.selectFirst("img, svg") != null) {
                            append(base, reading.takeIf { it.isNotBlank() })
                        }
                    }
                    else -> {
                        node.childNodes().forEach(::visit)
                        if (node.normalName() in setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "section", "td", "tr")) flush()
                    }
                }
            }
        }
        // EPUB XHTML permits self-closing script/style tags; HTML parsing would swallow the body.
        val body = if (mediaType == "application/xhtml+xml") {
            val document = Jsoup.parse(html, "", Parser.xmlParser())
            document.selectFirst("body") ?: document
        } else Jsoup.parse(html).body()
        visit(body)
        flush()
        val roles = TtsDialogue.ranges(source.toString())
        return result.mapIndexed { index, sentence -> sentence.copy(roles = TtsDialogue.slice(roles, sourceStarts[index], sourceStarts[index] + sentence.text.length)) }
    }

    fun selection(sentences: List<TtsSentence>, text: String, normalizedOffset: Int?, sentenceOffset: Int?): TtsSentence {
        fun count(value: String) = value.codePoints().filter { it.isReaderMatchableCodePoint() }.count().toInt()
        val start = ((normalizedOffset ?: 0) - count(text.take(sentenceOffset ?: 0))).coerceAtLeast(0)
        val starts = mutableListOf<Int>()
        val ends = mutableListOf<Int>()
        var utf16 = 0
        text.codePoints().forEach { point ->
            if (point.isReaderMatchableCodePoint()) { starts += utf16; ends += utf16 + Character.charCount(point) }
            utf16 += Character.charCount(point)
        }
        val rubies = if (normalizedOffset == null) emptyList() else sentences.flatMap { source ->
            source.ruby.mapNotNull { ruby ->
                val from = source.start + count(source.text.take(ruby.start)) - start
                val until = source.start + count(source.text.take(ruby.end)) - start
                when {
                    from >= 0 && until > from && until <= starts.size -> TtsRuby(starts[from], ends[until - 1], ruby.reading)
                    ruby.start == ruby.end && from >= 0 && from <= starts.size &&
                        source.start >= start && source.start + source.length <= start + starts.size -> {
                        val position = starts.getOrNull(from) ?: text.length
                        TtsRuby(position, position, ruby.reading)
                    }
                    else -> null
                }
            }
        }
        val chapter = sentences.firstOrNull()?.chapter ?: 0
        if (normalizedOffset != null) TtsDialogue.selectionRanges(sentences, text, start, starts.size)?.let {
            return TtsSentence(chapter, start, starts.size, text, rubies, roles = it)
        }
        val roleByOffset = mutableMapOf<Int, TtsTextRole>()
        sentences.forEach { source ->
            val sourceRoles = source.roles.ifEmpty { TtsDialogue.ranges(source.text) }
            var position = source.start
            var sourceUtf16 = 0
            source.text.codePoints().forEach { point ->
                if (point.isReaderMatchableCodePoint()) {
                    roleByOffset[position++] = sourceRoles.firstOrNull { sourceUtf16 >= it.start && sourceUtf16 < it.end }?.role ?: TtsTextRole.Narration
                }
                sourceUtf16 += Character.charCount(point)
            }
        }
        val selectedRoles = mutableListOf<TtsTextRange>()
        var position = start
        var selectedUtf16 = 0
        text.codePoints().forEach { point ->
            val role = if (!point.isReaderMatchableCodePoint() && point != '「'.code && point != '『'.code)
                selectedRoles.lastOrNull()?.role ?: roleByOffset[position] ?: TtsTextRole.Narration
            else roleByOffset[position] ?: selectedRoles.lastOrNull()?.role ?: TtsTextRole.Narration
            val end = selectedUtf16 + Character.charCount(point)
            val previous = selectedRoles.lastOrNull()
            if (previous?.role == role) selectedRoles[selectedRoles.lastIndex] = previous.copy(end = end)
            else selectedRoles += TtsTextRange(selectedUtf16, end, role)
            if (point.isReaderMatchableCodePoint()) position++
            selectedUtf16 = end
        }
        return TtsSentence(chapter, start, starts.size, text, rubies, roles = if (normalizedOffset == null) TtsDialogue.ranges(text) else selectedRoles)
    }

    fun spoken(sentence: TtsSentence, bookId: String?, words: List<TtsWord>, useRubyReadings: Boolean = true): String {
        return spokenParts(sentence, bookId, words, useRubyReadings).joinToString("") { it.text }
    }

    fun spokenParts(sentence: TtsSentence, bookId: String?, words: List<TtsWord>, useRubyReadings: Boolean = true): List<TtsSpokenPart> {
        val applicable = words.filter { !it.regex && (it.bookId == null || it.bookId == bookId) }
            .sortedWith(compareByDescending<TtsWord> { it.surface.length }.thenByDescending { it.bookId != null })
        val rubies = if (useRubyReadings) sentence.ruby else emptyList()
        val regexMatches = ttsRegexMatches(sentence.text, words.filter { it.regex && (it.bookId == null || it.bookId == bookId) }, applicable, rubies)
        val roles = sentence.roles.ifEmpty { TtsDialogue.ranges(sentence.text) }
        val result = mutableListOf<Pair<TtsTextRole, StringBuilder>>()
        fun emit(value: String, start: Int, end: Int) {
            if (value.isEmpty()) return
            val position = (start until end).firstOrNull { sentence.text[it] !in "「」『』" } ?: start
            val role = TtsDialogue.roleAt(roles, if (start == end && position == sentence.text.length) (position - 1).coerceAtLeast(0) else position)
            val previous = result.lastOrNull()
            if (previous?.first == role) previous.second.append(value)
            else result += role to StringBuilder(value)
        }
        run {
            var cursor = 0
            while (cursor <= sentence.text.length) {
                // Image-backed glyphs have no matchable source text. Emit each insertion once,
                // then consume source text; never use an empty/backward range to advance cursor.
                rubies.filter { it.start == cursor && it.end == cursor }.forEach { emit(it.reading, cursor, cursor) }
                if (cursor == sentence.text.length) break
                val word = applicable.firstOrNull { it.surface.isNotEmpty() && sentence.text.startsWith(it.surface, cursor) }
                val ruby = rubies.firstOrNull { it.start == cursor && it.end > cursor && it.end <= sentence.text.length }
                when {
                    word != null -> { emit(word.replacement, cursor, cursor + word.surface.length); cursor += word.surface.length }
                    ruby != null && (cursor until ruby.end).none { start -> applicable.any { sentence.text.startsWith(it.surface, start) } } -> {
                        emit(ruby.reading, cursor, ruby.end); cursor = ruby.end
                    }
                    regexMatches[cursor] != null -> {
                        val match = regexMatches.getValue(cursor)
                        emit(match.text, cursor, match.end); cursor = match.end
                    }
                    else -> { emit(sentence.text[cursor].toString(), cursor, cursor + 1); cursor++ }
                }
            }
        }
        return result.map { TtsSpokenPart(it.first, it.second.toString()) }
    }

    fun chunks(text: String, limit: Int): List<String> {
        require(limit >= 2)
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + limit, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            result += text.substring(start, end)
            start = end
        }
        return result
    }
}
