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
        measurementOnly: Boolean = false,
    ): String {
        val initialRestoreScript = initialFragment?.let { fragment ->
            "window.hoshiReader.jumpToFragment(${fragment.javaScriptStringLiteral()});"
        } ?: "window.hoshiReader.restoreProgress($initialProgress);"
        val source = ReaderPaginationAssetSource.load(assets)
        val template = when (settings.viewMode) {
            ReaderViewMode.Paginated -> source.paginatedTemplate
            ReaderViewMode.Continuous -> source.continuousTemplate
        }
        val restoreScripts = if (measurementOnly) {
            "window.hoshiReader.calculatePageStartsForMeasurement().then(starts => window.HoshiReaderPages.postMessage(${restoreToken.javaScriptStringLiteral()}, JSON.stringify(starts)));"
        } else readerRestoreScripts(
            sasayakiCuesJson = sasayakiCuesJson,
            highlightsJson = highlightsJson,
            initialRestoreScript = initialRestoreScript,
        )
        val generatedLayout = ReaderGeneratedLayout.from(settings)
        val body = template.render(mapOf(
            "__HOSHI_RESTORE_TOKEN_LITERAL__" to restoreToken.javaScriptStringLiteral(),
            "__HOSHI_PARAGRAPH_ENABLED__" to settings.paragraphMode.toString(),
            "__HOSHI_PARAGRAPH_MAX_SENTENCES__" to settings.maxSentencesPerPage.coerceIn(0, 10).toString(),
            "__HOSHI_PARAGRAPH_SPLIT_DIALOGUE__" to settings.splitDialogue.toString(),
            "__HOSHI_PARAGRAPH_TEXT_ANIMATION__" to settings.textAnimation.toString(),
            "__HOSHI_PARAGRAPH_TEXT_SPEED__" to settings.textSpeed.coerceParagraphTextSpeed().toString(),
            "__HOSHI_CONTINUOUS_HORIZONTAL_PADDING_RATIO__" to generatedLayout.viewportHorizontalPaddingRatio.toString(),
            "__HOSHI_CONTINUOUS_VERTICAL_PADDING_RATIO__" to generatedLayout.viewportVerticalPaddingRatio.toString(),
            "__HOSHI_VERTICAL_PADDING_BLOCK_RATIO__" to (settings.verticalPadding / 200.0).toString(),
            "__HOSHI_VERTICAL_PADDING_GAP_RATIO__" to (settings.verticalPadding / 100.0).toString(),
            "__HOSHI_IMAGE_WIDTH_VIEWPORT_RATIO__" to generatedLayout.imageWidthViewportRatio.toString(),
            "__HOSHI_IMAGE_HEIGHT_VIEWPORT_RATIO__" to generatedLayout.imageHeightViewportRatio.toString(),
            "__HOSHI_IMAGE_WIDTH_REDUCTION_PX__" to generatedLayout.imageWidthReductionPx.toString(),
            "__HOSHI_TRAILING_SPACER_HEIGHT_LITERAL__" to settings.trailingSpacerHeightCss.javaScriptSingleQuotedStringLiteral(),
            "__HOSHI_TRAILING_SPACER_WIDTH_LITERAL__" to settings.trailingSpacerWidthCss.javaScriptSingleQuotedStringLiteral(),
            "__HOSHI_BLUR_IMAGES__" to settings.blurImages.toString(),
            "__HOSHI_RESTORE_SCRIPTS__" to restoreScripts,
        ))
        return "<script>\n" + (if (measurementOnly) "window.hoshiReaderMeasurement = true;\n" else "") + "$body\n</script>"
    }
}

internal data class ReaderPaginationAssetSource(
    val paginated: String,
    val continuous: String,
    val paragraph: String,
    val readerViewport: String,
    val readerSasayaki: String,
    val readerTextSemantics: String,
    val readerDomText: String,
    val readerMediaSemantics: String,
    val highlights: String,
) {
    private val components: Map<String, String>
        get() = mapOf(
            "__HOSHI_HIGHLIGHTS_SCRIPT__" to highlights,
            "__HOSHI_READER_VIEWPORT_SCRIPT__" to readerViewport,
            "__HOSHI_READER_SASAYAKI_SCRIPT__" to readerSasayaki,
            "__HOSHI_READER_TEXT_SEMANTICS_SCRIPT__" to readerTextSemantics,
            "__HOSHI_READER_DOM_TEXT_SCRIPT__" to readerDomText,
            "__HOSHI_READER_MEDIA_SEMANTICS_SCRIPT__" to readerMediaSemantics,
            "__HOSHI_READER_PARAGRAPH_SCRIPT__" to paragraph,
        )
    val paginatedTemplate by lazy { ReaderScriptTemplate(paginated, components) }
    val continuousTemplate by lazy { ReaderScriptTemplate(continuous, components) }

    companion object {
        fun load(assets: ReaderWebAssets?): ReaderPaginationAssetSource =
            assets?.paginationSource ?: SourceTreeReaderPaginationAssets.value

        fun from(assets: ReaderWebAssets): ReaderPaginationAssetSource = ReaderPaginationAssetSource(
            paginated = assets.readerPaginatedJs,
            continuous = assets.readerContinuousJs,
            paragraph = assets.readerParagraphJs,
            readerViewport = assets.readerViewportJs,
            readerSasayaki = assets.readerSasayakiJs,
            readerTextSemantics = assets.readerTextSemanticsJs,
            readerDomText = assets.readerDomTextJs,
            readerMediaSemantics = assets.readerMediaSemanticsJs,
            highlights = assets.highlightsJs,
        )
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
    sasayakiCuesJson?.let { "window.hoshiReader.applySasayakiCues($it);" },
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
