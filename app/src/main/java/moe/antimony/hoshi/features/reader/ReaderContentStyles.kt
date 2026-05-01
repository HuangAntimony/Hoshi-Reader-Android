package moe.antimony.hoshi.features.reader

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
        val backgroundColor = settings.backgroundColor(systemDark).toReaderCssColor()
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
        return """
        $fontFaceCss
        $pageBreakCss
        @media (prefers-color-scheme: light) { :root { --hoshi-system-text-color: #000; } }
        @media (prefers-color-scheme: dark) { :root { --hoshi-system-text-color: #fff; } }
        :root {
            --hoshi-sasayaki-text-color: ${sasayakiTextColor.toReaderCssColor()};
            --hoshi-sasayaki-background-color: ${sasayakiBackgroundColor.toReaderCssColor(includeAlpha = true)};
        }
        html, body {
            overflow: hidden !important;
            height: var(--page-height, 100vh) !important;
            width: var(--page-width, 100vw) !important;
            margin: 0 !important;
            padding: 0 !important;
            background: $backgroundColor !important;
            color: $textColor !important;
            writing-mode: ${settings.writingModeCss} !important;
        }
        body {
            font-family: $bodyFontFamily !important;
            font-size: ${settings.fontSize}px !important;
            $textSpacingCss
            box-sizing: border-box !important;
            column-width: var(--page-width, 100vw) !important;
            column-gap: ${settings.columnGapCss};
            padding: ${settings.pagePaddingCss} !important;
            padding-bottom: ${settings.bottomPaddingCss} !important;
            $gridCss
            text-orientation: mixed;
        }
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
        ::highlight(hoshi-selection) {
            background-color: rgba(160, 160, 160, 0.4) !important;
            color: inherit;
        }
        .hoshi-sasayaki-cue.hoshi-sasayaki-active {
            color: var(--hoshi-sasayaki-text-color) !important;
            background-color: var(--hoshi-sasayaki-background-color) !important;
        }
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

private fun Long.toReaderCssColor(includeAlpha: Boolean = false): String = when {
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
