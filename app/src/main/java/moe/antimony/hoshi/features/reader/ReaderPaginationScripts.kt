package moe.antimony.hoshi.features.reader

import java.io.File
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRange

internal enum class ReaderNavigationDirection(val jsValue: String) {
    Forward("forward"),
    Backward("backward"),
}

internal enum class ReaderNavigationResult {
    Advanced,
    Revealed,
    Limit,
}

internal object ReaderPaginationScripts {
    fun paginateInvocation(direction: ReaderNavigationDirection): String =
        "window.hoshiReader.paginate('${direction.jsValue}')"

    fun nativeSelectionActiveInvocation(active: Boolean): String =
        "if (window.hoshiReader && typeof window.hoshiReader.setNativeSelectionActive === 'function') { window.hoshiReader.setNativeSelectionActive($active); }"

    fun progressInvocation(): String =
        "window.hoshiReader.calculateProgress()"

    fun applySasayakiCuesInvocation(cuesJson: String): String =
        "if (window.hoshiReader && typeof window.hoshiReader.applySasayakiCues === 'function') { window.hoshiReader.applySasayakiCues($cuesJson); }"

    fun highlightSasayakiCueInvocation(cue: SasayakiCueRange, reveal: Boolean): String =
        "window.hoshiReader.highlightSasayakiCue(${cue.toJavaScriptObjectLiteral()}, $reveal)"

    fun clearSasayakiCueInvocation(): String =
        "window.hoshiReader.clearSasayakiCue()"

    fun didScroll(result: String?): Boolean =
        navigationResult(result) == ReaderNavigationResult.Advanced

    fun navigationResult(result: String?): ReaderNavigationResult =
        when (result?.trim()?.trim('"')) {
            "scrolled" -> ReaderNavigationResult.Advanced
            "revealed" -> ReaderNavigationResult.Revealed
            else -> ReaderNavigationResult.Limit
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
            ReaderViewMode.VisualNovel -> source.visualNovel
        }
        val restoreScripts = if (settings.viewMode == ReaderViewMode.VisualNovel) {
            ""
        } else {
            readerRestoreScripts(
                highlightsJson = highlightsJson,
                initialRestoreScript = initialRestoreScript,
            )
        }
        val generatedLayout = ReaderGeneratedLayout.from(settings)
        val body = template
            .replace("__HOSHI_HIGHLIGHTS_SCRIPT__", source.highlights)
            .replace("__HOSHI_RESTORE_TOKEN_LITERAL__", restoreToken.javaScriptStringLiteral())
            .replace("__HOSHI_VISUAL_NOVEL_REVEAL_SPEED__", settings.visualNovelRevealSpeed.coerceIn(0, 120).toString())
            .replace("__HOSHI_VISUAL_NOVEL_SCREEN_MODE_LITERAL__", settings.visualNovelScreenMode.rawValue.javaScriptStringLiteral())
            .replace(
                "__HOSHI_VISUAL_NOVEL_SENTENCES_PER_SCREEN__",
                settings.visualNovelSentencesPerScreen.coerceIn(1, 12).toString(),
            )
            .replace("__HOSHI_VISUAL_NOVEL_PRESERVE_DIALOGUE__", settings.visualNovelPreserveDialogueBubbles.toString())
            .replace("__HOSHI_INITIAL_PROGRESS__", initialProgress.toString())
            .replace(
                "__HOSHI_INITIAL_FRAGMENT_LITERAL__",
                initialFragment?.javaScriptStringLiteral() ?: "null",
            )
            .replace("__HOSHI_INITIAL_HIGHLIGHTS_JSON__", highlightsJson ?: "null")
            .replace("__HOSHI_BOTTOM_OVERLAP_PX__", settings.bottomOverlapPx.toString())
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
    val visualNovel: String,
    val highlights: String,
) {
    companion object {
        fun load(assets: ReaderWebAssets?): ReaderPaginationAssetSource {
            if (assets != null) {
                return ReaderPaginationAssetSource(
                    paginated = assets.readerPaginatedJs,
                    continuous = assets.readerContinuousJs,
                    visualNovel = assets.readerVisualNovelJs,
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
            visualNovel = readSourceAsset("hoshi-web/reader/reader-visual-novel.js"),
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
    highlightsJson: String?,
    initialRestoreScript: String,
): String = listOfNotNull(
    highlightsJson?.let { "window.hoshiHighlights.applyHighlights($it);" },
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
