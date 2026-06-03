package moe.antimony.hoshi.features.reader

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
    ): String = "<style>\n${css(settings, fontFaceUrl, systemDark)}\n</style>"

    fun css(
        settings: ReaderSettings = ReaderSettings(),
        fontFaceUrl: String? = null,
        systemDark: Boolean = false,
        sasayakiTextColor: Long = 0xFF000000,
        sasayakiBackgroundColor: Long = 0x6687CEEB,
    ): String {
        val textColor = settings.textColorCss(systemDark)
        val backgroundColor = settings.backgroundColorCss(systemDark)
        val normalizedFont = ReaderFontManager.normalizeDefaultFont(settings.selectedFont)
        val fontFaceFamily = normalizedFont.cssString()
        val bodyFontFamily = normalizedFont.readerCssFontFamily()
        val fontFaceCss = fontFaceUrl?.let { url ->
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
        val selectionHighlightCss = if (settings.eInkMode) {
            """
        ::highlight(hoshi-selection) {
            background-color: transparent !important;
            color: inherit;
            text-decoration-line: underline;
            text-decoration-color: var(--hoshi-eink-line-color);
            text-decoration-thickness: 1.5px;
            text-underline-offset: 2px;
        }
            """.trimIndent()
        } else {
            """
        ::highlight(hoshi-selection) {
            background-color: rgba(160, 160, 160, 0.4) !important;
            color: inherit;
        }
            """.trimIndent()
        }
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
        val sasayakiHighlightCss = """
        html[data-hoshi-reader-eink-mode="true"] ::highlight(hoshi-sasayaki) {
            color: inherit !important;
            background-color: transparent !important;
        }
        html[data-hoshi-reader-eink-mode="true"] ruby.hoshi-sasayaki-ruby-active {
            color: inherit !important;
            background-color: transparent !important;
        }
        html[data-hoshi-reader-eink-mode="true"] .hoshi-sasayaki-cue.hoshi-sasayaki-active {
            color: inherit !important;
            background-color: transparent !important;
        }
        html:not([data-hoshi-reader-eink-mode="true"]) ::highlight(hoshi-sasayaki) {
            color: var(--hoshi-sasayaki-text-color) !important;
            background-color: var(--hoshi-sasayaki-background-color) !important;
        }
        html:not([data-hoshi-reader-eink-mode="true"]) ruby.hoshi-sasayaki-ruby-active {
            color: var(--hoshi-sasayaki-text-color) !important;
            background-color: var(--hoshi-sasayaki-background-color) !important;
        }
        html:not([data-hoshi-reader-eink-mode="true"]) .hoshi-sasayaki-cue.hoshi-sasayaki-active {
            color: var(--hoshi-sasayaki-text-color) !important;
            background-color: var(--hoshi-sasayaki-background-color) !important;
        }
        """.trimIndent()
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
        val layoutCss = if (settings.continuousMode) {
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
                font-family: $bodyFontFamily !important;
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
        } else {
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
                font-family: $bodyFontFamily !important;
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
        return """
        $fontFaceCss
        $pageBreakCss
        $paragraphSpacingCss
        @media (prefers-color-scheme: light) { :root { --hoshi-system-text-color: #000; } }
        @media (prefers-color-scheme: dark) { :root { --hoshi-system-text-color: #fff; } }
        :root {
            --hoshi-background-color: $backgroundColor;
            --hoshi-text-color: $textColor;
            --hoshi-eink-line-color: $eInkLineColor;
            --hoshi-reader-eink-mode: ${if (settings.eInkMode) "1" else "0"};
            --hoshi-reader-vertical-writing: ${if (settings.verticalWriting) "1" else "0"};
            --hoshi-sasayaki-text-color: ${sasayakiTextColor.toReaderCssColor()};
            --hoshi-sasayaki-background-color: ${sasayakiBackgroundColor.toReaderCssColor(includeAlpha = true)};
        }
        html {
            -webkit-line-box-contain: block glyphs replaced;
        }
        $layoutCss
        img.block-img {
            max-width: var(--hoshi-image-max-width, ${settings.imageMaxWidthFallbackCss}) !important;
            max-height: var(--hoshi-image-max-height, ${settings.imageMaxHeightFallbackCss}) !important;
            width: auto !important;
            height: auto !important;
            display: block !important;
            margin: auto !important;
            break-inside: avoid !important;
            -webkit-column-break-inside: avoid !important;
            object-fit: contain !important;
        }
        img.block-img.blurred,
        svg.blurred {
            filter: blur(24px) !important;
            clip-path: inset(0);
        }
        .blur-wrapper {
            display: table;
            margin: auto;
            line-height: 0;
            overflow: hidden;
        }
        svg {
            max-width: var(--hoshi-image-max-width, ${settings.imageMaxWidthFallbackCss}) !important;
            max-height: var(--hoshi-image-max-height, ${settings.imageMaxHeightFallbackCss}) !important;
            width: 100% !important;
            height: 100% !important;
            display: block !important;
            margin: auto !important;
            break-inside: avoid !important;
            -webkit-column-break-inside: avoid !important;
        }
        $furiganaCss
        ruby > rt, ruby > rp {
            -webkit-user-select: none;
            user-select: none;
        }
        $selectionHighlightCss
        $sasayakiHighlightCss
        ${HighlightColor.entries.joinToString("\n") { ".hoshi-highlight-${it.rawValue} { background-color: ${it.cssBackground} !important; }" }}
        a {
            color: rgba(66, 108, 245, 1) !important;
        }
        """.trimIndent()
    }
}

private fun String.cssString(): String =
    "'${replace("\\", "\\\\").replace("'", "\\'")}'"

private fun String.readerCssFontFamily(): String = when (this) {
    ReaderFontManager.defaultMinchoFont ->
        "${cssString()}, 'NotoSerifCJKjp-Regular', serif"
    ReaderFontManager.defaultGothicFont ->
        "${cssString()}, 'NotoSansCJKJP-Regular', sans-serif"
    else ->
        "${cssString()}, serif"
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
