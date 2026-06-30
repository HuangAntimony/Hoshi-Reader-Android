package moe.antimony.hoshi.features.reader

import androidx.compose.ui.graphics.Color
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
        assertEquals(18, settings.bottomSafeAreaDp)
        assertEquals(1.65, settings.lineHeight, 0.0)
        assertEquals(0.0, settings.paragraphSpacing, 0.0)
        assertEquals("Noto Serif CJK JP", settings.selectedFont)
        assertFalse(settings.systemLightSepia)
        assertFalse(settings.sepiaInvertInDark)
        assertEquals(ReaderInterfaceTheme.System, settings.uiTheme)
        assertEquals(0xFFFFFFFFL, settings.customBackgroundColor)
        assertEquals(0xFF000000L, settings.customTextColor)
        assertEquals(0xFF999999L, settings.customInfoColor)
        assertFalse(settings.continuousMode)
        assertFalse(settings.blurImages)
        assertFalse(settings.enableStatistics)
        assertTrue(settings.showStatisticsTab)
        assertEquals(StatisticsAutostartMode.Off, settings.statisticsAutostartMode)
        assertFalse(settings.showStatisticsToggle)
        assertFalse(settings.showReadingSpeed)
        assertFalse(settings.showReadingTime)
        assertEquals(20, settings.chapterSwipeDistance)
        assertTrue(settings.popupSwipeToDismiss)
        assertEquals(30, settings.popupSwipeThreshold)
        assertFalse(settings.openLastReadBookOnLaunch)
    }

    @Test
    fun statisticsAutostartModesUseIosRawLabels() {
        assertEquals("Off", StatisticsAutostartMode.Off.rawValue)
        assertEquals("Page Turn", StatisticsAutostartMode.PageTurn.rawValue)
        assertEquals("On", StatisticsAutostartMode.On.rawValue)
    }

    @Test
    fun enablingStatisticsTurnsOnReaderDisplayStatisticsControls() {
        val settings = ReaderSettings(
            enableStatistics = false,
            showStatisticsToggle = false,
            showReadingSpeed = false,
            showReadingTime = false,
        )

        val enabled = settings.withStatisticsEnabled(true)

        assertTrue(enabled.enableStatistics)
        assertTrue(enabled.showStatisticsToggle)
        assertTrue(enabled.showReadingSpeed)
        assertTrue(enabled.showReadingTime)
    }

    @Test
    fun statisticsDisplayControlsRemainUserControlledAfterAlreadyEnabled() {
        val settings = ReaderSettings(
            enableStatistics = true,
            showStatisticsToggle = false,
            showReadingSpeed = false,
            showReadingTime = false,
        )

        val enabled = settings.withStatisticsEnabled(true)

        assertTrue(enabled.enableStatistics)
        assertFalse(enabled.showStatisticsToggle)
        assertFalse(enabled.showReadingSpeed)
        assertFalse(enabled.showReadingTime)
    }

    @Test
    fun statisticsTabVisibilityRemainsUserControlledWhenEnablingStatistics() {
        val settings = ReaderSettings(
            enableStatistics = false,
            showStatisticsTab = false,
        )

        val enabled = settings.withStatisticsEnabled(true)

        assertTrue(enabled.enableStatistics)
        assertFalse(enabled.showStatisticsTab)
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
            paragraphSpacing = 1.4,
        )

        val css = ReaderContentStyles.styleTag(
            settings = settings,
            fontFaceUrl = "https://appassets.androidplatform.net/fonts/KleeOne-SemiBold.ttf",
        )

        assertTrue(css.contains("@font-face"))
        assertTrue(css.contains("font-family: 'KleeOne-SemiBold';"))
        assertTrue(css.contains("src: url('https://appassets.androidplatform.net/fonts/KleeOne-SemiBold.ttf');"))
        assertTrue(css.contains("writing-mode: vertical-rl !important;"))
        assertTrue(css.contains("font-family: 'KleeOne-SemiBold', 'Noto Serif CJK JP', 'NotoSerifCJKjp-Regular', serif !important;"))
        assertTrue(css.contains("font-size: 28px !important;"))
        assertTrue(css.contains("line-height: 1.85 !important;"))
        assertTrue(css.contains("letter-spacing: 0.02em !important;"))
        assertTrue(css.contains("margin-right: 1.4em !important;"))
        assertTrue(css.contains("margin-left: 1.4em !important;"))
        assertFalse(css.contains("margin-top: 1.4em !important;"))
        assertFalse(css.contains("margin-bottom: 1.4em !important;"))
        assertTrue(css.contains("column-gap: calc(var(--hoshi-vertical-padding-gap, 8vh) + 28px);"))
        assertTrue(css.contains("padding: var(--hoshi-vertical-padding-block, 4.0vh) 6.0vw !important;"))
        assertTrue(css.contains("padding-bottom: calc(var(--hoshi-vertical-padding-block, 4.0vh) + 28px) !important;"))
    }

    @Test
    fun readerViewportCssLayoutPreseedsAndroidWebViewLayoutVariables() {
        val layout = readerViewportCssLayout(
            settings = ReaderSettings(
                verticalWriting = true,
                fontSize = 22,
                horizontalPadding = 12,
                verticalPadding = 10,
            ),
            viewportCssWidth = 360,
            viewportCssHeight = 720,
        )

        assertEquals(742, layout.pageHeightPx)
        assertEquals(720, layout.visibleHeightPx)
        assertEquals(360, layout.pageWidthPx)
        assertEquals(36.0, layout.verticalPaddingBlockPx, 0.0)
        assertEquals(72.0, layout.verticalPaddingGapPx, 0.0)
        assertEquals(315, layout.imageMaxWidthPx)
        assertEquals(648, layout.imageMaxHeightPx)

        val css = layout.cssVariables()
        assertTrue(css.contains("--page-height: 742px;"))
        assertTrue(css.contains("--hoshi-reader-visible-height: 720px;"))
        assertTrue(css.contains("--page-width: 360px;"))
        assertTrue(css.contains("--hoshi-vertical-padding-block: 36.0px;"))
        assertTrue(css.contains("--hoshi-vertical-padding-gap: 72.0px;"))
        assertTrue(css.contains("--hoshi-image-max-width: 315px;"))
        assertTrue(css.contains("--hoshi-image-max-height: 648px;"))
    }

    @Test
    fun horizontalReaderCssAppliesAdvancedParagraphSpacingToBlockMargins() {
        val css = ReaderContentStyles.styleTag(
            ReaderSettings(
                verticalWriting = false,
                layoutAdvanced = true,
                paragraphSpacing = 2.3,
            ),
        )

        assertTrue(css.contains("margin-top: 2.3em !important;"))
        assertTrue(css.contains("margin-bottom: 2.3em !important;"))
        assertFalse(css.contains("margin-right: 2.3em !important;"))
        assertFalse(css.contains("margin-left: 2.3em !important;"))
    }

    @Test
    fun readerCssOmitsParagraphSpacingWhenAdvancedLayoutIsOff() {
        val css = ReaderContentStyles.styleTag(
            ReaderSettings(
                layoutAdvanced = false,
                paragraphSpacing = 2.3,
            ),
        )

        assertFalse(css.contains("margin-right: 2.3em !important;"))
        assertFalse(css.contains("margin-left: 2.3em !important;"))
        assertFalse(css.contains("margin-top: 2.3em !important;"))
        assertFalse(css.contains("margin-bottom: 2.3em !important;"))
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
    fun readerCssUsesFixedJapaneseContentFallbackFamilyForCustomFonts() {
        val css = ReaderContentStyles.styleTag(
            settings = ReaderSettings(selectedFont = "KleeOne-SemiBold"),
        )

        assertTrue(
            css.contains(
                "font-family: 'KleeOne-SemiBold', 'Noto Serif CJK JP', 'NotoSerifCJKjp-Regular', serif !important;",
            ),
        )
    }

    @Test
    fun publisherFontReaderCssKeepsEpubFontFamily() {
        val css = ReaderContentStyles.styleTag(
            settings = ReaderSettings(selectedFont = ReaderFontManager.publisherFont),
            fontFaceUrl = "https://appassets.androidplatform.net/fonts/ignored.ttf",
        )

        assertFalse(css.contains("@font-face"))
        assertFalse(css.contains("font-family:"))
        assertTrue(css.contains("font-size: 22px !important;"))
        assertTrue(css.contains("writing-mode: vertical-rl !important;"))
    }

    @Test
    fun horizontalReaderCssUsesIosWritingModeMapping() {
        val css = ReaderContentStyles.styleTag(ReaderSettings(verticalWriting = false))

        assertTrue(css.contains("writing-mode: horizontal-tb !important;"))
    }

    @Test
    fun continuousReaderCssUsesScrollableIosLayoutInsteadOfPagedColumns() {
        val css = ReaderContentStyles.styleTag(ReaderSettings(viewMode = ReaderViewMode.Continuous))

        assertTrue(css.contains("overflow-y: hidden !important;"))
        assertTrue(css.lines().any { it.trim() == "height: var(--hoshi-continuous-height, 100vh) !important;" })
        assertFalse(css.contains("overflow: hidden !important;"))
        assertFalse(css.contains("height: var(--page-height, 100vh) !important;"))
        assertFalse(css.contains("column-width: var(--page-width, 100vw) !important;"))
        assertFalse(css.contains("column-gap:"))
    }

    @Test
    fun verticalContinuousLayoutUsesOnlyConfiguredVerticalBottomPadding() {
        val layout = ReaderGeneratedLayout.from(
            ReaderSettings(
                viewMode = ReaderViewMode.Continuous,
                verticalWriting = true,
                horizontalPadding = 24,
                verticalPadding = 10,
                fontSize = 28,
            ),
        )

        assertEquals(0.12, layout.viewportHorizontalPaddingRatio, 0.0)
        assertEquals(0.0, layout.viewportVerticalPaddingRatio, 0.0)
        assertEquals("var(--hoshi-vertical-padding-block, 5.0vh) 0", layout.continuousBodyPaddingCss)
        assertEquals("var(--hoshi-vertical-padding-block, 5.0vh)", layout.continuousBodyBottomPaddingCss)
        assertEquals(1.0, layout.imageWidthViewportRatio, 0.0)
    }

    @Test
    fun verticalLayoutsUseOnePixelImageWidthGuard() {
        assertEquals(
            1,
            ReaderGeneratedLayout.from(ReaderSettings(verticalWriting = true)).imageWidthReductionPx,
        )
        assertEquals(
            0,
            ReaderGeneratedLayout.from(ReaderSettings(verticalWriting = false)).imageWidthReductionPx,
        )
    }

    @Test
    fun horizontalContinuousLayoutMovesVerticalPaddingToViewportOnly() {
        val layout = ReaderGeneratedLayout.from(
            ReaderSettings(
                viewMode = ReaderViewMode.Continuous,
                verticalWriting = false,
                horizontalPadding = 24,
                verticalPadding = 10,
            ),
        )

        assertEquals(0.0, layout.viewportHorizontalPaddingRatio, 0.0)
        assertEquals(0.05, layout.viewportVerticalPaddingRatio, 0.0)
        assertEquals("0 12.0vw", layout.continuousBodyPaddingCss)
        assertEquals("0", layout.continuousBodyBottomPaddingCss)
        assertEquals(0.76, layout.imageWidthViewportRatio, 0.0)
    }

    @Test
    fun paginatedVerticalLayoutUsesPageHeightAsColumnWidth() {
        assertEquals(
            "var(--page-height, 100vh)",
            ReaderGeneratedLayout.from(ReaderSettings(verticalWriting = true)).paginatedColumnWidthCss,
        )
        assertEquals(
            "var(--page-width, 100vw)",
            ReaderGeneratedLayout.from(ReaderSettings(verticalWriting = false)).paginatedColumnWidthCss,
        )
    }

    @Test
    fun verticalContinuousReaderUsesHorizontalPaddingAsViewportInset() {
        assertEquals(
            0.125,
            ReaderSettings(
                viewMode = ReaderViewMode.Continuous,
                verticalWriting = true,
                horizontalPadding = 25,
            ).continuousViewportHorizontalPaddingRatio,
            0.0,
        )
        assertEquals(
            0.0,
            ReaderSettings(
                viewMode = ReaderViewMode.Continuous,
                verticalWriting = false,
                horizontalPadding = 25,
            ).continuousViewportHorizontalPaddingRatio,
            0.0,
        )
        assertEquals(
            0.0,
            ReaderSettings(
                viewMode = ReaderViewMode.Paginated,
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
                viewMode = ReaderViewMode.Continuous,
                verticalWriting = false,
                verticalPadding = 32,
            ).continuousViewportVerticalPaddingRatio,
            0.0,
        )
        assertEquals(
            0.0,
            ReaderSettings(
                viewMode = ReaderViewMode.Continuous,
                verticalWriting = true,
                verticalPadding = 32,
            ).continuousViewportVerticalPaddingRatio,
            0.0,
        )
        assertEquals(
            0.0,
            ReaderSettings(
                viewMode = ReaderViewMode.Paginated,
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
        assertFalse(
            ReaderSettings(
                theme = ReaderTheme.Custom,
                uiTheme = ReaderInterfaceTheme.Light,
            ).usesDarkInterface(systemDark = true),
        )
        assertTrue(
            ReaderSettings(
                theme = ReaderTheme.Custom,
                uiTheme = ReaderInterfaceTheme.Dark,
            ).usesDarkInterface(systemDark = false),
        )
        assertTrue(
            ReaderSettings(
                theme = ReaderTheme.Custom,
                uiTheme = ReaderInterfaceTheme.System,
            ).usesDarkInterface(systemDark = true),
        )
        assertFalse(
            ReaderSettings(
                theme = ReaderTheme.Custom,
                uiTheme = ReaderInterfaceTheme.System,
            ).usesDarkInterface(systemDark = false),
        )
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
    fun systemBarIconAppearanceFollowsResolvedReaderInterfaceTheme() {
        assertTrue(ReaderSettings(theme = ReaderTheme.Light).usesDarkSystemBarIcons(systemDark = true))
        assertTrue(ReaderSettings(theme = ReaderTheme.Sepia).usesDarkSystemBarIcons(systemDark = true))
        assertFalse(
            ReaderSettings(
                theme = ReaderTheme.Sepia,
                sepiaInvertInDark = true,
            ).usesDarkSystemBarIcons(systemDark = true),
        )
        assertFalse(ReaderSettings(theme = ReaderTheme.Dark).usesDarkSystemBarIcons(systemDark = false))
        assertFalse(ReaderSettings(theme = ReaderTheme.System).usesDarkSystemBarIcons(systemDark = true))
        assertTrue(ReaderSettings(theme = ReaderTheme.System).usesDarkSystemBarIcons(systemDark = false))
        assertFalse(
            ReaderSettings(
                theme = ReaderTheme.Custom,
                uiTheme = ReaderInterfaceTheme.Dark,
            ).usesDarkSystemBarIcons(systemDark = false),
        )
        assertTrue(
            ReaderSettings(
                theme = ReaderTheme.Custom,
                uiTheme = ReaderInterfaceTheme.Light,
            ).usesDarkSystemBarIcons(systemDark = true),
        )
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

        assertTrue(css.contains("--hoshi-background-color: #000;"))
        assertTrue(css.contains("--hoshi-text-color: #fff;"))
        assertTrue(css.contains("background: var(--hoshi-background-color) !important;"))
        assertTrue(css.contains("color: var(--hoshi-text-color) !important;"))
    }

    @Test
    fun systemReaderThemeCanUseSepiaAsLightThemeLikeIos() {
        val settings = ReaderSettings(theme = ReaderTheme.System, systemLightSepia = true)

        assertEquals(0xFFF2E2C9, settings.backgroundColor(systemDark = false))
        assertEquals("#332A1B", settings.textColorCss(systemDark = false))
        assertEquals(0xFF000000, settings.backgroundColor(systemDark = true))
        assertEquals("#fff", settings.textColorCss(systemDark = true))
        assertFalse(settings.usesDarkInterface(systemDark = false))
        assertTrue(settings.usesDarkInterface(systemDark = true))
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

        assertEquals(0xFF17150F, settings.backgroundColor(systemDark = true))
        assertEquals("#F2E2C9", settings.textColorCss(systemDark = true))
        assertEquals(0xFFF2E2C9, settings.backgroundColor(systemDark = false))
        assertEquals("#332A1B", settings.textColorCss(systemDark = false))
    }

    @Test
    fun customReaderThemeUsesConfiguredContentColorsAndSeparateInterfaceTheme() {
        val settings = ReaderSettings(
            theme = ReaderTheme.Custom,
            uiTheme = ReaderInterfaceTheme.Dark,
            customBackgroundColor = 0xFF112233,
            customTextColor = 0xFF445566,
        )

        assertEquals(0xFF112233, settings.backgroundColor(systemDark = false))
        assertEquals(0xFF112233, settings.backgroundColor(systemDark = true))
        assertEquals("#445566", settings.textColorCss(systemDark = false))
        assertEquals("#445566", settings.textColorCss(systemDark = true))
        assertTrue(settings.usesDarkInterface(systemDark = false))
    }

    @Test
    fun customReaderCssPreservesConfiguredColorAlpha() {
        val css = ReaderContentStyles.styleTag(
            settings = ReaderSettings(
                theme = ReaderTheme.Custom,
                customBackgroundColor = 0x44112233,
                customTextColor = 0x88445566,
            ),
        )

        assertTrue(css.contains("--hoshi-background-color: #11223344;"))
        assertTrue(css.contains("--hoshi-text-color: #44556688;"))
    }

    @Test
    fun eInkModeOverridesCustomThemeContentColors() {
        val settings = ReaderSettings(
            theme = ReaderTheme.Custom,
            eInkMode = true,
            uiTheme = ReaderInterfaceTheme.Dark,
            customBackgroundColor = 0xFF112233,
            customTextColor = 0xFF445566,
        )

        assertEquals(0xFF000000, settings.backgroundColor(systemDark = false))
        assertEquals("#fff", settings.textColorCss(systemDark = false))
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
    fun paginatedReaderCssAllowsFillingPageBottomAcrossParagraphs() {
        val paginatedCss = ReaderContentStyles.styleTag(
            ReaderSettings(viewMode = ReaderViewMode.Paginated),
        )
        val continuousCss = ReaderContentStyles.styleTag(
            ReaderSettings(viewMode = ReaderViewMode.Continuous),
        )

        assertTrue(paginatedCss.contains("orphans: 1 !important;"))
        assertTrue(paginatedCss.contains("widows: 1 !important;"))
        assertFalse(continuousCss.contains("orphans: 1 !important;"))
        assertFalse(continuousCss.contains("widows: 1 !important;"))
    }

    @Test
    fun paginatedReaderCssResetsNestedColumnCounts() {
        val paginatedCss = ReaderContentStyles.styleTag(
            ReaderSettings(viewMode = ReaderViewMode.Paginated),
        )
        val continuousCss = ReaderContentStyles.styleTag(
            ReaderSettings(viewMode = ReaderViewMode.Continuous),
        )

        assertTrue(Regex("""body \* \{\s+column-count: auto !important;""").containsMatchIn(paginatedCss))
        assertTrue(paginatedCss.contains("-webkit-column-count: auto !important;"))
        assertFalse(continuousCss.contains("column-count: auto !important;"))
        assertFalse(continuousCss.contains("-webkit-column-count: auto !important;"))
    }

    @Test
    fun visualNovelReaderCssResetsNestedColumnCounts() {
        val visualNovelCss = ReaderContentStyles.styleTag(
            ReaderSettings(viewMode = ReaderViewMode.VisualNovel),
        )
        val declarations = cssDeclarationsForSelector(visualNovelCss, ".hoshi-vn-content *")

        assertEquals("auto !important", declarations["column-count"])
        assertEquals("auto !important", declarations["-webkit-column-count"])
    }

    @Test
    fun visualNovelReaderCssCentersWithinVisibleViewportInsteadOfPageOverlap() {
        val css = ReaderContentStyles.styleTag(
            ReaderSettings(
                viewMode = ReaderViewMode.VisualNovel,
                verticalWriting = true,
                fontSize = 28,
                verticalPadding = 8,
                horizontalPadding = 12,
            ),
        )

        val stage = cssDeclarationsForSelector(css, ".hoshi-vn-stage")
        val screen = cssDeclarationsForSelector(css, ".hoshi-vn-screen")
        val content = cssDeclarationsForSelector(css, ".hoshi-vn-content")
        val contentSvg = cssDeclarationsForSelector(css, ".hoshi-vn-content svg")

        assertEquals("var(--hoshi-reader-visible-height, var(--page-height, 100vh)) !important", stage["height"])
        assertEquals("flex !important", screen["display"])
        assertEquals("center !important", screen["align-items"])
        assertEquals("center !important", screen["justify-content"])
        assertEquals("100% !important", screen["height"])
        assertEquals("var(--hoshi-vertical-padding-block, 4.0vh) 6.0vw !important", screen["padding"])
        assertEquals("var(--hoshi-vertical-padding-block, 4.0vh) !important", screen["padding-bottom"])
        assertEquals("100% !important", content["max-width"])
        assertEquals("100% !important", content["max-height"])
        assertEquals("visible !important", content["overflow"])
        assertEquals("allow-end !important", content["hanging-punctuation"])
        assertEquals("var(--hoshi-image-max-width, 88vw) !important", contentSvg["width"])
        assertEquals("var(--hoshi-image-max-height, calc(var(--page-height, 100vh) - 28px)) !important", contentSvg["height"])
        assertFalse(content.containsValue("calc(100% - 28px) !important"))
    }

    @Test
    fun visualNovelContentKeepsTheReaderHangingPunctuationContractWhenTextIsNotJustified() {
        val paginatedCss = ReaderContentStyles.styleTag(
            ReaderSettings(viewMode = ReaderViewMode.Paginated),
        )
        val visualNovelCss = ReaderContentStyles.styleTag(
            ReaderSettings(viewMode = ReaderViewMode.VisualNovel),
        )
        val visualNovelContent = cssDeclarationsForSelector(visualNovelCss, ".hoshi-vn-content")

        assertTrue(paginatedCss.contains("hanging-punctuation: allow-end !important;"))
        assertFalse(paginatedCss.contains("hanging-punctuation: none !important;"))
        assertEquals("allow-end !important", visualNovelContent["hanging-punctuation"])
        assertFalse(visualNovelCss.contains("hanging-punctuation: none !important;"))
    }

    @Test
    fun visualNovelContentDoesNotForceHangingPunctuationWhenTextIsJustified() {
        val visualNovelCss = ReaderContentStyles.styleTag(
            ReaderSettings(viewMode = ReaderViewMode.VisualNovel, justifyText = true),
        )
        val visualNovelContent = cssDeclarationsForSelector(visualNovelCss, ".hoshi-vn-content")

        assertFalse(visualNovelContent.containsKey("hanging-punctuation"))
        assertFalse(visualNovelCss.contains("hanging-punctuation: allow-end !important;"))
        assertFalse(visualNovelCss.contains("hanging-punctuation: none !important;"))
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
    fun readerCssIncludesIosImageBlurRules() {
        val css = ReaderContentStyles.styleTag()

        assertTrue(css.contains("img.block-img.blurred,"))
        assertTrue(css.contains("svg.blurred {"))
        assertTrue(css.contains("filter: blur(24px) !important;"))
        assertTrue(css.contains("clip-path: inset(0);"))
    }

    @Test
    fun twoOptionAppearanceSegmentsReserveEnoughWidthForContinuousLabel() {
        assertEquals(120, segmentedControlWidthDp(optionCount = 2))
        assertEquals(100, segmentedControlWidthDp(listOf("縦", "横")))
        assertEquals(120, segmentedControlWidthDp(listOf("Top", "Bottom")))
        assertEquals(180, segmentedControlWidthDp(listOf("Paginated", "Continuous")))
        assertEquals(180, segmentedControlWidthDp(listOf("Block", "Sentences")))
    }

    @Test
    fun eInkAppearanceSegmentsUseInverseSelectedColors() {
        val colors = readerSegmentedControlColors(
            eInkMode = true,
            background = Color.White,
            content = Color.Black,
            surfaceVariant = Color.White,
            primaryContainer = Color.Black,
            onPrimaryContainer = Color.White,
            outlineVariant = Color.Black,
        )

        assertEquals(Color.White, colors.container)
        assertEquals(Color.Black, colors.selected)
        assertEquals(Color.White, colors.selectedContent)
        assertEquals(Color.Black, colors.unselectedContent)
        assertEquals(Color.Black, colors.border)
    }

    private fun cssDeclarationsForSelector(css: String, selector: String): Map<String, String> {
        val escapedSelector = Regex.escape(selector)
        val block = Regex("""$escapedSelector\s*\{([^}]*)}""")
            .find(css)
            ?.groupValues
            ?.get(1)
            ?: return emptyMap()
        return block
            .split(";")
            .mapNotNull { declaration ->
                val separator = declaration.indexOf(":")
                if (separator < 0) return@mapNotNull null
                val name = declaration.substring(0, separator).trim()
                val value = declaration.substring(separator + 1).trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
            .toMap()
    }
}
