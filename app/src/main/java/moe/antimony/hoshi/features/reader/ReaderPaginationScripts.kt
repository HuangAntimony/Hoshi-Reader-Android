package moe.antimony.hoshi.features.reader

import androidx.compose.ui.unit.IntSize
import java.io.File
import moe.antimony.hoshi.features.diagnostics.PerformanceLog
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRange

internal enum class ReaderNavigationDirection(val jsValue: String) {
    Forward("forward"),
    Backward("backward"),
}

private fun readerPerfScript(mode: String): String = """
    window.hoshiReaderPerf = {
      mode: '$mode',
      start: performance.now(),
      mark: function(label, extra) {
        console.debug(
          'HoshiReaderPerf js ' + this.mode + ' ' + label +
          ' +' + Math.round(performance.now() - this.start) + 'ms' +
          (extra ? ' ' + extra : '')
        );
      },
      time: function(label, startedAt, extra) {
        console.debug(
          'HoshiReaderPerf js ' + this.mode + ' ' + label +
          ' took ' + Math.round(performance.now() - startedAt) + 'ms' +
          ' total=+' + Math.round(performance.now() - this.start) + 'ms' +
          (extra ? ' ' + extra : '')
        );
      }
    };
    window.hoshiReaderPerf.mark('script top-level');
""".trimIndent()

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
        result?.trim()?.trim('"') == "scrolled"

    fun doubleResult(result: String?): Double? =
        result?.trim()?.trim('"')?.toDoubleOrNull()

    fun shellScript(
        initialProgress: Double = 0.0,
        settings: ReaderSettings = ReaderSettings(),
        sasayakiCuesJson: String? = null,
        highlightsJson: String? = null,
        initialFragment: String? = null,
        viewportCssSize: IntSize? = null,
        assets: ReaderWebAssets? = null,
    ): String = shellScriptWithRestoreToken(
        initialProgress = initialProgress,
        settings = settings,
        sasayakiCuesJson = sasayakiCuesJson,
        highlightsJson = highlightsJson,
        initialFragment = initialFragment,
        viewportCssSize = viewportCssSize,
        restoreToken = "restoreCompleted",
        assets = assets,
    )

    fun shellScriptWithRestoreToken(
        initialProgress: Double = 0.0,
        settings: ReaderSettings = ReaderSettings(),
        sasayakiCuesJson: String? = null,
        highlightsJson: String? = null,
        initialFragment: String? = null,
        viewportCssSize: IntSize? = null,
        restoreToken: String,
        assets: ReaderWebAssets? = null,
    ): String {
        val initialRestoreScript = initialFragment?.let { fragment ->
            "window.hoshiReader.jumpToFragment(${fragment.javaScriptStringLiteral()});"
        } ?: "window.hoshiReader.restoreProgress($initialProgress);"
        val restoreScripts = readerRestoreScripts(
            sasayakiCuesJson = sasayakiCuesJson,
            highlightsJson = highlightsJson,
            initialRestoreScript = initialRestoreScript,
        )
        val body = settingsStageBody(settings, viewportCssSize, assets)
            .replace("__HOSHI_RESTORE_TOKEN_LITERAL__", restoreToken.javaScriptStringLiteral())
            .replace("__HOSHI_RESTORE_SCRIPTS__", restoreScripts)
        return "<script>\n$body\n</script>"
    }

    private data class SettingsStageKey(
        val settings: ReaderSettings,
        val viewportCssSize: IntSize?,
        val assets: ReaderWebAssets?,
    )

    @Volatile
    private var cachedSettingsStage: Pair<SettingsStageKey, String>? = null

    private fun settingsStageBody(
        settings: ReaderSettings,
        viewportCssSize: IntSize?,
        assets: ReaderWebAssets?,
    ): String {
        val key = SettingsStageKey(settings, viewportCssSize, assets)
        cachedSettingsStage?.let { (cachedKey, cachedBody) ->
            if (cachedKey == key) return cachedBody
        }
        PerformanceLog.d(PerformanceLog.ReaderTag, "reader script settings stage rebuilt cached=${cachedSettingsStage != null}")
        val source = ReaderPaginationAssetSource.load(assets)
        val mode = if (settings.continuousMode) "continuous" else "paginated"
        val template = if (settings.continuousMode) source.continuous else source.paginated
        val generatedLayout = ReaderGeneratedLayout.from(settings)
        val viewportWidthJs = viewportCssSize?.width?.coerceAtLeast(1)?.toString() ?: "window.innerWidth"
        val viewportHeightJs = viewportCssSize?.height?.coerceAtLeast(1)?.toString() ?: "window.innerHeight"
        val pageHeightJs = viewportCssSize?.height
            ?.coerceAtLeast(1)
            ?.let { (it + settings.bottomOverlapPx).toString() }
            ?: "(window.innerHeight + ${settings.bottomOverlapPx})"
        val pageWidthJs = viewportWidthJs
        val body = "${readerPerfScript(mode)}\n" + template
            .replace("__HOSHI_HIGHLIGHTS_SCRIPT__", source.highlights)
            .replace("__HOSHI_BOTTOM_OVERLAP_PX__", settings.bottomOverlapPx.toString())
            .replace("__HOSHI_VIEWPORT_WIDTH_JS__", viewportWidthJs)
            .replace("__HOSHI_VIEWPORT_HEIGHT_JS__", viewportHeightJs)
            .replace("__HOSHI_PAGE_HEIGHT_JS__", pageHeightJs)
            .replace("__HOSHI_PAGE_WIDTH_JS__", pageWidthJs)
            .replace("__HOSHI_VERTICAL_WRITING_JS__", settings.verticalWriting.toString())
            .replace("__HOSHI_VERTICAL_PADDING_BLOCK_RATIO__", (settings.verticalPadding / 200.0).toString())
            .replace("__HOSHI_VERTICAL_PADDING_GAP_RATIO__", (settings.verticalPadding / 100.0).toString())
            .replace("__HOSHI_IMAGE_WIDTH_VIEWPORT_RATIO__", generatedLayout.imageWidthViewportRatio.toString())
            .replace("__HOSHI_IMAGE_HEIGHT_VIEWPORT_RATIO__", generatedLayout.imageHeightViewportRatio.toString())
            .replace("__HOSHI_IMAGE_WIDTH_REDUCTION_PX__", generatedLayout.imageWidthReductionPx.toString())
            .replace("__HOSHI_TRAILING_SPACER_HEIGHT_LITERAL__", settings.trailingSpacerHeightCss.javaScriptSingleQuotedStringLiteral())
            .replace("__HOSHI_TRAILING_SPACER_WIDTH_LITERAL__", settings.trailingSpacerWidthCss.javaScriptSingleQuotedStringLiteral())
            .replace("__HOSHI_BLUR_IMAGES__", settings.blurImages.toString())
        cachedSettingsStage = key to body
        return body
    }
}

private data class ReaderPaginationAssetSource(
    val paginated: String,
    val continuous: String,
    val highlights: String,
) {
    companion object {
        fun load(assets: ReaderWebAssets?): ReaderPaginationAssetSource {
            if (assets != null) {
                return ReaderPaginationAssetSource(
                    paginated = assets.readerPaginatedJs,
                    continuous = assets.readerContinuousJs,
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
    sasayakiCuesJson?.let(ReaderPaginationScripts::applySasayakiCuesInvocation),
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
