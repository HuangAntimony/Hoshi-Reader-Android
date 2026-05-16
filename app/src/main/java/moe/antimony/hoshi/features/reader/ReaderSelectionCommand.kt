package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.HighlightColor

internal sealed interface ReaderSelectionCommand {
    val source: String

    data class SelectText(
        val x: Float,
        val y: Float,
        val maxLength: Int,
    ) : ReaderSelectionCommand {
        override val source: String =
            "window.hoshiSelection.selectText($x, $y, $maxLength)"
    }

    data class HighlightSelection(
        val count: Int,
    ) : ReaderSelectionCommand {
        override val source: String =
            "window.hoshiSelection.highlightSelection($count)"
    }

    data class SelectionRects(
        val count: Int,
    ) : ReaderSelectionCommand {
        override val source: String =
            "JSON.stringify(window.hoshiSelection.selectionRects($count))"
    }

    data object ClearSelection : ReaderSelectionCommand {
        override val source: String =
            "window.hoshiSelection.clearSelection()"
    }
}

internal sealed interface ReaderHighlightCommand {
    val source: String

    data object PrepareSelection : ReaderHighlightCommand {
        override val source: String =
            "window.hoshiHighlights.prepareHighlightSelection()"
    }

    data class Create(
        val color: HighlightColor,
        val id: String,
    ) : ReaderHighlightCommand {
        override val source: String =
            "window.hoshiHighlights.createHighlight('${color.rawValue}', '${id.javaScriptSingleQuotedString()}')"
    }

    data class Remove(
        val id: String,
    ) : ReaderHighlightCommand {
        override val source: String =
            "window.hoshiHighlights.removeHighlight(${id.javaScriptDoubleQuotedString()})"
    }
}

internal data class ReaderSelectionResult(
    val selectedNothing: Boolean,
) {
    companion object {
        fun fromWebViewResult(result: String?): ReaderSelectionResult =
            ReaderSelectionResult(
                selectedNothing = result?.trim()
                    ?.let { it == "null" || it == "undefined" }
                    ?: true,
            )
    }
}

private fun String.javaScriptSingleQuotedString(): String =
    replace("\\", "\\\\").replace("'", "\\'")

private fun String.javaScriptDoubleQuotedString(): String =
    buildString(length + 2) {
        append('"')
        this@javaScriptDoubleQuotedString.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
