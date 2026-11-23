package moe.antimony.hoshi.features.reader

import java.net.URI
import moe.antimony.hoshi.epub.EpubBook
import org.jsoup.nodes.Document

/** Chapters with the same ordered stylesheets share the compiled CSS, including their font faces. */
internal class ReaderBookStyles(private val book: EpubBook) {
    private data class Sheet(val base: URI, val inline: String?, val media: String)
    private val groups = mutableMapOf<List<Sheet>, BookStyleGroup>()
    private val files = mutableMapOf<String, String>()

    fun collect(document: Document, base: URI): BookStyleGroup {
        val sheets = document.select("style, link[href]").mapNotNull { element ->
            val media = element.attr("media").trim()
            if (element.normalName() == "style") {
                Sheet(base.resolve("."), element.data().ifEmpty { element.wholeText() }.trim().removeSurrounding("<![CDATA[", "]]>"), media)
            } else {
                val rel = element.attr("rel").lowercase().split(Regex("\\s+"))
                if ("stylesheet" !in rel || "alternate" in rel || element.hasAttr("disabled")) return@mapNotNull null
                resolveBookUrl(base, element.attr("href"))?.let { Sheet(it, null, media) }
            }
        }
        return groups.getOrPut(sheets) {
            val rules = sheets.flatMap { sheet ->
                val rules = load(sheet.base, sheet.inline, mutableSetOf())
                if (sheet.media.isBlank()) rules else listOf(CssRule.Group("@media ${sheet.media}", rules))
            }
            BookStyleGroup("s${groups.size}", rules)
        }
    }

    private fun load(base: URI, inline: String?, importing: MutableSet<String>): List<CssRule> {
        val path = base.epubPath().orEmpty()
        if (inline == null && (!book.canReadEntry(path) || !importing.add(path))) return emptyList()
        val source = inline ?: files.getOrPut(path) { book.readResource(path)?.decodeToString().orEmpty() }
        val rules = parseRules(sanitizeReaderCss(source), base) { value, media ->
            val imported = resolveBookUrl(base, value)?.let { load(it, null, importing) }.orEmpty()
            if (media.isEmpty()) imported else listOf(CssRule.Group("@media $media", imported))
        }
        if (inline == null) importing.remove(path)
        return rules
    }

    fun render(): String = buildString {
        groups.values.forEach { append(it.css) }
    }
}

internal sealed interface CssRule {
    data class Group(val header: String, val rules: List<CssRule>) : CssRule
    data class Block(val header: String, val declarations: String, val base: URI) : CssRule
}

internal class BookStyleGroup(val token: String, rules: List<CssRule>) {
    private val fragments = mutableMapOf<String, String>()
    private val fonts = mutableMapOf<String, String>()
    private val animations = mutableMapOf<String, String>()

    private fun collectNames(rule: CssRule) {
        when (rule) {
            is CssRule.Group -> rule.rules.forEach(::collectNames)
            is CssRule.Block -> {
                if (rule.header.equals("@font-face", true)) {
                    val name = fontFaceFamily.find(rule.declarations)?.groupValues?.get(1)?.trim()?.trim('\'', '"')
                    if (!name.isNullOrEmpty()) fonts.putIfAbsent(name, "hoshi-font-$token-${fonts.size}")
                }
                val name = keyframes.matchEntire(rule.header)?.groupValues?.get(2)
                if (name != null) animations.putIfAbsent(name.trim('\'', '"'), "hoshi-animation-$token-${animations.size}")
            }
        }
    }

    init { rules.forEach(::collectNames) }

    private fun aliases(names: Map<String, String>): List<Pair<Regex, String>> =
        names.entries.sortedByDescending { it.key.length }.map { (name, alias) ->
            Regex("(?i)(?<![\\w-])" + Regex.escape(name) + "(?![\\w-])") to alias
        }

    private val fontAliases = aliases(fonts)
    private val animationAliases = aliases(animations)


