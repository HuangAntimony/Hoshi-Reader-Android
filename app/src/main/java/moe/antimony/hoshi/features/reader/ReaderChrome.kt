package moe.antimony.hoshi.features.reader

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

data class ReaderChromeState(
    val title: String,
    val currentCharacter: Int,
    val totalCharacters: Int,
    val backTargetCharacter: Int? = null,
    val forwardTargetCharacter: Int? = null,
    val statistics: ReaderStatisticsChromeState? = null,
) {
    fun progressText(
        settings: ReaderSettings,
        progressDisplay: ReaderProgressDisplay = ReaderProgressDisplay.characters(),
    ): String {
        val parts = mutableListOf<String>()
        if (settings.showCharacters) {
            parts += progressDisplay.countText(currentCharacter)
            if (totalCharacters > 0) {
                parts[parts.lastIndex] = progressDisplay.rangeText(currentCharacter, totalCharacters)
            }
        }
        if (settings.showPercentage) {
            val percent = if (totalCharacters > 0) {
                currentCharacter.toDouble() / totalCharacters.toDouble() * 100.0
            } else {
                0.0
            }
            parts += String.format(Locale.US, "%.2f%%", percent)
        }
        return parts.joinToString(separator = " ")
    }

    fun statisticsText(
        settings: ReaderSettings,
        progressDisplay: ReaderProgressDisplay = ReaderProgressDisplay.characters(),
    ): String {
        val statistics = statistics ?: return ""
        if (!settings.enableStatistics) return ""
        val parts = mutableListOf<String>()
        if (settings.showReadingSpeed) {
            parts += progressDisplay.speedText(statistics.readingSpeed)
        }
        if (settings.showReadingTime) {
            parts += statistics.readingTimeText()
        }
        return parts.joinToString(separator = " ")
    }
}

data class ReaderStatisticsChromeState(
    val readingSpeed: Int,
    val readingTimeSeconds: Double,
)

data class ReaderChromeColors(
    val buttonContainer: Long,
    val buttonBorder: Long,
    val buttonOutline: Long,
    val buttonShadowElevationDp: Int,
    val buttonShadowColor: Long,
    val buttonInnerShadowColor: Long,
    val buttonContent: Long,
    val menuContainer: Long,
    val menuContent: Long,
    val menuBorder: Long,
    val bubbleOutline: Long,
    val bubbleShadowElevationDp: Int,
    val bubbleShadowColor: Long,
    val bubbleInnerShadowColor: Long,
    val infoText: Long,
)

data class ReaderChromeLayout(
    val showProgressInBottomBar: Boolean,
    val showStatisticsInBottomBar: Boolean,
    val bottomCenterLineCount: Int,
    val bottomCenterMaxHeightDp: Int,
)

data class ReaderContentChromeInsets(
    val topDp: Int,
    val bottomDp: Int,
)

data class ReaderInfoBubbleMetrics(
    val horizontalPaddingDp: Int,
    val verticalPaddingDp: Int,
    val cornerRadiusDp: Int,
)

data class ReaderChromeVisibility(
    val showTitleAndProgress: Boolean,
    val showBottomChrome: Boolean,
    val showStatisticsToggle: Boolean,
    val showSasayakiToggle: Boolean,
    val showBackJump: Boolean,
    val showForwardJump: Boolean,
)

enum class ReaderMenuDestination {
    Appearance,
    GoTo,
    Statistics,
    Sasayaki,
}

data class ReaderSasayakiBottomPlaybackControls(
    val visible: Boolean,
    val rowHeightDp: Int,
    val buttonWidthDp: Int,
    val iconSizeDp: Int,
    val horizontalPaddingDp: Int,
)

enum class ReaderSasayakiBottomSkipButtonAction {
    Backward,
    Forward,
}

data class ReaderSasayakiBottomSkipButtonActions(
    val left: ReaderSasayakiBottomSkipButtonAction,
    val right: ReaderSasayakiBottomSkipButtonAction,
)

