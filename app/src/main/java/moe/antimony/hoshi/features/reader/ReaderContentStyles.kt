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
    ): String {
        val textColor = settings.textColorCss ?: "var(--hoshi-system-text-color)"
        val backgroundColor = when (settings.theme) {
            ReaderTheme.Dark -> "#000"
            ReaderTheme.Sepia -> "#F2E2C9"
            else -> "#fff"
        }
        val fontFamily = settings.selectedFont.cssString()
        val fontFaceCss = fontFaceUrl?.let { url ->
            """
            @font-face {
                font-family: $fontFamily;
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
        <style>
        $fontFaceCss
        $pageBreakCss
        @media (prefers-color-scheme: light) { :root { --hoshi-system-text-color: #000; } }
        @media (prefers-color-scheme: dark) { :root { --hoshi-system-text-color: #fff; } }
        * {
            max-width: 100% !important;
            box-sizing: border-box !important;
        }
        html, body {
            overflow: hidden !important;
            width: var(--page-width, 100vw) !important;
            margin: 0 !important;
            padding: 0 !important;
            background: $backgroundColor !important;
            color: $textColor !important;
            writing-mode: ${settings.writingModeCss} !important;
        }
        html {
            height: var(--page-height, 100vh) !important;
        }
        body {
            height: ${settings.bodyHeightCss} !important;
            font-family: $fontFamily, serif !important;
            font-size: ${settings.fontSize}px !important;
            $textSpacingCss
            box-sizing: border-box !important;
            column-width: ${settings.columnWidthCss} !important;
            column-gap: ${settings.columnGapCss};
            column-fill: auto !important;
            -webkit-column-fill: auto !important;
            overflow-wrap: anywhere !important;
            word-break: normal !important;
            orphans: 1;
            widows: 1;
            padding: ${settings.pagePaddingCss} !important;
            padding-bottom: ${settings.bottomPaddingCss} !important;
            $gridCss
            text-orientation: mixed;
        }
        p, div, span, li {
            break-inside: auto !important;
            -webkit-column-break-inside: auto !important;
            overflow-wrap: anywhere !important;
            word-break: normal !important;
        }
        pre, code {
            white-space: pre-wrap !important;
            overflow-wrap: anywhere !important;
        }
        table {
            table-layout: fixed !important;
            width: 100% !important;
            overflow-wrap: anywhere !important;
        }
        img.block-img {
            max-width: var(--hoshi-image-max-width, ${settings.imageMaxWidthFallbackCss}) !important;
            max-height: var(--hoshi-image-max-height, ${settings.imageMaxHeightFallbackCss}) !important;
            width: var(--hoshi-image-max-width, ${settings.imageMaxWidthFallbackCss}) !important;
            height: var(--hoshi-image-max-height, ${settings.imageMaxHeightFallbackCss}) !important;
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
        a {
            color: rgba(66, 108, 245, 1) !important;
        }
        </style>
        """.trimIndent()
    }
}

private fun String.cssString(): String =
    "'${replace("\\", "\\\\").replace("'", "\\'")}'"

private fun String.cssSingleQuotedUrl(): String =
    replace("\\", "\\\\").replace("'", "\\'")

private fun Double.cssLetterSpacingEm(): String =
    String.format(java.util.Locale.US, "%.2f", this / 100.0)