    val css = buildString { rules.forEach { writeRule(it) } }

    private fun StringBuilder.writeRule(rule: CssRule) {
        when (rule) {
            is CssRule.Group -> {
                append(rule.header).append('{')
                rule.rules.forEach { writeRule(it) }
                append("}\n")
            }
            is CssRule.Block -> {
                val animation = keyframes.matchEntire(rule.header)
                when {
                    animation != null -> append(animation.groupValues[1]).append(' ')
                        .append(animations[animation.groupValues[2].trim('\'', '"')])
                    rule.header.equals("@font-face", true) -> append("@font-face")
                    rule.header.startsWith('@') -> return // @page, @property etc. affect the entire host document.
                    else -> append(splitSelectors(rule.header).joinToString(",") {
                        "[data-hoshi-styles=\"$token\"] ${remapSelector(it)}"
                    })
                }
                append('{').append(declarations(rule.declarations, rule.base, null)).append("}\n")
            }
        }
    }

    fun inlineStyle(value: String, base: URI, idPrefix: String): String =
        declarations(sanitizeReaderCss(value), base, idPrefix)

    private fun declarations(value: String, base: URI, idPrefix: String?): String {
        val rebased = rebaseCssUrls(value, base, fragmentValue = { id ->
            if (idPrefix != null) "url(\"#${cssString(idPrefix + id)}\")"
            else "var(${fragments.getOrPut(id) { "--hoshi-fragment-$token-${fragments.size}" }})"
        })
        if (fonts.isEmpty() && animations.isEmpty()) return rebased
        return namedDeclaration.substitute(rebased) { match ->
            val property = match.group(1).orEmpty()
            val aliases = if (property.startsWith("font", true)) fontAliases else animationAliases
            val names = aliases.fold(match.group(2).orEmpty()) { value, (pattern, alias) -> pattern.replace(value, alias) }
            "$property:$names"
        }
    }

    fun fragmentVariables(idPrefix: String): String = fragments.entries.joinToString(";") { (id, variable) ->
        "$variable:url(\"#${cssString(idPrefix + id)}\")"
    }
}