data class ReaderFocusModeToggleArea(
    val visible: Boolean,
    val horizontalPaddingDp: Int,
)

data class ReaderSystemBarVisibility(
    val showStatusBar: Boolean,
    val showNavigationBar: Boolean,
)

data class ReaderTopTitlePaddingDp(
    val startDp: Int,
    val endDp: Int,
)

data class ReaderBottomChromeMetrics(
    val buttonSizeDp: Int,
    val topSasayakiButtonSizeDp: Int,
    val topStatisticsButtonSizeDp: Int,
    val topButtonOffsetYDp: Int,
    val topButtonHorizontalInsetDp: Int,
    val primaryIconSizeDp: Int,
    val secondaryIconSizeDp: Int,
    val topSasayakiIconSizeDp: Int,
    val topStatisticsIconSizeDp: Int,
    val horizontalPaddingDp: Int,
    val bottomPaddingDp: Int,
    val bottomSafeAreaDp: Int,
    val menuButtonGapDp: Int,
    val trailingButtonSpacingDp: Int,
    val menuWidthDp: Int,
    val menuVerticalPaddingDp: Int,
    val menuItemHorizontalPaddingDp: Int,
    val menuItemVerticalPaddingDp: Int,
    val menuItemIconBoxSizeDp: Int,
    val menuItemSpacingDp: Int,
) {
    val menuBottomOffsetDp: Int = buttonSizeDp + bottomPaddingDp + bottomSafeAreaDp + menuButtonGapDp
}

fun readerChromeLayout(
    state: ReaderChromeState,
    settings: ReaderSettings,
    progressDisplay: ReaderProgressDisplay = ReaderProgressDisplay.characters(),
    showSasayakiToggle: Boolean = false,
    showStatisticsToggle: Boolean = false,
    focusMode: Boolean = false,
): ReaderChromeLayout {
    val progress = state.progressText(settings, progressDisplay)
    val statistics = state.statisticsText(settings, progressDisplay)
    val showProgressInBottomBar = !settings.alwaysShowProgress && !settings.showProgressTop && progress.isNotBlank()
    val showStatisticsInBottomBar = statistics.isNotBlank()
    return ReaderChromeLayout(
        showProgressInBottomBar = showProgressInBottomBar,
        showStatisticsInBottomBar = showStatisticsInBottomBar,
        bottomCenterLineCount = listOf(showStatisticsInBottomBar, showProgressInBottomBar).count { it },
        bottomCenterMaxHeightDp = ReaderBottomChromeButtonSizeDp,
    )
}

fun readerShowsProgressInTopBubble(settings: ReaderSettings): Boolean =
    !settings.alwaysShowProgress && settings.showProgressTop

@Suppress("UNUSED_PARAMETER")
fun readerBottomSafeProgressText(
    state: ReaderChromeState,
    settings: ReaderSettings,
    focusMode: Boolean = false,
    progressDisplay: ReaderProgressDisplay = ReaderProgressDisplay.characters(),
): String = if (settings.alwaysShowProgress) state.progressText(settings, progressDisplay) else ""

@Suppress("UNUSED_PARAMETER")
fun readerContentChromeInsets(
    state: ReaderChromeState? = null,
    settings: ReaderSettings? = null,
    showSasayakiToggle: Boolean = false,
    showStatisticsToggle: Boolean = false,
    focusMode: Boolean = false,
    topSystemInsetDp: Int = 0,
): ReaderContentChromeInsets =
    ReaderContentChromeInsets(
        topDp = ReaderContentTopReservedSpaceDp + ReaderWebViewTopPaddingDp,
        bottomDp = ReaderBottomGestureSafeAreaDp,
    )

fun readerTopInfoOverlayPaddingDp(
    topSystemInsetDp: Int,
    focusMode: Boolean,
): Int =
    if (focusMode) {
        ReaderFocusTopOverlayPaddingDp
    } else if (topSystemInsetDp > 0) {
        topSystemInsetDp
    } else {
        ReaderTopInfoFallbackPaddingDp
    }

