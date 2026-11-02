package moe.antimony.hoshi.features.reader

import java.io.File
import kotlinx.serialization.Serializable
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRange

@Serializable
internal data class ReaderSasayakiMediaStop(
    val scroll: Double? = null,
)

internal enum class ReaderNavigationDirection(val jsValue: String) {
    Forward("forward"),
    Backward("backward"),
}

internal enum class ReaderNavigationResult {
    Advanced,
    Blocked,
    Limit,
}

internal object ReaderPaginationScripts {
    fun showSearchHighlightInvocation(highlight: ReaderSearchHighlight): String =
        "window.hoshiHighlights.showSearchHighlight(${highlight.offset}, ${highlight.length})"

    fun clearSearchHighlightInvocation(): String =
        "if (window.hoshiHighlights) window.hoshiHighlights.clearSearchHighlight()"

    fun paginateInvocation(direction: ReaderNavigationDirection): String =
        "window.hoshiReader.paginateWithProgress('${direction.jsValue}')"

    fun progressInvocation(): String =
        "window.hoshiReader.calculateProgress()"

    fun applySasayakiCuesInvocation(cuesJson: String): String =
        "if (window.hoshiReader && typeof window.hoshiReader.applySasayakiCues === 'function') { window.hoshiReader.applySasayakiCues($cuesJson); }"

    fun highlightSasayakiCueInvocation(cue: SasayakiCueRange, reveal: Boolean): String =
        "window.hoshiReader.highlightSasayakiCue(${cue.toJavaScriptObjectLiteral()}, $reveal)"

    fun sasayakiMediaStopsBeforeCueInvocation(cue: SasayakiCueRange): String =
        "window.hoshiReader.sasayakiMediaStopsBeforeCue(${cue.toJavaScriptObjectLiteral()})"

    fun sasayakiMediaStopsToChapterEndInvocation(): String =
        "window.hoshiReader.sasayakiMediaStopsToChapterEnd()"

    fun showSasayakiMediaStopInvocation(stopJson: String): String =
        "window.hoshiReader.showSasayakiMediaStop($stopJson)"

    fun clearSasayakiCueInvocation(): String =
        "window.hoshiReader.clearSasayakiCue()"

    fun didScroll(result: String?): Boolean =
        navigationResult(result) == ReaderNavigationResult.Advanced

    fun navigationResult(result: String?): ReaderNavigationResult =
        when {
            doubleResult(result)?.let { it in 0.0..1.0 } == true -> ReaderNavigationResult.Advanced
            else -> when (result?.trim()?.trim('"')) {
                "scrolled" -> ReaderNavigationResult.Advanced
                "blocked" -> ReaderNavigationResult.Blocked
                else -> ReaderNavigationResult.Limit
            }
        }

    fun doubleResult(result: String?): Double? =
        result?.trim()?.trim('"')?.toDoubleOrNull()

    fun shellScript(
        initialProgress: Double = 0.0,
        settings: ReaderSettings = ReaderSettings(),
        sasayakiCuesJson: String? = null,
        highlightsJson: String? = null,
        initialFragment: String? = null,
        assets: ReaderWebAssets? = null,
    ): String = shellScriptWithRestoreToken(
        initialProgress = initialProgress,
        settings = settings,
        sasayakiCuesJson = sasayakiCuesJson,
        highlightsJson = highlightsJson,
        initialFragment = initialFragment,
        restoreToken = "restoreCompleted",
        assets = assets,
    )

