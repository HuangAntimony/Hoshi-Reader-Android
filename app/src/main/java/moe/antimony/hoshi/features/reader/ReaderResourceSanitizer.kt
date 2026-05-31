package moe.antimony.hoshi.features.reader

internal fun sanitizeReaderResource(mediaType: String, bytes: ByteArray): ByteArray {
    if (!mediaType.substringBefore(';').trim().equals("text/css", ignoreCase = true)) {
        return bytes
    }
    return sanitizeReaderCss(bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
}

internal fun sanitizeReaderCss(css: String): String =
    stripPublisherWritingModeCss(sanitizeEpubPrivateCss(sanitizeCalibreCss(css)))

private fun sanitizeCalibreCss(css: String): String {
    var didStripLineHeight = false
    val result = calibreRuleRegex.replace(css) { match ->
        val selector = match.groupValues[1]
        val declarations = match.groupValues[2].split(';')
        if (declarations.any { it.propertyName() == "line-height" }) {
            didStripLineHeight = true
        }
        val stripHeight = declarations.any { it.propertyName() in writingModeProperties }
        val cleaned = declarations
            .mapNotNull { it.sanitizeCalibreDeclaration(stripHeight) }
            .joinToString(separator = ";")
        "$selector{$cleaned}"
    }
    return if (didStripLineHeight) {
        result + "\nbody { line-height: 1.65; }\n"
    } else {
        result
    }
}

private fun sanitizeEpubPrivateCss(css: String): String =
    epubPrivateDeclarationRegex.replace(css) { match ->
        val indent = match.groups["indent"]?.value.orEmpty()
        val property = match.groups["property"]?.value.orEmpty().lowercase()
        val value = match.groups["value"]?.value?.trim().orEmpty()
        replacementDeclarations(indent, property, value)
    }

private val calibreRuleRegex =
    Regex(
        """^(\s*\.(?:calibre\d*|body|c\d*|p\d+)\s*)\{(.*?)\}""",
        setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
    )

private val writingModeProperties = setOf(
    "writing-mode",
    "-webkit-writing-mode",
    "-epub-writing-mode",
)

private val epubPrivateDeclarationRegex =
    Regex("""(?im)^(?<indent>[ \t]*)-epub-(?<property>[^:;{}\r\n]+)[ \t]*:[ \t]*(?<value>[^;{}\r\n]*)[ \t]*;[ \t]*(?:\r?\n)?""")

private val publisherWritingModeDeclarationRegex =
    Regex("""(?i)(^|[;{])\s*(?:-webkit-)?writing-mode\s*:\s*[^;{}]+;?""")

private fun stripPublisherWritingModeCss(css: String): String =
    publisherWritingModeDeclarationRegex.replace(css) { match -> match.groupValues[1] }

private fun String.sanitizeCalibreDeclaration(stripHeight: Boolean): String? =
    when (propertyName()) {
        in writingModeProperties -> null
        "line-height" -> null
        "height" -> if (stripHeight) null else this
        "text-indent" -> {
            val value = substringAfter(':', missingDelimiterValue = "")
                .trim()
            if (value.startsWith("-")) this else " text-indent: 0"
        }
        else -> this
    }

private fun String.propertyName(): String =
    substringBefore(':')
        .trim()
        .lowercase()

private fun replacementDeclarations(indent: String, property: String, value: String): String =
    when (property) {
        // Hoshi controls page direction globally; nested EPUB writing-mode rules can crash Android WebView.
        "writing-mode" -> ""
        "line-break" -> declarations(
            indent,
            "-webkit-line-break" to value,
            "line-break" to value,
        )
        "word-break" -> declarations(
            indent,
            "word-break" to value,
        )
        "hyphens" -> declarations(
            indent,
            "-webkit-hyphens" to value,
            "hyphens" to value,
        )
        "text-underline-position" -> declarations(
            indent,
            "text-underline-position" to value,
        )
        "text-combine" -> declarations(
            indent,
            "-webkit-text-combine" to value,
            "text-combine-upright" to if (value.equals("horizontal", ignoreCase = true)) "all" else value,
        )
        "text-orientation" -> declarations(
            indent,
            "-webkit-text-orientation" to value,
            "text-orientation" to value,
        )
        "text-emphasis-style" -> declarations(
            indent,
            "-webkit-text-emphasis-style" to value,
            "text-emphasis-style" to value,
        )
        "text-emphasis-color" -> declarations(
            indent,
            "-webkit-text-emphasis-color" to value,
            "text-emphasis-color" to value,
        )
        else -> ""
    }

private fun declarations(indent: String, vararg pairs: Pair<String, String>): String =
    pairs.joinToString(separator = "\n", postfix = "\n") { (property, value) ->
        "$indent$property: $value;"
    }
