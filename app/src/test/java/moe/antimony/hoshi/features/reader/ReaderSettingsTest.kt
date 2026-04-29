package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSettingsTest {
    @Test
    fun defaultsMatchIosUserConfigFirstRunValues() {
        val settings = ReaderSettings()

        assertEquals(true, settings.verticalWriting)
        assertEquals(22, settings.fontSize)
        assertEquals(5, settings.horizontalPadding)
        assertEquals(0, settings.verticalPadding)
        assertEquals(1.65, settings.lineHeight, 0.0)
        assertEquals("Hiragino Mincho ProN", settings.selectedFont)
    }

    @Test
    fun readerCssUsesSettingsValues() {
        val settings = ReaderSettings(
            verticalWriting = true,
            fontSize = 28,
            selectedFont = "KleeOne-SemiBold",
            horizontalPadding = 12,
            verticalPadding = 8,
            layoutAdvanced = true,
            lineHeight = 1.85,
            characterSpacing = 2.0,
        )

        val css = ReaderContentStyles.styleTag(
            settings = settings,
            fontFaceUrl = "https://hoshi.local/fonts/KleeOne-SemiBold.ttf",
        )

        assertTrue(css.contains("@font-face"))
        assertTrue(css.contains("font-family: 'KleeOne-SemiBold';"))
        assertTrue(css.contains("src: url('https://hoshi.local/fonts/KleeOne-SemiBold.ttf');"))
        assertTrue(css.contains("writing-mode: vertical-rl !important;"))
        assertTrue(css.contains("font-family: 'KleeOne-SemiBold', serif !important;"))
        assertTrue(css.contains("font-size: 28px !important;"))
        assertTrue(css.contains("line-height: 1.85 !important;"))
        assertTrue(css.contains("letter-spacing: 0.02em !important;"))
        assertTrue(css.contains("column-gap: calc(8vh + 28px);"))
        assertTrue(css.contains("padding: 4.0vh 6.0vw !important;"))
        assertTrue(css.contains("padding-bottom: calc(4.0vh + 28px) !important;"))
    }

    @Test
    fun horizontalReaderCssUsesIosWritingModeMapping() {
        val css = ReaderContentStyles.styleTag(ReaderSettings(verticalWriting = false))

        assertTrue(css.contains("writing-mode: horizontal-tb !important;"))
    }

    @Test
    fun readerCssPreservesImageAspectRatio() {
        val css = ReaderContentStyles.styleTag()
        val imageCss = css.substringAfter("img.block-img {").substringBefore("}")
        val svgCss = css.substringAfter("svg {").substringBefore("}")

        assertTrue(css.contains("img.block-img"))
        assertTrue(imageCss.contains("max-width: var(--hoshi-image-max-width"))
        assertTrue(imageCss.contains("max-height: var(--hoshi-image-max-height"))
        assertTrue(imageCss.contains("width: auto !important;"))
        assertTrue(imageCss.contains("height: auto !important;"))
        assertTrue(imageCss.contains("object-fit: contain !important;"))
        assertFalse(imageCss.contains("\n            width: var(--hoshi-image-max-width"))
        assertFalse(imageCss.contains("\n            height: var(--hoshi-image-max-height"))
        assertTrue(svgCss.contains("width: auto !important;"))
        assertTrue(svgCss.contains("height: auto !important;"))
        assertFalse(svgCss.contains("width: 100% !important;"))
        assertFalse(svgCss.contains("height: 100% !important;"))
    }

    @Test
    fun appInterfaceThemeMatchesIosColorSchemeMapping() {
        assertFalse(ReaderSettings(theme = ReaderTheme.Light).usesDarkInterface(systemDark = true))
        assertTrue(ReaderSettings(theme = ReaderTheme.Dark).usesDarkInterface(systemDark = false))
        assertFalse(ReaderSettings(theme = ReaderTheme.Sepia).usesDarkInterface(systemDark = true))
        assertTrue(ReaderSettings(theme = ReaderTheme.System).usesDarkInterface(systemDark = true))
        assertFalse(ReaderSettings(theme = ReaderTheme.System).usesDarkInterface(systemDark = false))
    }

    @Test
    fun readerCssUsesIosAppearanceFlags() {
        val css = ReaderContentStyles.styleTag(
            ReaderSettings(
                hideFurigana = true,
                avoidPageBreak = true,
                justifyText = true,
            ),
        )

        assertTrue(css.contains("display: none !important;"))
        assertTrue(css.contains("break-inside: avoid !important;"))
        assertFalse(css.contains("text-align: start !important;"))
    }

    @Test
    fun twoOptionAppearanceSegmentsReserveEnoughWidthForBottomLabel() {
        assertEquals(180, segmentedControlWidthDp(optionCount = 2))
    }
}
