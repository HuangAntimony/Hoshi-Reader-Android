package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderSettingsTest {
    @Test
    fun defaultsMatchIosUserConfigFirstRunValuesWithAndroidFontPreset() {
        val settings = ReaderSettings()

        assertFalse(settings.eInkMode)
        assertEquals(true, settings.verticalWriting)
        assertEquals(22, settings.fontSize)
        assertEquals(5, settings.horizontalPadding)
        assertEquals(0, settings.verticalPadding)
        assertEquals(1.65, settings.lineHeight, 0.0)
        assertEquals("Noto Serif CJK JP", settings.selectedFont)
        assertFalse(settings.sepiaInvertInDark)
        assertFalse(settings.continuousMode)
        assertEquals(20, settings.chapterSwipeDistance)
        assertTrue(settings.popupSwipeToDismiss)
        assertEquals(30, settings.popupSwipeThreshold)
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
        assertTrue(css.contains("column-gap: calc(var(--hoshi-vertical-padding-gap, 8vh) + 28px);"))
        assertTrue(css.contains("padding: var(--hoshi-vertical-padding-block, 4.0vh) 6.0vw !important;"))
        assertTrue(css.contains("padding-bottom: calc(var(--hoshi-vertical-padding-block, 4.0vh) + 28px) !important;"))
    }

    @Test
    fun readerCssMapsAndroidJapanesePresetsToSystemFallbackFamilies() {
        val minchoCss = ReaderContentStyles.styleTag(
            ReaderSettings(selectedFont = "Noto Serif CJK JP"),
        )
        val gothicCss = ReaderContentStyles.styleTag(
            ReaderSettings(selectedFont = "Noto Sans CJK JP"),
        )

        assertTrue(
            minchoCss.contains(
                "font-family: 'Noto Serif CJK JP', 'NotoSerifCJKjp-Regular', serif !important;",
            ),
        )
        assertTrue(
            gothicCss.contains(
                "font-family: 'Noto Sans CJK JP', 'NotoSansCJKJP-Regular', sans-serif !important;",
            ),
        )
    }

    @Test
    fun readerCssMigratesLegacyIosPresetNamesToAndroidJapaneseFallbacks() {
        val legacyMinchoCss = ReaderContentStyles.styleTag(
            ReaderSettings(selectedFont = "Hiragino Mincho ProN"),
        )
        val legacyGothicCss = ReaderContentStyles.styleTag(
            ReaderSettings(selectedFont = "Hiragino Kaku Gothic ProN"),
        )

        assertTrue(legacyMinchoCss.contains("'Noto Serif CJK JP'"))
        assertFalse(legacyMinchoCss.contains("Hiragino Mincho ProN"))
        assertTrue(legacyGothicCss.contains("'Noto Sans CJK JP'"))
        assertFalse(legacyGothicCss.contains("Hiragino Kaku Gothic ProN"))
    }

    @Test
    fun horizontalReaderCssUsesIosWritingModeMapping() {
        val css = ReaderContentStyles.styleTag(ReaderSettings(verticalWriting = false))

        assertTrue(css.contains("writing-mode: horizontal-tb !important;"))
    }

    @Test
    fun continuousReaderCssUsesScrollableIosLayoutInsteadOfPagedColumns() {
        val css = ReaderContentStyles.styleTag(ReaderSettings(continuousMode = true))

        assertTrue(css.contains("overflow-y: hidden !important;"))
        assertTrue(css.lines().any { it.trim() == "height: var(--hoshi-continuous-height, 100vh) !important;" })
        assertFalse(css.contains("overflow: hidden !important;"))
        assertFalse(css.contains("height: var(--page-height, 100vh) !important;"))
        assertFalse(css.contains("column-width: var(--page-width, 100vw) !important;"))
        assertFalse(css.contains("column-gap:"))
    }

    @Test
    fun verticalContinuousReaderUsesHorizontalPaddingAsViewportInset() {
        assertEquals(
            0.125,
            ReaderSettings(
                continuousMode = true,
                verticalWriting = true,
                horizontalPadding = 25,
            ).continuousViewportHorizontalPaddingRatio,
            0.0,
        )
        assertEquals(
            0.0,
            ReaderSettings(
                continuousMode = true,
                verticalWriting = false,
                horizontalPadding = 25,
            ).continuousViewportHorizontalPaddingRatio,
            0.0,
        )
        assertEquals(
            0.0,
            ReaderSettings(
                continuousMode = false,
                verticalWriting = true,
                horizontalPadding = 25,
            ).continuousViewportHorizontalPaddingRatio,
            0.0,
        )
    }

    @Test
    fun horizontalContinuousReaderUsesVerticalPaddingAsViewportInset() {
        assertEquals(
            0.16,
            ReaderSettings(
                continuousMode = true,
                verticalWriting = false,
                verticalPadding = 32,
            ).continuousViewportVerticalPaddingRatio,
            0.0,
        )
        assertEquals(
            0.0,
            ReaderSettings(
                continuousMode = true,
                verticalWriting = true,
                verticalPadding = 32,
            ).continuousViewportVerticalPaddingRatio,
            0.0,
        )
        assertEquals(
            0.0,
            ReaderSettings(
                continuousMode = false,
                verticalWriting = false,
                verticalPadding = 32,
            ).continuousViewportVerticalPaddingRatio,
            0.0,
        )
    }

    @Test
    fun appInterfaceThemeMatchesIosColorSchemeMapping() {
        assertFalse(ReaderSettings(theme = ReaderTheme.Light).usesDarkInterface(systemDark = true))
        assertTrue(ReaderSettings(theme = ReaderTheme.Dark).usesDarkInterface(systemDark = false))
        assertFalse(ReaderSettings(theme = ReaderTheme.Sepia).usesDarkInterface(systemDark = true))
        assertTrue(
            ReaderSettings(
                theme = ReaderTheme.Sepia,
                sepiaInvertInDark = true,
            ).usesDarkInterface(systemDark = true),
        )
        assertFalse(
            ReaderSettings(
                theme = ReaderTheme.Sepia,
                sepiaInvertInDark = true,
            ).usesDarkInterface(systemDark = false),
        )
        assertTrue(ReaderSettings(theme = ReaderTheme.System).usesDarkInterface(systemDark = true))
        assertFalse(ReaderSettings(theme = ReaderTheme.System).usesDarkInterface(systemDark = false))
    }

    @Test
    fun systemReaderThemeResolvesContentColorsFromSystemDarkMode() {
        val settings = ReaderSettings(theme = ReaderTheme.System)

        assertEquals(0xFF000000, settings.backgroundColor(systemDark = true))
        assertEquals(0xFFFFFFFF, settings.backgroundColor(systemDark = false))
        assertEquals("#fff", settings.textColorCss(systemDark = true))
        assertEquals("#000", settings.textColorCss(systemDark = false))
    }

    @Test
    fun systemReaderCssUsesDarkColorsWhenSystemIsDark() {
        val css = ReaderContentStyles.styleTag(
            settings = ReaderSettings(theme = ReaderTheme.System),
            systemDark = true,
        )

        assertTrue(css.contains("background: #000 !important;"))
        assertTrue(css.contains("color: #fff !important;"))
    }

    @Test
    fun eInkModeForcesReaderContentToPureBlackAndWhite() {
        val light = ReaderSettings(theme = ReaderTheme.Sepia, eInkMode = true)
        val dark = ReaderSettings(theme = ReaderTheme.Dark, eInkMode = true)

        assertEquals(0xFFFFFFFF, light.backgroundColor(systemDark = false))
        assertEquals("#000", light.textColorCss(systemDark = false))
        assertEquals(0xFF000000, dark.backgroundColor(systemDark = false))
        assertEquals("#fff", dark.textColorCss(systemDark = false))
    }

    @Test
    fun sepiaCanInvertReaderColorsInSystemDarkModeLikeIos() {
        val settings = ReaderSettings(theme = ReaderTheme.Sepia, sepiaInvertInDark = true)

        assertEquals(0xFF18150C, settings.backgroundColor(systemDark = true))
        assertEquals("#F2E2C9", settings.textColorCss(systemDark = true))
        assertEquals(0xFFF2E2C9, settings.backgroundColor(systemDark = false))
        assertEquals("#332A1B", settings.textColorCss(systemDark = false))
    }

    @Test
    fun readerCssIncludesIosWebKitSelectionAndSizingRules() {
        val css = ReaderContentStyles.styleTag()

        assertTrue(css.contains("-webkit-line-box-contain: block glyphs replaced;"))
        assertTrue(css.contains("-webkit-text-size-adjust: none !important;"))
        assertTrue(css.contains("ruby > rt, ruby > rp"))
        assertTrue(css.contains("-webkit-user-select: none;"))
        assertTrue(css.contains("user-select: none;"))
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
