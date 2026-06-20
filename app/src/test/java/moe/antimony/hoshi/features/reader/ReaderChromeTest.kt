package moe.antimony.hoshi.features.reader

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TravelExplore
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderChromeTest {
    @Test
    fun wordDisplayUnitRoundsCharacterCountsUp() {
        val display = ReaderProgressDisplay.word()

        assertEquals(0, display.displayCount(0))
        assertEquals(1, display.displayCount(1))
        assertEquals(1, display.displayCount(5))
        assertEquals(2, display.displayCount(6))
        assertEquals(71, display.displayCount(355))
        assertEquals(33_865, display.displayCount(169_325))
    }

    @Test
    fun wordDisplayUnitConvertsJumpTargetsBackToCharacters() {
        val display = ReaderProgressDisplay.word()

        assertEquals(0, display.rawTargetFromDisplayCount(0, totalCharacters = 1_000))
        assertEquals(500, display.rawTargetFromDisplayCount(100, totalCharacters = 1_000))
        assertEquals(1_000, display.rawTargetFromDisplayCount(250, totalCharacters = 1_000))
    }

    @Test
    fun formatsEnglishProfileProgressWithWordCountsAndRawPercentage() {
        val text = ReaderChromeState(
            title = "Moby-Dick",
            currentCharacter = 355,
            totalCharacters = 169_325,
        ).progressText(ReaderSettings(), ReaderProgressDisplay.word())

        assertEquals("71 / 33,865 0.21%", text)
    }

    @Test
    fun formatsEnglishProfileStatisticsWithWordSpeed() {
        val text = ReaderChromeState(
            title = "Moby-Dick",
            currentCharacter = 355,
            totalCharacters = 169_325,
            statistics = ReaderStatisticsChromeState(readingSpeed = 3_600, readingTimeSeconds = 65.0),
        ).statisticsText(
            ReaderSettings(
                enableStatistics = true,
                showReadingSpeed = true,
                showReadingTime = true,
            ),
            ReaderProgressDisplay.word(),
        )

        assertEquals("720 / h 0:01", text)
    }

    @Test
    fun formatsEnglishProfileCountsWithoutWordUnits() {
        val display = ReaderProgressDisplay.word()

        assertEquals("1", display.countText(1))
        assertEquals("71", display.countText(355))
        assertEquals("720 / h", display.speedText(3_600))
    }

    @Test
    fun formatsSearchResultPositionWithRawPercentage() {
        val display = ReaderProgressDisplay.characters()

        assertEquals("355 (0.21%)", display.countWithPercentText(355, 169_325))
    }

    @Test
    fun formatsEnglishSearchResultPositionWithDisplayedWordCountAndRawPercentage() {
        val display = ReaderProgressDisplay.word()

        assertEquals("71 (0.21%)", display.countWithPercentText(355, 169_325))
    }

    @Test
    fun formatsEnglishProfileJumpTargetsAsWordCounts() {
        assertEquals("71", readerJumpTargetText(355, ReaderProgressDisplay.word()))
    }

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

        assertEquals(ReaderContentChromeInsets(topDp = 34, bottomDp = 18), readerContentChromeInsets())
        assertEquals(
            ReaderContentChromeInsets(topDp = 34, bottomDp = 18),
            readerContentChromeInsets(
                state = state,
                settings = ReaderSettings(showTitle = false, showCharacters = false, showPercentage = false),
            ),
        )
        assertEquals(
            ReaderContentChromeInsets(topDp = 34, bottomDp = 18),
            readerContentChromeInsets(topSystemInsetDp = 52),
        )
        assertEquals(
            ReaderContentChromeInsets(topDp = 34, bottomDp = 18),
            readerContentChromeInsets(focusMode = true),
        )
    }

    @Test
    fun topTitleBubbleUsesStableStatusAreaPaddingBeforeInsetsAnimateIn() {
        assertEquals(52, readerTopInfoOverlayPaddingDp(topSystemInsetDp = 0, focusMode = false))
        assertEquals(44, readerTopInfoOverlayPaddingDp(topSystemInsetDp = 44, focusMode = false))
        assertEquals(52, readerTopInfoOverlayPaddingDp(topSystemInsetDp = 52, focusMode = false))
        assertEquals(0, readerTopInfoOverlayPaddingDp(topSystemInsetDp = 52, focusMode = true))
    }

    @Test
    fun topTitleBubbleWaitsForStatusBarRevealToSettleAfterFocusMode() {
        assertFalse(
            readerShouldShowTitleAndProgress(
                focusMode = true,
                currentStatusBarInsetDp = 52,
                stableStatusBarInsetDp = 52,
            ),
        )
        assertFalse(
            readerShouldShowTitleAndProgress(
                focusMode = false,
                currentStatusBarInsetDp = 0,
                stableStatusBarInsetDp = 52,
            ),
        )
        assertFalse(
            readerShouldShowTitleAndProgress(
                focusMode = false,
                currentStatusBarInsetDp = 24,
                stableStatusBarInsetDp = 52,
            ),
        )
        assertTrue(
            readerShouldShowTitleAndProgress(
                focusMode = false,
                currentStatusBarInsetDp = 52,
                stableStatusBarInsetDp = 52,
            ),
        )
        assertTrue(
            readerShouldShowTitleAndProgress(
                focusMode = false,
                currentStatusBarInsetDp = 44,
                stableStatusBarInsetDp = 0,
            ),
        )
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
            ReaderContentChromeInsets(topDp = 34, bottomDp = 18),
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
            ReaderContentChromeInsets(topDp = 34, bottomDp = 18),
            readerContentChromeInsets(
                state = state,
                settings = ReaderSettings(showTitle = false, showProgressTop = false),
                showSasayakiToggle = true,
            ),
        )
        assertEquals(
            ReaderContentChromeInsets(topDp = 34, bottomDp = 18),
            readerContentChromeInsets(
                state = state,
                settings = ReaderSettings(showTitle = false, showProgressTop = false),
                showStatisticsToggle = true,
            ),
        )
        assertEquals(
            ReaderContentChromeInsets(topDp = 34, bottomDp = 18),
            readerContentChromeInsets(
                state = state,
                settings = ReaderSettings(showTitle = false, showProgressTop = false),
                showSasayakiToggle = false,
            ),
        )
        assertEquals(
            ReaderContentChromeInsets(topDp = 34, bottomDp = 18),
            readerContentChromeInsets(
                state = state,
                settings = ReaderSettings(showTitle = true, showProgressTop = false),
                showSasayakiToggle = true,
            ),
        )
        assertEquals(
            ReaderContentChromeInsets(topDp = 34, bottomDp = 18),
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
                alwaysShowProgress = false,
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

        assertEquals(30, metrics.topSasayakiButtonSizeDp)
        assertEquals(22, metrics.topSasayakiIconSizeDp)
        assertEquals(4, metrics.topButtonOffsetYDp)
        assertEquals(8, metrics.topButtonHorizontalInsetDp)
        assertEquals(0x00000000L, readerTopButtonContainerColor())
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
            readerChromeLayout(state, ReaderSettings(alwaysShowProgress = false, showProgressTop = false)).showProgressInBottomBar,
        )
        assertFalse(
            readerChromeLayout(
                state,
                ReaderSettings(
                    alwaysShowProgress = false,
                    showProgressTop = false,
                    showCharacters = false,
                    showPercentage = false,
                ),
            ).showProgressInBottomBar,
        )
    }

    @Test
    fun alwaysShowProgressPersistsThroughFocusModeWithoutBottomChromeDuplicate() {
        val state = ReaderChromeState(
            title = "屍人荘の殺人",
            currentCharacter = 355,
            totalCharacters = 169325,
        )
        val settings = ReaderSettings()

        assertFalse(readerShowsProgressInTopBubble(settings))
        assertFalse(readerChromeLayout(state, settings.copy(showProgressTop = false)).showProgressInBottomBar)
        assertEquals("355 / 169325 0.21%", readerBottomSafeProgressText(state, settings, focusMode = false))
        assertEquals("355 / 169325 0.21%", readerBottomSafeProgressText(state, settings, focusMode = true))
        assertEquals(
            "",
            readerBottomSafeProgressText(
                state,
                settings.copy(showCharacters = false, showPercentage = false),
                focusMode = true,
            ),
        )
    }

    @Test
    fun disablingAlwaysShowProgressRestoresBubbleProgressPosition() {
        val state = ReaderChromeState(
            title = "屍人荘の殺人",
            currentCharacter = 355,
            totalCharacters = 169325,
        )
        val settings = ReaderSettings(alwaysShowProgress = false)

        assertEquals("", readerBottomSafeProgressText(state, settings, focusMode = false))
        assertTrue(readerShowsProgressInTopBubble(settings.copy(showProgressTop = true)))
        assertFalse(readerShowsProgressInTopBubble(settings.copy(showProgressTop = false)))
        assertFalse(readerChromeLayout(state, settings.copy(showProgressTop = true)).showProgressInBottomBar)
        assertTrue(readerChromeLayout(state, settings.copy(showProgressTop = false)).showProgressInBottomBar)
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

        assertEquals(ReaderContentChromeInsets(topDp = 34, bottomDp = 18), normalInsets)
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

        assertFalse(
            readerFocusModeToggleArea(
                metrics = metrics,
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
        assertEquals(18, metrics.bottomSafeAreaDp)
        assertEquals(8, metrics.menuButtonGapDp)
        assertEquals(72, metrics.menuBottomOffsetDp)
        assertEquals(ReaderContentChromeInsets(topDp = 34, bottomDp = 18), readerContentChromeInsets())
    }

    @Test
    fun sasayakiBottomPlaybackControlsStayInsideBottomSafeAreaWhenEnabled() {
        val metrics = readerBottomChromeMetrics()

        assertEquals(
            ReaderSasayakiBottomPlaybackControls(
                visible = true,
                rowHeightDp = metrics.bottomSafeAreaDp,
                buttonWidthDp = 40,
                iconSizeDp = 14,
                horizontalPaddingDp = 18,
            ),
            readerSasayakiBottomPlaybackControls(
                settings = SasayakiSettings(showReaderBottomPlaybackControls = true),
                hasAudio = true,
                metrics = metrics,
            ),
        )
        assertFalse(
            readerSasayakiBottomPlaybackControls(
                settings = SasayakiSettings(showReaderBottomPlaybackControls = false),
                hasAudio = true,
                metrics = metrics,
            ).visible,
        )
        assertFalse(
            readerSasayakiBottomPlaybackControls(
                settings = SasayakiSettings(showReaderBottomPlaybackControls = true),
                hasAudio = false,
                metrics = metrics,
            ).visible,
        )
        assertTrue(readerSasayakiBottomPlaybackControls(
            settings = SasayakiSettings(showReaderBottomPlaybackControls = true),
            hasAudio = true,
            metrics = metrics,
        ).rowHeightDp <= metrics.bottomSafeAreaDp)
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
    fun bottomMenuVisualOrderMatchesIosUpwardMenu() {
        assertEquals(
            listOf(
                ReaderMenuDestination.Sasayaki,
                ReaderMenuDestination.Statistics,
                ReaderMenuDestination.GoTo,
                ReaderMenuDestination.Appearance,
            ),
            readerBottomMenuVisualOrder(showStatistics = true, showSasayaki = true),
        )
    }

    @Test
    fun bottomMenuOmitsUnavailableOptionalDestinationsWithoutChangingIosOrder() {
        assertEquals(
            listOf(
                ReaderMenuDestination.GoTo,
                ReaderMenuDestination.Appearance,
            ),
            readerBottomMenuVisualOrder(showStatistics = false, showSasayaki = false),
        )
    }

    @Test
    fun bottomMenuGoToUsesNavigationIconInsteadOfChapterListIcon() {
        assertEquals(Icons.Rounded.TravelExplore, readerBottomMenuIcon(ReaderMenuDestination.GoTo))
    }

    @Test
    fun usesThemeMatchedChromeColors() {
        assertEquals(0xFAF8F0E2L, readerChromeColors(ReaderSettings(theme = ReaderTheme.Sepia), systemDark = true).buttonContainer)
        assertEquals(0xE6141414L, readerChromeColors(ReaderSettings(theme = ReaderTheme.Dark), systemDark = false).buttonContainer)
    }

    @Test
    fun invertedSepiaChromeUsesDarkInterfaceColorsInSystemDarkMode() {
        val colors = readerChromeColors(
            ReaderSettings(theme = ReaderTheme.Sepia, sepiaInvertInDark = true),
            systemDark = true,
        )

        assertEquals(0xE6191713L, colors.buttonContainer)
        assertEquals(0xE6191713L, colors.menuContainer)
        assertEquals(0xE6, colors.buttonContainer ushr 24)
        assertEquals(readerChromeColors(ReaderSettings(theme = ReaderTheme.Dark), systemDark = false).buttonContainer ushr 24, colors.buttonContainer ushr 24)
        assertEquals(0xFF4A4438L, colors.buttonBorder)
        assertEquals(0xFFF2E2C9L, colors.buttonContent)
    }

    @Test
    fun systemThemeChromeFollowsSystemDarkMode() {
        val settings = ReaderSettings(theme = ReaderTheme.System)

        assertEquals(0xE6141414L, readerChromeColors(settings, systemDark = true).buttonContainer)
        assertEquals(0xFAFCFCFCL, readerChromeColors(settings, systemDark = false).buttonContainer)
    }

    @Test
    fun lightReaderMenuUsesVisibleOutlineAgainstWhiteReaderBackground() {
        val colors = readerChromeColors(ReaderSettings(theme = ReaderTheme.Light), systemDark = false)

        assertEquals(0xE6FFFFFFL, colors.menuBorder)
        assertEquals(0xE6FFFFFFL, colors.buttonBorder)
    }

    @Test
    fun systemThemeChromeUsesSepiaColorsWhenSepiaIsEnabledAsLightTheme() {
        val settings = ReaderSettings(theme = ReaderTheme.System, systemLightSepia = true)

        assertEquals(0xFAF8F0E2L, readerChromeColors(settings, systemDark = false).buttonContainer)
        assertEquals(0xE6141414L, readerChromeColors(settings, systemDark = true).buttonContainer)
    }

    @Test
    fun customReaderThemeUsesCustomInfoColorForReaderMetadata() {
        val colors = readerChromeColors(
            ReaderSettings(
                theme = ReaderTheme.Custom,
                uiTheme = ReaderInterfaceTheme.Light,
                customInfoColor = 0xFF778899,
            ),
            systemDark = true,
        )

        assertEquals(0xFAFCFCFCL, colors.buttonContainer)
        assertEquals(0xFF778899L, colors.infoText)
    }

    @Test
    fun nonEInkChromeUsesIosLikeGlassHighlightsAndShadows() {
        val light = readerChromeColors(ReaderSettings(theme = ReaderTheme.Light), systemDark = false)
        val sepia = readerChromeColors(ReaderSettings(theme = ReaderTheme.Sepia), systemDark = false)
        val dark = readerChromeColors(ReaderSettings(theme = ReaderTheme.Dark), systemDark = false)

        assertEquals(0xFAFCFCFCL, light.buttonContainer)
        assertEquals(0xFAFCFCFCL, light.menuContainer)
        assertEquals(0xE6FFFFFFL, light.buttonBorder)
        assertEquals(0x00000000L, light.buttonOutline)
        assertEquals(light.buttonBorder, light.menuBorder)
        assertEquals(light.buttonOutline, light.bubbleOutline)
        assertEquals(1, light.buttonShadowElevationDp)
        assertEquals(0x25000000L, light.buttonShadowColor)
        assertEquals(0x00000000L, light.buttonInnerShadowColor)
        assertEquals(1, light.bubbleShadowElevationDp)
        assertEquals(0x25000000L, light.bubbleShadowColor)
        assertEquals(0x00000000L, light.bubbleInnerShadowColor)
        assertEquals(0.75f, readerButtonBorderWidthDp(light))
        assertEquals(0.75f, readerBubbleBorderWidthDp(light))
        assertEquals(0xFAF8F0E2L, sepia.buttonContainer)
        assertEquals(0xFAF8F0E2L, sepia.menuContainer)
        assertEquals(0xE6FFFFFFL, sepia.buttonBorder)
        assertEquals(0x00000000L, sepia.buttonOutline)
        assertEquals(sepia.buttonBorder, sepia.menuBorder)
        assertEquals(sepia.buttonOutline, sepia.bubbleOutline)
        assertEquals(1, sepia.buttonShadowElevationDp)
        assertEquals(0x25000000L, sepia.buttonShadowColor)
        assertEquals(0x00000000L, sepia.buttonInnerShadowColor)
        assertEquals(1, sepia.bubbleShadowElevationDp)
        assertEquals(0x25000000L, sepia.bubbleShadowColor)
        assertEquals(0x00000000L, sepia.bubbleInnerShadowColor)
        assertEquals(0xE6141414L, dark.buttonContainer)
        assertEquals(0xE6141414L, dark.menuContainer)
        assertEquals(0xFF484848L, dark.buttonBorder)
        assertEquals(0x00000000L, dark.buttonOutline)
        assertEquals(dark.buttonBorder, dark.menuBorder)
        assertEquals(dark.buttonOutline, dark.bubbleOutline)
        assertEquals(1, dark.buttonShadowElevationDp)
        assertEquals(0x25000000L, dark.buttonShadowColor)
        assertEquals(0x00000000L, dark.buttonInnerShadowColor)
        assertEquals(1, dark.bubbleShadowElevationDp)
        assertEquals(0x25000000L, dark.bubbleShadowColor)
        assertEquals(0x00000000L, dark.bubbleInnerShadowColor)
    }

    @Test
    fun eInkModeUsesOpaquePureChromeColors() {
        val light = readerChromeColors(ReaderSettings(eInkMode = true), systemDark = false)
        val dark = readerChromeColors(ReaderSettings(theme = ReaderTheme.Dark, eInkMode = true), systemDark = false)

        assertEquals(0xFFFFFFFFL, light.buttonContainer)
        assertEquals(0xFF000000L, light.buttonBorder)
        assertEquals(0x00000000L, light.buttonOutline)
        assertEquals(0, light.buttonShadowElevationDp)
        assertEquals(0x00000000L, light.buttonShadowColor)
        assertEquals(0x00000000L, light.buttonInnerShadowColor)
        assertEquals(0, light.bubbleShadowElevationDp)
        assertEquals(0x00000000L, light.bubbleShadowColor)
        assertEquals(0x00000000L, light.bubbleInnerShadowColor)
        assertEquals(1f, readerButtonBorderWidthDp(light))
        assertEquals(1f, readerBubbleBorderWidthDp(light))
        assertEquals(0xFF000000L, light.buttonContent)
        assertEquals(0xFFFFFFFFL, light.menuContainer)
        assertEquals(0xFF000000L, light.menuContent)
        assertEquals(0xFF000000L, light.menuBorder)
        assertEquals(0xFF000000L, light.infoText)
        assertEquals(0xFF000000L, dark.buttonContainer)
        assertEquals(0xFFFFFFFFL, dark.buttonBorder)
        assertEquals(0, dark.buttonShadowElevationDp)
        assertEquals(0, dark.bubbleShadowElevationDp)
        assertEquals(0xFFFFFFFFL, dark.buttonContent)
        assertEquals(0xFFFFFFFFL, dark.menuBorder)
    }

}