private val fontFaceFamily = Regex("""(?:^|;)\s*font-family\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE)
private val keyframes = Regex("""(@(?:-webkit-)?keyframes)\s+(\S+)""", RegexOption.IGNORE_CASE)
private val namedDeclaration = Regex("""(?<![\w-])(font(?:-family)?|(?:-webkit-)?animation(?:-name)?)\s*:([^;{}]*)""", RegexOption.IGNORE_CASE)
private val imported = Regex("""@import\s+(?:url\(\s*(?:"([^"]*)"|'([^']*)'|([^\s)]+))\s*\)|"([^"]*)"|'([^']*)')\s*(.*)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val cssGroup = Regex("""@(?:media|supports|container|layer|starting-style)\b.*""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/** CSS delimiters in strings, escaped identifiers, comments and functions are data, not rule boundaries. */
private fun cssDelimiter(source: String, from: Int, stops: String): Int {
    var cursor = from
    var quote = '\u0000'
    var parens = 0
    var brackets = 0
    var braces = 0
    while (cursor < source.length) {
        val c = source[cursor]
        when {
            c == '\\' -> cursor++
            quote != '\u0000' -> if (c == quote) quote = '\u0000'
            c == '\'' || c == '"' -> quote = c
            source.startsWith("/*", cursor) -> {
                val end = source.indexOf("*/", cursor + 2)
                if (end < 0) return source.length
                cursor = end + 1
            }
            parens == 0 && brackets == 0 && braces == 0 && c in stops -> return cursor
            c == '(' -> parens++
            c == ')' -> parens--
            c == '[' -> brackets++
            c == ']' -> brackets--
            c == '{' -> braces++
            c == '}' -> braces--
        }
        cursor++
    }
    return source.length
}

private fun parseRules(source: String, base: URI, import: (String, String) -> List<CssRule>): List<CssRule> = buildList {
    var cursor = 0
    while (cursor < source.length) {
        if (source[cursor].isWhitespace() || source[cursor] == '\uFEFF') { cursor++; continue }
        if (source.startsWith("/*", cursor)) {
            val end = source.indexOf("*/", cursor + 2)
            if (end < 0) break
            cursor = end + 2
            continue
        }
        val end = cssDelimiter(source, cursor, ";{")
        if (end == source.length) break
        val header = source.substring(cursor, end).trim()
        if (source[end] == ';') {
            imported.matchEntire(header)?.let { match ->
                val path = match.groupValues.slice(1..5).firstOrNull(String::isNotEmpty)
                if (path != null) addAll(import(path, match.groupValues[6]))
            }
            cursor = end + 1
        } else {
            val close = cssDelimiter(source, end + 1, "}")
            val body = source.substring(end + 1, close)
            if (cssGroup.matches(header)) add(CssRule.Group(header, parseRules(body, base, import)))
            else if (header.isNotEmpty()) add(CssRule.Block(header, body, base))
            cursor = close + 1
        }
    }
}

private fun splitSelectors(value: String): List<String> = buildList {
    var start = 0
    while (start < value.length) {
        val end = cssDelimiter(value, start, ",")
        add(value.substring(start, end).trim())
        start = end + 1
    }
}

// Match whole tokens so class names such as .body, quoted values and attribute selectors stay intact.
private val selectorToken = Regex("""/\*[\s\S]*?\*/|\[(?:[^\]"']|"[^"]*"|'[^']*')*\]|"(?:\\.|[^"])*"|'(?:\\.|[^'])*'|(?:[#.:])?(?:[\w\u0080-\uFFFF-]|\\.)+""")
private val idAttribute = Regex("""^(\[\s*)id(?=\s*[~|^$*!=\]])""", RegexOption.IGNORE_CASE)

private fun remapSelector(value: String): String = selectorToken.substitute(value) {
    val token = it.group()
    when {
        token.equals("html", true) -> "hoshi-html"
        token.equals(":root", true) -> "[data-hoshi-root]"
        token.equals("body", true) -> "hoshi-body"
        token.startsWith('#') -> ":is(#hoshi-unused-id,[data-hoshi-id=\"${token.drop(1)}\"])"
        token.startsWith('[') -> idAttribute.replace(token, "$1data-hoshi-id")
        else -> token
    }
}

private val urlToken = Regex("""/\*[\s\S]*?\*/|"(?:[^"\\]++|\\.)*+"|'(?:[^'\\]++|\\.)*+'|\burl\(\s*(?:"((?:[^"\\]++|\\.)*+)"|'((?:[^'\\]++|\\.)*+)'|((?:[^)\\]++|\\.)*+))\s*\)""", RegexOption.IGNORE_CASE)

internal fun rebaseCssUrls(value: String, base: URI, fragmentValue: (String) -> String): String = urlToken.substitute(value) { match ->
    if (!match.group().startsWith("url(", true)) return@substitute match.group()
    val raw = (match.group(1) ?: match.group(2) ?: match.group(3)).trim()
    if (raw.startsWith('#')) return@substitute fragmentValue(raw.drop(1))
    val target = if (raw.startsWith("data:", true)) raw else resolveBookUrl(base, raw)?.toASCIIString().orEmpty()
    "url(\"${cssString(target)}\")"
}

internal fun cssString(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\a ").replace("\r", "\\d ")

// Use a single Matcher per pass. Android ICU otherwise repeatedly copies the full stylesheet.
private inline fun Regex.substitute(value: String, replacement: (java.util.regex.Matcher) -> String): String {
    val matcher = toPattern().matcher(value)
    if (!matcher.find()) return value
    return buildString(value.length) {
        var end = 0
        do {
            append(value, end, matcher.start()).append(replacement(matcher))
            end = matcher.end()
        } while (matcher.find())
        append(value, end, value.length)
    }
}
