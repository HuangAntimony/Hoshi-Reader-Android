package moe.antimony.hoshi.features.tts

import com.google.re2j.Matcher
import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.withErrorDetails

/** Immutable compiled rules; matchers are local to a sentence, never shared between threads. */
internal class TtsRegexRule private constructor(val pattern: Pattern, private val template: List<Token>) {
    private data class Token(val text: String = "", val group: Int? = null)
    fun replacement(match: Matcher): String = buildString {
        template.forEach { append(it.group?.let { group -> match.group(group).orEmpty() } ?: it.text) }
    }

    companion object {
        private val cache = linkedMapOf<Pair<String, String>, Result<TtsRegexRule>>()
        fun patternError(word: TtsWord): Boolean = failure(word) is PatternSyntaxException
        fun compile(word: TtsWord): TtsRegexRule? = compilation(word).getOrNull()
        fun failure(word: TtsWord): Throwable? = compilation(word).exceptionOrNull()

        @Synchronized private fun compilation(word: TtsWord): Result<TtsRegexRule> {
            val key = word.surface to word.replacement
            cache[key]?.let { return it }
            val compiled = try {
                val pattern = Pattern.compile(word.surface)
                val groups = pattern.groupCount()
                val tokens = mutableListOf<Token>()
                val literal = StringBuilder()
                var cursor = 0
                while (cursor < word.replacement.length) {
                    when (val char = word.replacement[cursor++]) {
                        '\\' -> {
                            require(cursor < word.replacement.length)
                            literal.append(word.replacement[cursor++])
                        }
                        '$' -> {
                            if (literal.isNotEmpty()) { tokens += Token(literal.toString()); literal.clear() }
                            val start = cursor
                            while (cursor < word.replacement.length && word.replacement[cursor] in '0'..'9') cursor++
                            val group = word.replacement.substring(start, cursor).toIntOrNull()
                            require(group != null && group in 0..groups)
                            tokens += Token(group = group)
                        }
                        else -> literal.append(char)
                    }
                }
                if (literal.isNotEmpty()) tokens += Token(literal.toString())
                Result.success(TtsRegexRule(pattern, tokens))
            } catch (error: PatternSyntaxException) { Result.failure(error)
            } catch (error: IllegalArgumentException) { Result.failure(error) }
            cache[key] = compiled
            if (cache.size > 64) cache.remove(cache.keys.first())
            return compiled
        }
    }
}

internal data class TtsReplacementMatch(val end: Int, val text: String)

/** Match original text once. Literal entries and usable ruby reserve their original ranges first. */
internal fun ttsRegexMatches(text: String, rules: List<TtsWord>, literals: List<TtsWord>, rubies: List<TtsRuby>): Map<Int, TtsReplacementMatch> {
    if (rules.isEmpty()) return emptyMap()
    val literalAt = arrayOfNulls<TtsWord>(text.length)
    literals.filter { it.surface.isNotEmpty() }.forEach { word ->
        var start = text.indexOf(word.surface)
        while (start >= 0) {
            if (literalAt[start] == null) literalAt[start] = word
            start = text.indexOf(word.surface, start + 1)
        }
    }
    val protected = BooleanArray(text.length)
    val insertions = BooleanArray(text.length + 1)
    var cursor = 0
    // Replay the existing literal/ruby consumption. Overlapped, skipped entries reserve nothing.
    while (cursor <= text.length) {
        insertions[cursor] = rubies.any { it.start == cursor && it.end == cursor }
        if (cursor == text.length) break
        val word = literalAt[cursor]
        val ruby = rubies.firstOrNull { it.start == cursor && it.end > cursor && it.end <= text.length }
        val end = when {
            word != null -> cursor + word.surface.length
            ruby != null && (cursor until ruby.end).none { literalAt[it] != null } -> ruby.end
            else -> cursor
        }
        for (index in cursor until end) protected[index] = true
        cursor = if (end > cursor) end else cursor + 1
    }
    // Prefix counts make overlap checks constant-time. A glyph insertion is a boundary,
    // not a protected character: matches may start/end there but cannot swallow it.
    val blocked = IntArray(text.length + 1)
    text.indices.forEach { blocked[it + 1] = blocked[it] + if (protected[it]) 1 else 0 }
    val inserted = IntArray(text.length + 2)
    insertions.indices.forEach { inserted[it + 1] = inserted[it] + if (insertions[it]) 1 else 0 }
    val matches = mutableMapOf<Int, TtsReplacementMatch>()
    rules.sortedByDescending { it.bookId != null }.forEach { word ->
        val rule = TtsRegexRule.compile(word) ?: return@forEach
        val matcher = rule.pattern.matcher(text)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            // Ignore insertion-only matches; they must neither loop nor steal a spoken range.
            if (start == end || blocked[end] != blocked[start] || inserted[end] != inserted[start + 1]) continue
            if (end > (matches[start]?.end ?: -1)) matches[start] = TtsReplacementMatch(end, rule.replacement(matcher))
        }
    }
    return matches
}

/** The draft replaces its saved identity/conflict only for preview, without persisting anything. */
internal fun TtsPreferences.withPreviewWord(word: TtsWord): TtsPreferences = copy(words = words.filterNot {
    it.id == word.id || it.surface == word.surface && it.bookId == word.bookId && it.regex == word.regex
} + word)

internal data class TtsWordPreview(
    val word: TtsWord,
    val sentence: TtsSentence,
    val sourcePreferences: TtsPreferences,
    val preferences: TtsPreferences,
    val text: String?,
    val error: TtsWordError?,
    val errorMessage: UiText? = null,
)

internal fun prepareTtsWordPreview(word: TtsWord, sentence: TtsSentence, bookId: String?, preferences: TtsPreferences): TtsWordPreview {
    val error = validateTtsWord(word, preferences.words)
    val config = preferences.withPreviewWord(word)
    val text = if (error == null) TtsText.spoken(sentence, bookId, config.words, config.settings.useRubyReadings) else null
    val message = error?.let { category ->
        val summary = UiText.Resource(category.messageRes())
        val original = if (category == TtsWordError.RegexPattern || category == TtsWordError.RegexReplacement) TtsRegexRule.failure(word) else null
        original?.let(summary::withErrorDetails) ?: summary
    }
    return TtsWordPreview(word, sentence, preferences, config, text, error, message)
}