fun readerShouldShowTitleAndProgress(
    focusMode: Boolean,
    currentStatusBarInsetDp: Int,
    stableStatusBarInsetDp: Int,
): Boolean =
    !focusMode &&
        currentStatusBarInsetDp > 0 &&
        (stableStatusBarInsetDp <= 0 || currentStatusBarInsetDp >= stableStatusBarInsetDp)

fun readerInfoBubbleMetrics(): ReaderInfoBubbleMetrics =
    ReaderInfoBubbleMetrics(
        horizontalPaddingDp = 12,
        verticalPaddingDp = 6,
        cornerRadiusDp = 24,
    )

fun readerSystemBarVisibility(focusMode: Boolean): ReaderSystemBarVisibility =
    ReaderSystemBarVisibility(
        showStatusBar = !focusMode,
        showNavigationBar = false,
    )

fun readerChromeVisibility(
    focusMode: Boolean,
    hasStatisticsToggle: Boolean,
    hasSasayakiToggle: Boolean,
    hasBackJump: Boolean,
    hasForwardJump: Boolean,
): ReaderChromeVisibility =
    ReaderChromeVisibility(
        showTitleAndProgress = !focusMode,
        showBottomChrome = !focusMode,
        showStatisticsToggle = focusMode && hasStatisticsToggle,
        showSasayakiToggle = focusMode && hasSasayakiToggle,
        showBackJump = focusMode && hasBackJump,
        showForwardJump = focusMode && hasForwardJump,
    )

fun readerBottomChromeMetrics(): ReaderBottomChromeMetrics =
    ReaderBottomChromeMetrics(
        buttonSizeDp = ReaderBottomChromeButtonSizeDp,
        topSasayakiButtonSizeDp = ReaderTopButtonSizeDp,
        topStatisticsButtonSizeDp = ReaderTopButtonSizeDp,
        topButtonOffsetYDp = ReaderTopButtonOffsetYDp,
        topButtonHorizontalInsetDp = ReaderTopButtonHorizontalInsetDp,
        primaryIconSizeDp = 28,
        secondaryIconSizeDp = 28,
        topSasayakiIconSizeDp = ReaderTopButtonIconSizeDp,
        topStatisticsIconSizeDp = ReaderTopButtonIconSizeDp,
        horizontalPaddingDp = 22,
        bottomPaddingDp = 2,
        bottomSafeAreaDp = ReaderBottomGestureSafeAreaDp,
        menuButtonGapDp = ReaderMenuButtonGapDp,
        trailingButtonSpacingDp = 8,
        menuWidthDp = 204,
        menuVerticalPaddingDp = 4,
        menuItemHorizontalPaddingDp = 16,
        menuItemVerticalPaddingDp = 8,
        menuItemIconBoxSizeDp = 24,
        menuItemSpacingDp = 12,
    )

fun readerBottomMenuVisualOrder(
    showStatistics: Boolean,
    showSasayaki: Boolean,
): List<ReaderMenuDestination> = buildList {
    if (showSasayaki) add(ReaderMenuDestination.Sasayaki)
    if (showStatistics) add(ReaderMenuDestination.Statistics)
    add(ReaderMenuDestination.GoTo)
    add(ReaderMenuDestination.Appearance)
}

fun readerSasayakiBottomPlaybackControls(
    settings: moe.antimony.hoshi.features.sasayaki.SasayakiSettings,
    hasAudio: Boolean,
    metrics: ReaderBottomChromeMetrics,
): ReaderSasayakiBottomPlaybackControls =
    ReaderSasayakiBottomPlaybackControls(
        visible = settings.enabled && settings.showReaderBottomPlaybackControls && hasAudio,
        rowHeightDp = metrics.bottomSafeAreaDp,
        buttonWidthDp = 40,
        iconSizeDp = 14,
        horizontalPaddingDp = 18,
    )

