package moe.antimony.hoshi.features.reader

import java.io.File
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.epub.HighlightColor

internal object ReaderLayoutDefaults {
    // Mirrors the first-run iOS ReaderWebView defaults:
    // verticalWriting=true, fontSize=22, horizontalPadding=5, verticalPadding=0.
    const val fontSizePx: Int = 22
    const val bottomOverlapPx: Int = fontSizePx
    const val imageWidthViewportRatio: Double = 0.95

    const val columnGapCss: String = "calc(0vh + 22px)"
    const val pagePaddingCss: String = "0vh 2.5vw"
    const val bottomPaddingCss: String = "calc(0vh + 22px)"
    const val imageMaxWidthFallbackCss: String = "95vw"
    const val imageMaxHeightFallbackCss: String = "calc(var(--page-height, 100vh) - 22px)"
    const val trailingSpacerHeightCss: String = "calc(0vh + 22px)"
    // The spacer only needs inline height; physical width would allocate a new column in Android WebView.
    const val trailingSpacerWidthCss: String = "0"
}

internal data class ReaderGeneratedLayout(
    val viewportHorizontalPaddingRatio: Double,
    val viewportVerticalPaddingRatio: Double,
    val continuousBodyPaddingCss: String,
    val continuousBodyBottomPaddingCss: String,
    val paginatedColumnWidthCss: String,
    val imageWidthViewportRatio: Double,
    val imageHeightViewportRatio: Double,
    val imageWidthReductionPx: Int,
) {
    companion object {
        fun from(settings: ReaderSettings): ReaderGeneratedLayout {
            val verticalPaddingBlock = "var(--hoshi-vertical-padding-block, ${(settings.verticalPadding / 2.0).cssNumber()}vh)"
            val continuousBodyPadding = if (settings.verticalWriting) {
                "$verticalPaddingBlock 0"
            } else {
                "0 ${(settings.horizontalPadding / 2.0).cssNumber()}vw"
            }
            val continuousBottomPadding = if (settings.verticalWriting) {
                settings.bottomPaddingCss
            } else {
                "0"
            }
            val columnWidth = if (settings.verticalWriting) {
                "var(--page-height, 100vh)"
            } else {
                "var(--page-width, 100vw)"
            }
            val imageRatio = if (settings.continuousMode && settings.verticalWriting) {
                1.0
            } else {
                settings.imageWidthViewportRatio
            }
            return ReaderGeneratedLayout(
                viewportHorizontalPaddingRatio = settings.continuousViewportHorizontalPaddingRatio,
                viewportVerticalPaddingRatio = settings.continuousViewportVerticalPaddingRatio,
                continuousBodyPaddingCss = continuousBodyPadding,
                continuousBodyBottomPaddingCss = continuousBottomPadding,
                paginatedColumnWidthCss = columnWidth,
                imageWidthViewportRatio = imageRatio,
                imageHeightViewportRatio = settings.imageHeightViewportRatio,
                imageWidthReductionPx = if (settings.verticalWriting) 1 else 0,
            )
        }
    }
}

