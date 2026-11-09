package moe.antimony.hoshi.features.reader

/** Compile static assets once; chapter setup only appends literals and escaped parameters. */
internal class ReaderScriptTemplate(source: String, components: Map<String, String>) {
    private data class Part(val literal: String? = null, val parameter: String? = null)
    private val parts = buildList {
        fun compile(text: String) {
            var start = 0
            for (match in placeholder.findAll(text)) {
                if (start < match.range.first) add(Part(literal = text.substring(start, match.range.first)))
                val component = components[match.value]
                if (component != null) compile(component) else add(Part(parameter = match.value))
                start = match.range.last + 1
            }
            if (start < text.length) add(Part(literal = text.substring(start)))
        }
        compile(source)
    }
    private val literalLength = parts.sumOf { it.literal?.length ?: 0 }

    fun render(parameters: Map<String, String>): String = buildString(literalLength + 1024) {
        for (part in parts) append(part.literal ?: parameters.getValue(requireNotNull(part.parameter)))
    }

    private companion object {
        val placeholder = Regex("__HOSHI_[A-Z_]+__")
    }
}
