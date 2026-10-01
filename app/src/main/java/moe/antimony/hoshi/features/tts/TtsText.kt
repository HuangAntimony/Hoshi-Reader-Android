package moe.antimony.hoshi.features.tts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import moe.antimony.hoshi.epub.isReaderMatchableCodePoint
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

@Serializable
data class TtsWord(
    val id: String,
    val surface: String,
    @SerialName("reading") val replacement: String,
    val bookId: String? = null,
)

enum class TtsWordError { Surface, Replacement, Duplicate, Storage }

fun validateTtsWord(word: TtsWord, words: List<TtsWord>): TtsWordError? = when {
    word.surface.isBlank() -> TtsWordError.Surface
    word.replacement.isBlank() -> TtsWordError.Replacement
    words.any { it.id != word.id && it.surface == word.surface && it.bookId == word.bookId } -> TtsWordError.Duplicate
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
)

/** Written offsets use the Reader's matchable code points; ruby offsets use UTF-16 within text. */
object TtsText {
    fun chapter(chapter: Int, html: String): List<TtsSentence> {
        val result = mutableListOf<TtsSentence>()
        val text = StringBuilder()
        val rubies = mutableListOf<TtsRuby>()
        var offset = 0
        var imageBefore = false
        var sentenceEnded = false
        fun flush() {
            val value = text.toString()
            val length = value.codePoints().filter { it.isReaderMatchableCodePoint() }.count().toInt()
            if (length > 0) {
                result += TtsSentence(chapter, offset, length, value, rubies.toList(), imageBefore)
                imageBefore = false
            }
            offset += length
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
                    "rt", "rp", "script", "style" -> Unit
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
        visit(Jsoup.parse(html).body())
        flush()
        return result
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
        return TtsSentence(chapter, start, starts.size, text, rubies)
    }

    fun spoken(sentence: TtsSentence, bookId: String?, words: List<TtsWord>, useRubyReadings: Boolean = true): String {
        val applicable = words.filter { it.bookId == null || it.bookId == bookId }
            .sortedWith(compareByDescending<TtsWord> { it.surface.length }.thenByDescending { it.bookId != null })
        val rubies = if (useRubyReadings) sentence.ruby else emptyList()
        return buildString {
            var cursor = 0
            while (cursor <= sentence.text.length) {
                // Image-backed glyphs have no matchable source text. Emit each insertion once,
                // then consume source text; never use an empty/backward range to advance cursor.
                rubies.filter { it.start == cursor && it.end == cursor }.forEach { append(it.reading) }
                if (cursor == sentence.text.length) break
                val word = applicable.firstOrNull { it.surface.isNotEmpty() && sentence.text.startsWith(it.surface, cursor) }
                val ruby = rubies.firstOrNull { it.start == cursor && it.end > cursor && it.end <= sentence.text.length }
                when {
                    word != null -> { append(word.replacement); cursor += word.surface.length }
                    ruby != null && (cursor until ruby.end).none { start -> applicable.any { sentence.text.startsWith(it.surface, start) } } -> {
                        append(ruby.reading); cursor = ruby.end
                    }
                    else -> append(sentence.text[cursor++])
                }
            }
        }
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
