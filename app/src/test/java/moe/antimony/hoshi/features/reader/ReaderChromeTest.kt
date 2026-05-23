package moe.antimony.hoshi.features.reader

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Timer
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun readerContentReservesOnlyTheTopSafetyArea() {
        val state = ReaderChromeState(
            title = "屍人荘の殺人",
            currentCharacter = 355,
            totalCharacters = 169325,
        )

        assertEquals(ReaderContentChromeInsets(topDp = 56, bottomDp = 0), readerContentChromeInsets())
        assertEquals(
            ReaderContentChromeInsets(topDp = 56, bottomDp = 0),
            readerContentChromeInsets(
                state = state,
                settings = ReaderSettings(showTitle = false, showCharacters = false, showPercentage = false),
            ),
        )
        assertEquals(
            ReaderContentChromeInsets(topDp = 56, bottomDp = 0),
            readerContentChromeInsets(topSystemInsetDp = 52),
        )
        assertEquals(
            ReaderContentChromeInsets(topDp = 56, bottomDp = 0),
            readerContentChromeInsets(focusMode = true),
        )
    }

    @Test
    fun topTitleBubbleUsesStableStatusAreaPaddingBeforeInsetsAnimateIn() {
        assertEquals(52, readerTopInfoOverlayPaddingDp(topSystemInsetDp = 0, focusMode = false))
        assertEquals(52, readerTopInfoOverlayPaddingDp(topSystemInsetDp = 52, focusMode = false))
        assertEquals(14, readerTopInfoOverlayPaddingDp(topSystemInsetDp = 52, focusMode = true))
    }

    @Test
    fun jumpHistoryControlsDoNotAddMoreThanTheTopSafetyArea() {
        val state = ReaderChromeState(
            title = "屍人荘の殺人",
            currentCharacter = 355,
            totalCharacters = 169325,
            backTargetCharacter = 120,
        )

        assertEquals(
            ReaderContentChromeInsets(topDp = 56, bottomDp = 0),
            readerContentChromeInsets(
                state = state,
                settings = ReaderSettings(showTitle = false, showProgressTop = false),
            ),
        )
    }

    @Test
    fun topQuickControlsDoNotAddMoreThanTheTopSafetyArea() {
        val state = ReaderChromeState(
            title = "屍人荘の殺人",
            currentCharacter = 355,
            totalCharacters = 169325,
        )

        assertEquals(
            ReaderContentChromeInsets(topDp = 56, bottomDp = 0),
            readerContentChromeInsets(
                state = state,
                settings = ReaderSettings(showTitle = false, showProgressTop = false),
                showSasayakiToggle = true,
            ),
        )
        assertEquals(
            ReaderContentChromeInsets(topDp = 56, bottomDp = 0),
            readerContentChromeInsets(
                state = state,
                settings = ReaderSettings(showTitle = false, showProgressTop = false),
                showStatisticsToggle = true,
            ),
        )
        assertEquals(
            ReaderContentChromeInsets(topDp = 56, bottomDp = 0),
            readerContentChromeInsets(
                state = state,
                settings = ReaderSettings(showTitle = false, showProgressTop = false),
                showSasayakiToggle = false,
            ),
        )
        assertEquals(
            ReaderContentChromeInsets(topDp = 56, bottomDp = 0),
            readerContentChromeInsets(
                state = state,
                settings = ReaderSettings(showTitle = true, showProgressTop = false),
                showSasayakiToggle = true,
            ),
        )
        assertEquals(
            ReaderContentChromeInsets(topDp = 56, bottomDp = 0),
            readerContentChromeInsets(
                state = state,
                settings = ReaderSettings(showTitle = true, showProgressTop = true),
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
    fun jumpHistoryTargetTextUsesUngroupedIosCharacterCount() {
        assertEquals("1234567", readerJumpTargetText(1_234_567))
    }

    @Test
    fun jumpHistoryTopControlsUseIosUndoRedoIcons() {
        assertEquals(Icons.AutoMirrored.Rounded.Undo, readerJumpBackIcon())
        assertEquals(Icons.AutoMirrored.Rounded.Redo, readerJumpForwardIcon())
    }

    @Test
    fun topTitleReservesControlsSymmetricallySoItAlignsWithProgress() {
        assertEquals(
            ReaderTopTitlePaddingDp(startDp = 42, endDp = 42),
            readerTopTitlePaddingDp(hasStartControl = true, hasEndControl = false),
        )
        assertEquals(
            ReaderTopTitlePaddingDp(startDp = 42, endDp = 42),
            readerTopTitlePaddingDp(hasStartControl = false, hasEndControl = true),
        )
        assertEquals(
            ReaderTopTitlePaddingDp(startDp = 42, endDp = 42),
            readerTopTitlePaddingDp(hasStartControl = true, hasEndControl = true),
        )
        assertEquals(
            ReaderTopTitlePaddingDp(startDp = 0, endDp = 0),
            readerTopTitlePaddingDp(hasStartControl = false, hasEndControl = false),
        )
    }

    @Test
    fun statisticsTopToggleUsesIosTimerIconWhenTracking() {
        assertEquals(Icons.AutoMirrored.Rounded.ShowChart, readerStatisticsTopToggleIcon(isTracking = false))
        assertEquals(Icons.Rounded.Timer, readerStatisticsTopToggleIcon(isTracking = true))
        assertEquals(Icons.Rounded.GraphicEq, readerSasayakiTopToggleIcon(isPlaying = false))
        assertEquals(Icons.Rounded.Pause, readerSasayakiTopToggleIcon(isPlaying = true))
        assertNotEquals(readerSasayakiTopToggleIcon(isPlaying = true), readerStatisticsTopToggleIcon(isTracking = true))
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
    fun centerInfoUsesBubbleChromeLikeIos() {
        assertEquals(
            ReaderInfoBubbleMetrics(horizontalPaddingDp = 12, verticalPaddingDp = 6, cornerRadiusDp = 24),
            readerInfoBubbleMetrics(),
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
    fun focusModeDoesNotChangeReaderContentChromeInsets() {
        val state = ReaderChromeState(
            title = "屍人荘の殺人",
            currentCharacter = 355,
            totalCharacters = 169325,
        )
        val normalInsets = readerContentChromeInsets(
            state = state,
            settings = ReaderSettings(),
            showSasayakiToggle = true,
            focusMode = false,
        )
        val focusInsets = readerContentChromeInsets(
            state = state,
            settings = ReaderSettings(),
            showSasayakiToggle = true,
            focusMode = true,
        )

        assertEquals(ReaderContentChromeInsets(topDp = 56, bottomDp = 0), normalInsets)
        assertEquals(normalInsets, focusInsets)
    }

    @Test
    fun readerSystemBarsShowStatusOutsideFocusAndHideAllInFocus() {
        assertEquals(
            ReaderSystemBarVisibility(showStatusBar = true, showNavigationBar = false),
            readerSystemBarVisibility(focusMode = false),
        )
        assertEquals(
            ReaderSystemBarVisibility(showStatusBar = false, showNavigationBar = false),
            readerSystemBarVisibility(focusMode = true),
        )
    }

    @Test
    fun focusModeKeepsTopQuickControlsAvailableAndHidesBottomChrome() {
        val visibility = readerChromeVisibility(
            focusMode = true,
            hasStatisticsToggle = true,
            hasSasayakiToggle = true,
            hasBackJump = true,
            hasForwardJump = true,
        )

        assertFalse(visibility.showTitleAndProgress)
        assertFalse(visibility.showBottomChrome)
        assertEquals(true, visibility.showStatisticsToggle)
        assertEquals(true, visibility.showSasayakiToggle)
        assertEquals(true, visibility.showBackJump)
        assertEquals(true, visibility.showForwardJump)
    }

    @Test
    fun nonFocusModeShowsTitleProgressBottomChromeAndHidesTopQuickControls() {
        val visibility = readerChromeVisibility(
            focusMode = false,
            hasStatisticsToggle = true,
            hasSasayakiToggle = true,
            hasBackJump = true,
            hasForwardJump = true,
        )

        assertEquals(true, visibility.showTitleAndProgress)
        assertEquals(true, visibility.showBottomChrome)
        assertFalse(visibility.showStatisticsToggle)
        assertFalse(visibility.showSasayakiToggle)
        assertFalse(visibility.showBackJump)
        assertFalse(visibility.showForwardJump)
    }

    @Test
    fun bottomFocusModeTapAreaDoesNotInterceptReaderText() {
        val metrics = readerBottomChromeMetrics()
        val skipButtons = ReaderSasayakiBottomSkipButtons(
            visible = true,
            buttonSizeDp = metrics.buttonSizeDp,
            iconSizeDp = metrics.secondaryIconSizeDp,
            adjacentSpacingDp = metrics.trailingButtonSpacingDp,
        )

        assertFalse(
            readerFocusModeToggleArea(
                metrics = metrics,
                sasayakiSkipButtons = skipButtons.copy(visible = false),
                focusMode = false,
            ).visible,
        )
        assertFalse(
            readerFocusModeToggleArea(
                metrics = metrics,
                sasayakiSkipButtons = skipButtons,
                focusMode = false,
            ).visible,
        )
    }

    @Test
    fun bottomChromeUsesCompactOverlayControlsWithoutContentInset() {
        val metrics = readerBottomChromeMetrics()

        assertEquals(44, metrics.buttonSizeDp)
        assertEquals(28, metrics.primaryIconSizeDp)
        assertEquals(28, metrics.secondaryIconSizeDp)
        assertEquals(8, metrics.trailingButtonSpacingDp)
        assertEquals(46, metrics.menuBottomOffsetDp)
        assertEquals(ReaderContentChromeInsets(topDp = 56, bottomDp = 0), readerContentChromeInsets())
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
    fun sasayakiBottomSkipActionsOnlyReverseInVerticalWritingWhenEnabled() {
        assertEquals(
            ReaderSasayakiBottomSkipButtonActions(
                left = ReaderSasayakiBottomSkipButtonAction.Backward,
                right = ReaderSasayakiBottomSkipButtonAction.Forward,
            ),
            readerSasayakiBottomSkipButtonActions(
                verticalWriting = false,
                reverseVerticalReaderSkipButtons = false,
            ),
        )
        assertEquals(
            ReaderSasayakiBottomSkipButtonActions(
                left = ReaderSasayakiBottomSkipButtonAction.Backward,
                right = ReaderSasayakiBottomSkipButtonAction.Forward,
            ),
            readerSasayakiBottomSkipButtonActions(
                verticalWriting = false,
                reverseVerticalReaderSkipButtons = true,
            ),
        )
        assertEquals(
            ReaderSasayakiBottomSkipButtonActions(
                left = ReaderSasayakiBottomSkipButtonAction.Backward,
                right = ReaderSasayakiBottomSkipButtonAction.Forward,
            ),
            readerSasayakiBottomSkipButtonActions(
                verticalWriting = true,
                reverseVerticalReaderSkipButtons = false,
            ),
        )
        assertEquals(
            ReaderSasayakiBottomSkipButtonActions(
                left = ReaderSasayakiBottomSkipButtonAction.Forward,
                right = ReaderSasayakiBottomSkipButtonAction.Backward,
            ),
            readerSasayakiBottomSkipButtonActions(
                verticalWriting = true,
                reverseVerticalReaderSkipButtons = true,
            ),
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
    fun lightReaderMenuUsesVisibleOutlineAgainstWhiteReaderBackground() {
        val colors = readerChromeColors(ReaderSettings(theme = ReaderTheme.Light), systemDark = false)

        assertEquals(0x1F000000L, colors.menuBorder)
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