    fun shellScriptWithRestoreToken(
        initialProgress: Double = 0.0,
        settings: ReaderSettings = ReaderSettings(),
        sasayakiCuesJson: String? = null,
        highlightsJson: String? = null,
        initialFragment: String? = null,
        restoreToken: String,
        assets: ReaderWebAssets? = null,
    ): String {
        val initialRestoreScript = initialFragment?.let { fragment ->
            "window.hoshiReader.jumpToFragment(${fragment.javaScriptStringLiteral()});"
        } ?: "window.hoshiReader.restoreProgress($initialProgress);"
        val source = ReaderPaginationAssetSource.load(assets)
        val template = when (settings.viewMode) {
            ReaderViewMode.Paginated -> source.paginated
            ReaderViewMode.Continuous -> source.continuous
        }
        val restoreScripts = readerRestoreScripts(
            sasayakiCuesJson = sasayakiCuesJson,
            highlightsJson = highlightsJson,
            initialRestoreScript = initialRestoreScript,
        )
        val generatedLayout = ReaderGeneratedLayout.from(settings)
        val body = template
            .replace("__HOSHI_HIGHLIGHTS_SCRIPT__", source.highlights)
            .replace("__HOSHI_READER_VIEWPORT_SCRIPT__", source.readerViewport)
            .replace("__HOSHI_READER_SASAYAKI_SCRIPT__", source.readerSasayaki)
            .replace("__HOSHI_READER_TEXT_SEMANTICS_SCRIPT__", source.readerTextSemantics)
            .replace("__HOSHI_READER_DOM_TEXT_SCRIPT__", source.readerDomText)
            .replace("__HOSHI_READER_MEDIA_SEMANTICS_SCRIPT__", source.readerMediaSemantics)
            .replace("__HOSHI_READER_LAYOUT_SEMANTICS_SCRIPT__", source.readerLayoutSemantics)
            .replace("__HOSHI_RESTORE_TOKEN_LITERAL__", restoreToken.javaScriptStringLiteral())
            .replace("__HOSHI_READER_PARAGRAPH_SCRIPT__", source.paragraph)
            .replace("__HOSHI_PARAGRAPH_ENABLED__", settings.paragraphMode.toString())
            .replace("__HOSHI_PARAGRAPH_MAX_SENTENCES__", settings.maxSentencesPerPage.coerceIn(0, 10).toString())
            .replace("__HOSHI_PARAGRAPH_SPLIT_DIALOGUE__", settings.splitDialogue.toString())
            .replace("__HOSHI_PARAGRAPH_TEXT_ANIMATION__", settings.textAnimation.toString())
            .replace("__HOSHI_PARAGRAPH_TEXT_SPEED__", settings.textSpeed.coerceParagraphTextSpeed().toString())
            .replace("__HOSHI_CONTINUOUS_HORIZONTAL_PADDING_RATIO__", generatedLayout.viewportHorizontalPaddingRatio.toString())
            .replace("__HOSHI_CONTINUOUS_VERTICAL_PADDING_RATIO__", generatedLayout.viewportVerticalPaddingRatio.toString())
            .replace("__HOSHI_VERTICAL_PADDING_BLOCK_RATIO__", (settings.verticalPadding / 200.0).toString())
            .replace("__HOSHI_VERTICAL_PADDING_GAP_RATIO__", (settings.verticalPadding / 100.0).toString())
            .replace("__HOSHI_IMAGE_WIDTH_VIEWPORT_RATIO__", generatedLayout.imageWidthViewportRatio.toString())
            .replace("__HOSHI_IMAGE_HEIGHT_VIEWPORT_RATIO__", generatedLayout.imageHeightViewportRatio.toString())
            .replace("__HOSHI_IMAGE_WIDTH_REDUCTION_PX__", generatedLayout.imageWidthReductionPx.toString())
            .replace("__HOSHI_TRAILING_SPACER_HEIGHT_LITERAL__", settings.trailingSpacerHeightCss.javaScriptSingleQuotedStringLiteral())
            .replace("__HOSHI_TRAILING_SPACER_WIDTH_LITERAL__", settings.trailingSpacerWidthCss.javaScriptSingleQuotedStringLiteral())
            .replace("__HOSHI_BLUR_IMAGES__", settings.blurImages.toString())
            .replace("__HOSHI_RESTORE_SCRIPTS__", restoreScripts)
        return "<script>\n$body\n</script>"
    }
}

private data class ReaderPaginationAssetSource(
    val paginated: String,
    val continuous: String,
    val paragraph: String,
    val readerViewport: String,
    val readerSasayaki: String,
    val readerTextSemantics: String,
    val readerDomText: String,
    val readerMediaSemantics: String,
    val readerLayoutSemantics: String,
    val highlights: String,
) {
    companion object {
        fun load(assets: ReaderWebAssets?): ReaderPaginationAssetSource {
            if (assets != null) {
                return ReaderPaginationAssetSource(
                    paginated = assets.readerPaginatedJs,
                    continuous = assets.readerContinuousJs,
                    paragraph = assets.readerParagraphJs,
                    readerViewport = assets.readerViewportJs,
                    readerSasayaki = assets.readerSasayakiJs,
                    readerTextSemantics = assets.readerTextSemanticsJs,
                    readerDomText = assets.readerDomTextJs,
                    readerMediaSemantics = assets.readerMediaSemanticsJs,
                    readerLayoutSemantics = assets.readerLayoutSemanticsJs,
                    highlights = assets.highlightsJs,
                )
            }
            return SourceTreeReaderPaginationAssets.value
        }
    }
}

private object SourceTreeReaderPaginationAssets {
    val value: ReaderPaginationAssetSource by lazy {
        ReaderPaginationAssetSource(
            paginated = readSourceAsset("hoshi-web/reader/reader-paginated.js"),
            continuous = readSourceAsset("hoshi-web/reader/reader-continuous.js"),
            paragraph = readSourceAsset("hoshi-web/reader/reader-paragraph.js"),
            readerViewport = readSourceAsset("hoshi-web/reader/reader-viewport.js"),
            readerSasayaki = readSourceAsset("hoshi-web/reader/reader-sasayaki.js"),
            readerTextSemantics = readSourceAsset("hoshi-web/reader/reader-text-semantics.js"),
            readerDomText = readSourceAsset("hoshi-web/reader/reader-dom-text.js"),
            readerMediaSemantics = readSourceAsset("hoshi-web/reader/reader-media-semantics.js"),
            readerLayoutSemantics = readSourceAsset("hoshi-web/reader/reader-layout-semantics.js"),
            highlights = readSourceAsset("hoshi-web/reader/highlights.js"),
        )
    }

    private fun readSourceAsset(path: String): String {
        val candidates = listOf(
            File("app/src/main/assets/$path"),
            File("src/main/assets/$path"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Reader web asset not found in source tree: $path")
    }
}

private fun readerRestoreScripts(
    sasayakiCuesJson: String?,
    highlightsJson: String?,
    initialRestoreScript: String,
): String = listOfNotNull(
    highlightsJson?.let { "window.hoshiHighlights.applyHighlights($it);" },
    "window.hoshiReader.applySasayakiCues(${sasayakiCuesJson ?: "[]"});",
    initialRestoreScript,
).joinToString(separator = "\n")

private fun String.javaScriptStringLiteral(): String =
    buildString(length + 2) {
        append('"')
        this@javaScriptStringLiteral.forEach { ch ->
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

private fun String.javaScriptSingleQuotedStringLiteral(): String =
    buildString(length + 2) {
        append('\'')
        this@javaScriptSingleQuotedStringLiteral.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('\'')
    }

private fun SasayakiCueRange.toJavaScriptObjectLiteral(): String =
    "{id:${id.javaScriptStringLiteral()},start:$start,length:$length}"