fun readerSasayakiBottomSkipButtonActions(
    verticalWriting: Boolean,
    reverseVerticalReaderSkipButtons: Boolean,
): ReaderSasayakiBottomSkipButtonActions =
    if (verticalWriting && reverseVerticalReaderSkipButtons) {
        ReaderSasayakiBottomSkipButtonActions(
            left = ReaderSasayakiBottomSkipButtonAction.Forward,
            right = ReaderSasayakiBottomSkipButtonAction.Backward,
        )
    } else {
        ReaderSasayakiBottomSkipButtonActions(
            left = ReaderSasayakiBottomSkipButtonAction.Backward,
            right = ReaderSasayakiBottomSkipButtonAction.Forward,
        )
    }

fun readerFocusModeToggleArea(
    metrics: ReaderBottomChromeMetrics,
    focusMode: Boolean,
): ReaderFocusModeToggleArea {
    return ReaderFocusModeToggleArea(visible = false, horizontalPaddingDp = 0)
}

fun readerTopTitlePaddingDp(
    hasStartControl: Boolean,
    hasEndControl: Boolean,
): ReaderTopTitlePaddingDp {
    val sidePadding = if (hasStartControl || hasEndControl) ReaderTopTitleControlPaddingDp else 0
    return ReaderTopTitlePaddingDp(startDp = sidePadding, endDp = sidePadding)
}

fun readerJumpTargetText(
    character: Int,
    progressDisplay: ReaderProgressDisplay = ReaderProgressDisplay.characters(),
): String = progressDisplay.jumpTargetText(character)

fun readerJumpBackIcon(): ImageVector = Icons.AutoMirrored.Rounded.Undo

fun readerJumpForwardIcon(): ImageVector = Icons.AutoMirrored.Rounded.Redo

