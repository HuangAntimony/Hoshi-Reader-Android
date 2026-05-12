package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ReaderChromeTest {
    @Test
    fun formatsProgressLikeIosReaderOverlay() {
        val text = ReaderChromeState(
            title = "屍人荘の殺人",
            currentCharacter = 355,
            totalCharacters = 169325,
        ).progressText(ReaderSettings())

        assertEquals("355 / 169325 0.21%", text)
    }

    @Test
    fun formatsStatisticsLikeIosReaderOverlay() {
        val text = ReaderChromeState(
            title = "屍人荘の殺人",
            currentCharacter = 355,
            totalCharacters = 169325,
            statistics = ReaderStatisticsChromeState(readingSpeed = 3600, readingTimeSeconds = 65.0),
        ).statisticsText(
            ReaderSettings(
                enableStatistics = true,
                showReadingSpeed = true,
                showReadingTime = true,
            ),
        )

        assertEquals("3600 / h 0:01", text)
    }

    @Test
    fun hidesProgressPiecesFromAppearanceSettings() {
        val state = ReaderChromeState(
            title = "屍人荘の殺人",
            currentCharacter = 355,
            totalCharacters = 169325,
        )

        assertEquals("0.21%", state.progressText(ReaderSettings(showCharacters = false)))
        assertEquals("355 / 169325", state.progressText(ReaderSettings(showPercentage = false)))
        assertEquals("", state.progressText(ReaderSettings(showCharacters = false, showPercentage = false)))
    }

    @Test
    fun topReaderPaddingFollowsVisibleTopChromeRows() {
        val state = ReaderChromeState(
            title = "屍人荘の殺人",
            currentCharacter = 355,
            totalCharacters = 169325,
        )

        assertEquals(44, readerWebViewTopPaddingDp(state, ReaderSettings()))
        assertEquals(24, readerWebViewTopPaddingDp(state, ReaderSettings(showProgressTop = false)))
        assertEquals(24, readerWebViewTopPaddingDp(state, ReaderSettings(showTitle = false)))
        assertEquals(4, readerWebViewTopPaddingDp(state, ReaderSettings(showTitle = false, showProgressTop = false)))
        assertEquals(
            4,
            readerWebViewTopPaddingDp(
                state,
                ReaderSettings(showTitle = false, showCharacters = false, showPercentage = false),
            ),
        )
    }

    @Test
    fun sasayakiTopToggleReservesTheTopChromeRowLikeIos() {
        val state = ReaderChromeState(
            title = "屍人荘の殺人",
            currentCharacter = 355,
            totalCharacters = 169325,
        )

        assertEquals(
            40,
            readerWebViewTopPaddingDp(
                state,
                ReaderSettings(showTitle = false, showProgressTop = false),
                showSasayakiToggle = true,
            ),
        )
        assertEquals(
            40,
            readerWebViewTopPaddingDp(
                state,
                ReaderSettings(showTitle = false, showProgressTop = false),
                showStatisticsToggle = true,
            ),
        )
        assertEquals(
            4,
            readerWebViewTopPaddingDp(
                state,
                ReaderSettings(showTitle = false, showProgressTop = false),
                showSasayakiToggle = false,
            ),
        )
        assertEquals(
            40,
            readerWebViewTopPaddingDp(
                state,
                ReaderSettings(showTitle = true, showProgressTop = false),
                showSasayakiToggle = true,
            ),
        )
        assertEquals(
            44,
            readerWebViewTopPaddingDp(
                state,
                ReaderSettings(showTitle = true, showProgressTop = true),
                showSasayakiToggle = true,
            ),
        )
    }

    @Test
    fun statisticsTopToggleUsesSameMetricsAsSasayakiTopToggle() {
        val metrics = readerBottomChromeMetrics()

        assertEquals(metrics.topSasayakiButtonSizeDp, metrics.topStatisticsButtonSizeDp)
        assertEquals(metrics.topSasayakiIconSizeDp, metrics.topStatisticsIconSizeDp)
    }

    @Test
    fun bottomStatisticsAndProgressFitInsideBottomChromeButtonHeight() {
        val state = ReaderChromeState(
            title = "屍人荘の殺人",
            currentCharacter = 355,
            totalCharacters = 169325,
            statistics = ReaderStatisticsChromeState(readingSpeed = 3600, readingTimeSeconds = 65.0),
        )
        val layout = readerChromeLayout(
            state,
            ReaderSettings(
                showProgressTop = false,
                enableStatistics = true,
                showReadingSpeed = true,
                showReadingTime = true,
            ),
        )

        assertEquals(2, layout.bottomCenterLineCount)
        assertEquals(readerBottomChromeMetrics().buttonSizeDp, layout.bottomCenterMaxHeightDp)
    }

    @Test
    fun topSasayakiToggleUsesSmallerCircleWithoutShrinkingTheIcon() {
        val metrics = readerBottomChromeMetrics()

        assertEquals(36, metrics.topSasayakiButtonSizeDp)
        assertEquals(20, metrics.topSasayakiIconSizeDp)
    }

    @Test
    fun bottomProgressBelongsToBottomChromeWhenProgressIsNotTop() {
        val state = ReaderChromeState(
            title = "屍人荘の殺人",
            currentCharacter = 355,
            totalCharacters = 169325,
        )

        assertFalse(readerChromeLayout(state, ReaderSettings()).showProgressInBottomBar)
        assertEquals(
            true,
            readerChromeLayout(state, ReaderSettings(showProgressTop = false)).showProgressInBottomBar,
        )
        assertFalse(
            readerChromeLayout(
                state,
                ReaderSettings(showProgressTop = false, showCharacters = false, showPercentage = false),
            ).showProgressInBottomBar,
        )
    }

    @Test
    fun focusModeKeepsTheReaderContentPaddingStable() {
        val state = ReaderChromeState(
            title = "屍人荘の殺人",
            currentCharacter = 355,
            totalCharacters = 169325,
        )
        val normalLayout = readerChromeLayout(
            state = state,
            settings = ReaderSettings(),
            showSasayakiToggle = true,
            focusMode = false,
        )
        val focusLayout = readerChromeLayout(
            state = state,
            settings = ReaderSettings(),
            showSasayakiToggle = true,
            focusMode = true,
        )

        assertEquals(normalLayout.topWebViewPaddingDp, focusLayout.topWebViewPaddingDp)
        assertEquals(normalLayout.showProgressInBottomBar, focusLayout.showProgressInBottomBar)
    }

    @Test
    fun focusModeEntryTapAreaStaysBetweenBottomButtonClusters() {
        val metrics = readerBottomChromeMetrics()
        val skipButtons = ReaderSasayakiBottomSkipButtons(
            visible = true,
            buttonSizeDp = metrics.buttonSizeDp,
            iconSizeDp = metrics.secondaryIconSizeDp,
            adjacentSpacingDp = metrics.trailingButtonSpacingDp,
        )

        assertEquals(
            metrics.horizontalPaddingDp + metrics.buttonSizeDp,
            readerFocusModeToggleArea(
                metrics = metrics,
                sasayakiSkipButtons = skipButtons.copy(visible = false),
                focusMode = false,
            ).horizontalPaddingDp,
        )
        assertEquals(
            metrics.horizontalPaddingDp + metrics.buttonSizeDp + metrics.trailingButtonSpacingDp + metrics.buttonSizeDp,
            readerFocusModeToggleArea(
                metrics = metrics,
                sasayakiSkipButtons = skipButtons,
                focusMode = false,
            ).horizontalPaddingDp,
        )
        assertEquals(
            0,
            readerFocusModeToggleArea(
                metrics = metrics,
                sasayakiSkipButtons = skipButtons,
                focusMode = true,
            ).horizontalPaddingDp,
        )
    }

    @Test
    fun bottomChromeUsesCompactControlsAndKeepsReaderContentFlushToButtonTop() {
        val metrics = readerBottomChromeMetrics()

        assertEquals(44, metrics.buttonSizeDp)
        assertEquals(28, metrics.primaryIconSizeDp)
        assertEquals(28, metrics.secondaryIconSizeDp)
        assertEquals(8, metrics.trailingButtonSpacingDp)
        assertEquals(46, metrics.webViewBottomPaddingDp)
        assertEquals(metrics.webViewBottomPaddingDp, metrics.menuBottomPaddingDp)
        assertEquals(metrics.buttonSizeDp + metrics.bottomPaddingDp, metrics.webViewBottomPaddingDp)
    }

    @Test
    fun sasayakiBottomSkipButtonsMatchBottomChromeButtonsWhenEnabled() {
        val metrics = readerBottomChromeMetrics()

        assertEquals(
            ReaderSasayakiBottomSkipButtons(
                visible = true,
                buttonSizeDp = metrics.buttonSizeDp,
                iconSizeDp = metrics.secondaryIconSizeDp,
                adjacentSpacingDp = metrics.trailingButtonSpacingDp,
            ),
            readerSasayakiBottomSkipButtons(
                settings = SasayakiSettings(showReaderSkipButtons = true),
                hasAudio = true,
                metrics = metrics,
            ),
        )
        assertFalse(
            readerSasayakiBottomSkipButtons(
                settings = SasayakiSettings(showReaderSkipButtons = false),
                hasAudio = true,
                metrics = metrics,
            ).visible,
        )
        assertFalse(
            readerSasayakiBottomSkipButtons(
                settings = SasayakiSettings(showReaderSkipButtons = true),
                hasAudio = false,
                metrics = metrics,
            ).visible,
        )
    }

    @Test
    fun bottomMenuUsesCompactReaderChromeMetrics() {
        val metrics = readerBottomChromeMetrics()

        assertEquals(204, metrics.menuWidthDp)
        assertEquals(4, metrics.menuVerticalPaddingDp)
        assertEquals(16, metrics.menuItemHorizontalPaddingDp)
        assertEquals(8, metrics.menuItemVerticalPaddingDp)
        assertEquals(24, metrics.menuItemIconBoxSizeDp)
        assertEquals(12, metrics.menuItemSpacingDp)
    }

    @Test
    fun usesThemeMatchedChromeColors() {
        assertEquals(0x40FFFFFFL, readerChromeColors(ReaderSettings(theme = ReaderTheme.Sepia), systemDark = true).buttonContainer)
        assertEquals(0x661A1A1AL, readerChromeColors(ReaderSettings(theme = ReaderTheme.Dark), systemDark = false).buttonContainer)
    }

    @Test
    fun invertedSepiaChromeUsesDarkInterfaceColorsInSystemDarkMode() {
        val colors = readerChromeColors(
            ReaderSettings(theme = ReaderTheme.Sepia, sepiaInvertInDark = true),
            systemDark = true,
        )

        assertEquals(0x661A1A1AL, colors.buttonContainer)
        assertEquals(0xFFF4F4F4L, colors.buttonContent)
    }

    @Test
    fun systemThemeChromeFollowsSystemDarkMode() {
        val settings = ReaderSettings(theme = ReaderTheme.System)

        assertEquals(0x661A1A1AL, readerChromeColors(settings, systemDark = true).buttonContainer)
        assertEquals(0xD9FFFFFFL, readerChromeColors(settings, systemDark = false).buttonContainer)
    }

    @Test
    fun systemThemeChromeUsesSepiaColorsWhenSepiaIsEnabledAsLightTheme() {
        val settings = ReaderSettings(theme = ReaderTheme.System, systemLightSepia = true)

        assertEquals(0x40FFFFFFL, readerChromeColors(settings, systemDark = false).buttonContainer)
        assertEquals(0x661A1A1AL, readerChromeColors(settings, systemDark = true).buttonContainer)
    }

    @Test
    fun eInkModeUsesOpaquePureChromeColors() {
        val light = readerChromeColors(ReaderSettings(eInkMode = true), systemDark = false)
        val dark = readerChromeColors(ReaderSettings(theme = ReaderTheme.Dark, eInkMode = true), systemDark = false)

        assertEquals(0xFFFFFFFFL, light.buttonContainer)
        assertEquals(0xFF000000L, light.buttonContent)
        assertEquals(0xFFFFFFFFL, light.menuContainer)
        assertEquals(0xFF000000L, light.menuContent)
        assertEquals(0xFF000000L, light.infoText)
        assertEquals(0xFF000000L, dark.buttonContainer)
        assertEquals(0xFFFFFFFFL, dark.buttonContent)
    }

}
