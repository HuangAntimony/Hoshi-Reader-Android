package moe.antimony.hoshi.features.reader

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