fun readerChromeColors(settings: ReaderSettings, systemDark: Boolean): ReaderChromeColors {
    val colors = when {
        settings.eInkMode && settings.usesDarkInterface(systemDark) -> ReaderChromeColors(
            buttonContainer = 0xFF000000,
            buttonBorder = 0xFFFFFFFF,
            buttonOutline = 0x00000000,
            buttonShadowElevationDp = 0,
            buttonShadowColor = 0x00000000,
            buttonInnerShadowColor = 0x00000000,
            buttonContent = 0xFFFFFFFF,
            menuContainer = 0xFF000000,
            menuContent = 0xFFFFFFFF,
            menuBorder = 0xFFFFFFFF,
            bubbleOutline = 0x00000000,
            bubbleShadowElevationDp = 0,
            bubbleShadowColor = 0x00000000,
            bubbleInnerShadowColor = 0x00000000,
            infoText = 0xFFFFFFFF,
        )
        settings.eInkMode -> ReaderChromeColors(
            buttonContainer = 0xFFFFFFFF,
            buttonBorder = 0xFF000000,
            buttonOutline = 0x00000000,
            buttonShadowElevationDp = 0,
            buttonShadowColor = 0x00000000,
            buttonInnerShadowColor = 0x00000000,
            buttonContent = 0xFF000000,
            menuContainer = 0xFFFFFFFF,
            menuContent = 0xFF000000,
            menuBorder = 0xFF000000,
            bubbleOutline = 0x00000000,
            bubbleShadowElevationDp = 0,
            bubbleShadowColor = 0x00000000,
            bubbleInnerShadowColor = 0x00000000,
            infoText = 0xFF000000,
        )
        settings.theme == ReaderTheme.Sepia && settings.sepiaInvertInDark && systemDark -> ReaderChromeColors(
            buttonContainer = 0xE6191713,
            buttonBorder = 0xFF4A4438,
            buttonOutline = 0x00000000,
            buttonShadowElevationDp = 1,
            buttonShadowColor = 0x25000000,
            buttonInnerShadowColor = 0x00000000,
            buttonContent = 0xFFF2E2C9,
            menuContainer = 0xE6191713,
            menuContent = 0xFFF2E2C9,
            menuBorder = 0xFF4A4438,
            bubbleOutline = 0x00000000,
            bubbleShadowElevationDp = 1,
            bubbleShadowColor = 0x25000000,
            bubbleInnerShadowColor = 0x00000000,
            infoText = 0xCCF2E2C9,
        )
        settings.usesDarkInterface(systemDark) -> ReaderChromeColors(
            buttonContainer = 0xE6141414,
            buttonBorder = 0xFF484848,
            buttonOutline = 0x00000000,
            buttonShadowElevationDp = 1,
            buttonShadowColor = 0x25000000,
            buttonInnerShadowColor = 0x00000000,
            buttonContent = 0xFFF4F4F4,
            menuContainer = 0xE6141414,
            menuContent = 0xFFF4F4F4,
            menuBorder = 0xFF484848,
            bubbleOutline = 0x00000000,
            bubbleShadowElevationDp = 1,
            bubbleShadowColor = 0x25000000,
            bubbleInnerShadowColor = 0x00000000,
            infoText = 0xCCFFFFFF,
        )
        settings.usesSepiaLightContent(systemDark) -> ReaderChromeColors(
            buttonContainer = 0xFAF8F0E2,
            buttonBorder = 0xE6FFFFFF,
            buttonOutline = 0x00000000,
            buttonShadowElevationDp = 1,
            buttonShadowColor = 0x25000000,
            buttonInnerShadowColor = 0x00000000,
            buttonContent = 0xFF1F170D,
            menuContainer = 0xFAF8F0E2,
            menuContent = 0xFF1F170D,
            menuBorder = 0xE6FFFFFF,
            bubbleOutline = 0x00000000,
            bubbleShadowElevationDp = 1,
            bubbleShadowColor = 0x25000000,
            bubbleInnerShadowColor = 0x00000000,
            infoText = 0xB35C5448,
        )
        else -> ReaderChromeColors(
            buttonContainer = 0xFAFCFCFC,
            buttonBorder = 0xE6FFFFFF,
            buttonOutline = 0x00000000,
            buttonShadowElevationDp = 1,
            buttonShadowColor = 0x25000000,
            buttonInnerShadowColor = 0x00000000,
            buttonContent = 0xFF111111,
            menuContainer = 0xFAFCFCFC,
            menuContent = 0xFF111111,
            menuBorder = 0xE6FFFFFF,
            bubbleOutline = 0x00000000,
            bubbleShadowElevationDp = 1,
            bubbleShadowColor = 0x25000000,
            bubbleInnerShadowColor = 0x00000000,
            infoText = 0xB3111111,
        )
    }
    return if (!settings.eInkMode && settings.theme == ReaderTheme.Custom) {
        colors.copy(infoText = settings.customInfoColor)
    } else {
        colors
    }
}

private fun ReaderStatisticsChromeState.readingTimeText(): String {
    val totalMinutes = (readingTimeSeconds / 60.0).toLong()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return "$hours:${minutes.toString().padStart(2, '0')}"
}

private const val ReaderBottomChromeButtonSizeDp = 44
private const val ReaderBottomGestureSafeAreaDp = 18
private const val ReaderMenuButtonGapDp = 8
private const val ReaderContentTopReservedSpaceDp = 30
private const val ReaderTopInfoFallbackPaddingDp = 52
private const val ReaderWebViewTopPaddingDp = 4
private const val ReaderFocusTopOverlayPaddingDp = 0
private const val ReaderTopButtonSizeDp = 30
private const val ReaderTopButtonIconSizeDp = 22
private const val ReaderTopButtonOffsetYDp = 4
private const val ReaderTopButtonHorizontalInsetDp = 8
private const val ReaderTopTitleControlPaddingDp = 42

fun readerTopButtonContainerColor(): Long = 0x00000000

fun readerButtonBorderWidthDp(colors: ReaderChromeColors): Float =
    if (colors.buttonShadowElevationDp > 0) 0.75f else 1f

fun readerBubbleBorderWidthDp(colors: ReaderChromeColors): Float =
    if (colors.bubbleShadowElevationDp > 0) 0.75f else 1f