internal object ReaderContentStyles {
    fun styleTag(
        settings: ReaderSettings = ReaderSettings(),
        fontFaceUrl: String? = null,
        systemDark: Boolean = false,
        sasayakiTextColor: Long = 0xFF000000,
        sasayakiBackgroundColor: Long = 0x6687CEEB,
        contentLanguageProfile: ContentLanguageProfile = ContentLanguageProfile.Default,
        readerCssTemplate: String? = null,
    ): String = "<style>\n${
        css(
            settings = settings,
            fontFaceUrl = fontFaceUrl,
            systemDark = systemDark,
            sasayakiTextColor = sasayakiTextColor,
            sasayakiBackgroundColor = sasayakiBackgroundColor,
            contentLanguageProfile = contentLanguageProfile,
            readerCssTemplate = readerCssTemplate,
        )
    }\n</style>"

    fun css(
        settings: ReaderSettings = ReaderSettings(),
        fontFaceUrl: String? = null,
        systemDark: Boolean = false,
        sasayakiTextColor: Long = 0xFF000000,
        sasayakiBackgroundColor: Long = 0x6687CEEB,
        contentLanguageProfile: ContentLanguageProfile = ContentLanguageProfile.Default,
        readerCssTemplate: String? = null,
    ): String {
        val textColor = settings.textColorCss(systemDark)
        val backgroundColor = settings.backgroundColorCss(systemDark)
        val normalizedFont = settings.selectedFont
        val fontFaceFamily = normalizedFont.cssString()
        val bodyFontFamilyCss = if (ReaderFontManager.isPublisherFont(normalizedFont)) {
            ""
        } else {
            "font-family: ${normalizedFont.readerCssFontFamily(contentLanguageProfile)} !important;"
        }
        val fontFaceCss = fontFaceUrl
            ?.takeUnless { ReaderFontManager.isPublisherFont(normalizedFont) }
            ?.let { url ->
            """
            @font-face {
                font-family: $fontFaceFamily;
                src: url('${url.cssSingleQuotedUrl()}');
            }
            """.trimIndent()
        }.orEmpty()
        val textSpacingCss = if (settings.layoutAdvanced) {
            """
            line-height: ${settings.lineHeight} !important;
            letter-spacing: ${settings.characterSpacing.cssLetterSpacingEm()}em !important;
            """.trimIndent()
        } else {
            ""
        }
        val eInkLineColor = if (settings.usesDarkInterface(systemDark)) "#fff" else "#000"
        val gridCss = if (!settings.justifyText) {
            """
            text-align: start !important;
            hanging-punctuation: allow-end !important;
            line-break: strict !important;
            """.trimIndent()
        } else {
            ""
        }
        val pageBreakCss = if (settings.avoidPageBreak) {
            """
            p {
                break-inside: avoid !important;
                -webkit-column-break-inside: avoid !important;
            }
            """.trimIndent()
        } else {
            ""
        }
        val paragraphSpacingCss = if (settings.layoutAdvanced) {
            if (settings.verticalWriting) {
                """
                p {
                    margin-right: ${settings.paragraphSpacing}em !important;
                    margin-left: ${settings.paragraphSpacing}em !important;
                }
                """.trimIndent()
            } else {
                """
                p {
                    margin-top: ${settings.paragraphSpacing}em !important;
                    margin-bottom: ${settings.paragraphSpacing}em !important;
                }
                """.trimIndent()
            }
        } else {
            ""
        }
        val furiganaCss = if (settings.hideFurigana) {
            """
            rt {
                display: none !important;
            }
            """.trimIndent()
        } else {
            """
            rt {
                font-size: 0.45em;
            }
            """.trimIndent()
        }
        val generatedLayout = ReaderGeneratedLayout.from(settings)
        val visualNovelContentMaxHeight = if (settings.verticalWriting && settings.bottomOverlapPx > 0) {
            "calc(100% - ${settings.bottomOverlapPx}px)"
        } else {
            "100%"
        }
        val layoutCss = when (settings.viewMode) {
            ReaderViewMode.Continuous -> {
                val hiddenOverflowAxis = if (settings.verticalWriting) "overflow-y" else "overflow-x"
                val viewportConstraintCss = if (settings.verticalWriting) {
                    "height: var(--hoshi-continuous-height, 100vh) !important;"
                } else {
                    """
                    width: 100vw !important;
                    min-height: 100vh !important;
                    """.trimIndent()
                }
                """
                html, body {
                    $hiddenOverflowAxis: hidden !important;
                    margin: 0 !important;
                    padding: 0 !important;
                    background: var(--hoshi-background-color) !important;
                    color: var(--hoshi-text-color) !important;
                    writing-mode: ${settings.writingModeCss} !important;
                }
                body {
                    $bodyFontFamilyCss
                    font-size: ${settings.fontSize}px !important;
                    -webkit-text-size-adjust: none !important;
                    $textSpacingCss
                    box-sizing: border-box !important;
                    $viewportConstraintCss
                    padding: ${generatedLayout.continuousBodyPaddingCss} !important;
                    padding-bottom: ${generatedLayout.continuousBodyBottomPaddingCss} !important;
                    $gridCss
                    text-orientation: mixed;
                }
                """.trimIndent()
            }
            ReaderViewMode.VisualNovel -> {
                """
                html, body {
                    overflow: hidden !important;
                    height: var(--page-height, 100vh) !important;
                    width: var(--page-width, 100vw) !important;
                    margin: 0 !important;
                    padding: 0 !important;
                    background: var(--hoshi-background-color) !important;
                    color: var(--hoshi-text-color) !important;
                }
                body {
                    $bodyFontFamilyCss
                    font-size: ${settings.fontSize}px !important;
                    -webkit-text-size-adjust: none !important;
                    $textSpacingCss
                    box-sizing: border-box !important;
                    height: var(--page-height, 100vh) !important;
                    width: var(--page-width, 100vw) !important;
                    padding: 0 !important;
                    $gridCss
                    text-orientation: mixed;
                }
                .hoshi-vn-screen {
                    writing-mode: ${settings.writingModeCss} !important;
                    display: flex !important;
                    align-items: center !important;
                    justify-content: center !important;
                    height: var(--page-height, 100vh) !important;
                    width: var(--page-width, 100vw) !important;
                    padding: ${settings.pagePaddingCss} !important;
                    padding-bottom: ${settings.bottomPaddingCss} !important;
                }
                .hoshi-vn-content {
                    writing-mode: ${settings.writingModeCss} !important;
                    box-sizing: border-box !important;
                    max-width: 100% !important;
                    max-height: $visualNovelContentMaxHeight !important;
                    overflow: visible !important;
                    hanging-punctuation: none !important;
                }
                .hoshi-vn-content * {
                    column-count: auto !important;
                    -webkit-column-count: auto !important;
                }
                .hoshi-vn-content svg {
                    width: var(--hoshi-image-max-width, ${settings.imageMaxWidthFallbackCss}) !important;
                    height: var(--hoshi-image-max-height, ${settings.imageMaxHeightFallbackCss}) !important;
                }
                """.trimIndent()
            }
            ReaderViewMode.Paginated -> {
                """
                html, body {
                    overflow: hidden !important;
                    height: var(--page-height, 100vh) !important;
                    width: var(--page-width, 100vw) !important;
                    margin: 0 !important;
                    padding: 0 !important;
                    background: var(--hoshi-background-color) !important;
                    color: var(--hoshi-text-color) !important;
                    writing-mode: ${settings.writingModeCss} !important;
                }
                body {
                    $bodyFontFamilyCss
                    font-size: ${settings.fontSize}px !important;
                    -webkit-text-size-adjust: none !important;
                    $textSpacingCss
                    box-sizing: border-box !important;
                    column-width: ${generatedLayout.paginatedColumnWidthCss} !important;
                    column-gap: ${settings.columnGapCss};
                    padding: ${settings.pagePaddingCss} !important;
                    padding-bottom: ${settings.bottomPaddingCss} !important;
                    $gridCss
                    text-orientation: mixed;
                }
                body * {
                    column-count: auto !important;
                    -webkit-column-count: auto !important;
                }
                body, body * {
                    orphans: 1 !important;
                    widows: 1 !important;
                }
                """.trimIndent()
            }
        }
        return (readerCssTemplate ?: ReaderCssTemplateSource.value)
            .replace("__HOSHI_FONT_FACE_CSS__", fontFaceCss)
            .replace("__HOSHI_PAGE_BREAK_CSS__", pageBreakCss)
            .replace("__HOSHI_PARAGRAPH_SPACING_CSS__", paragraphSpacingCss)
            .replace("__HOSHI_BACKGROUND_COLOR__", backgroundColor)
            .replace("__HOSHI_TEXT_COLOR__", textColor)
            .replace("__HOSHI_EINK_LINE_COLOR__", eInkLineColor)
            .replace("__HOSHI_READER_EINK_MODE__", if (settings.eInkMode) "1" else "0")
            .replace("__HOSHI_READER_VERTICAL_WRITING__", if (settings.verticalWriting) "1" else "0")
            .replace("__HOSHI_SASAYAKI_TEXT_COLOR__", sasayakiTextColor.toReaderCssColor())
            .replace(
                "__HOSHI_SASAYAKI_BACKGROUND_COLOR__",
                sasayakiBackgroundColor.toReaderCssColor(includeAlpha = true),
            )
            .replace("__HOSHI_LAYOUT_CSS__", layoutCss)
            .replace("__HOSHI_IMAGE_MAX_WIDTH_FALLBACK__", settings.imageMaxWidthFallbackCss)
            .replace("__HOSHI_IMAGE_MAX_HEIGHT_FALLBACK__", settings.imageMaxHeightFallbackCss)
            .replace("__HOSHI_FURIGANA_CSS__", furiganaCss)
            .replace(
                "__HOSHI_HIGHLIGHT_COLOR_CSS__",
                HighlightColor.entries.joinToString("\n") {
                    ".hoshi-highlight-${it.rawValue} { background-color: ${it.cssBackground} !important; }"
                },
            )
    }
}

