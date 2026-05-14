package moe.antimony.hoshi.features.reader

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