private object ReaderCssTemplateSource {
    val value: String by lazy {
        val candidates = listOf(
            File("app/src/main/assets/hoshi-web/reader/reader.css"),
            File("src/main/assets/hoshi-web/reader/reader.css"),
        )
        candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Reader CSS asset not found in source tree.")
    }
}

private fun String.cssString(): String =
    "'${replace("\\", "\\\\").replace("'", "\\'")}'"

private fun String.readerCssFontFamily(contentLanguageProfile: ContentLanguageProfile): String = when (this) {
    ReaderFontManager.defaultMinchoFont ->
        contentLanguageProfile.readerSerifFontFamilyCss
    ReaderFontManager.defaultGothicFont ->
        contentLanguageProfile.readerSansSerifFontFamilyCss
    else ->
        "${cssString()}, ${contentLanguageProfile.readerSerifFontFamilyCss}"
}

private fun String.cssSingleQuotedUrl(): String =
    replace("\\", "\\\\").replace("'", "\\'")

private fun Double.cssLetterSpacingEm(): String =
    String.format(java.util.Locale.US, "%.2f", this / 100.0)

internal fun Long.toReaderCssColor(includeAlpha: Boolean = false): String = when {
    includeAlpha && (this ushr 24) != 0xFFL -> {
        val alpha = (this ushr 24) and 0xFF
        val rgb = this and 0xFFFFFF
        "#${rgb.toString(16).padStart(6, '0')}${alpha.toString(16).padStart(2, '0')}"
    }
    this == 0xFF000000 -> "#000"
    this == 0xFFFFFFFF -> "#fff"
    this == 0xFFF2E2C9 -> "#F2E2C9"
    else -> "#${(this and 0xFFFFFF).toString(16).padStart(6, '0')}"
}
