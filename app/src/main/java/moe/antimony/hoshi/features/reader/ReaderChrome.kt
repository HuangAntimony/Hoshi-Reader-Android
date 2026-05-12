package moe.antimony.hoshi.features.reader

import java.util.Locale

data class ReaderChromeState(
    val title: String,
    val currentCharacter: Int,
    val totalCharacters: Int,
    val statistics: ReaderStatisticsChromeState? = null,
) {
    fun progressText(settings: ReaderSettings): String {
        val parts = mutableListOf<String>()
        if (settings.showCharacters) {
            parts += currentCharacter.toString()
            if (totalCharacters > 0) {
                parts[parts.lastIndex] = "$currentCharacter / $totalCharacters"
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

    fun statisticsText(settings: ReaderSettings): String {
        val statistics = statistics ?: return ""
        if (!settings.enableStatistics) return ""
        val parts = mutableListOf<String>()
        if (settings.showReadingSpeed) {
            parts += "${statistics.readingSpeed} / h"
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
    val buttonContent: Long,
    val menuContainer: Long,
    val menuContent: Long,
    val menuBorder: Long,
    val infoText: Long,
)

data class ReaderChromeLayout(
    val topWebViewPaddingDp: Int,
    val showProgressInBottomBar: Boolean,
    val showStatisticsInBottomBar: Boolean,
    val bottomCenterLineCount: Int,
    val bottomCenterMaxHeightDp: Int,
)

data class ReaderSasayakiBottomSkipButtons(
    val visible: Boolean,
    val buttonSizeDp: Int,
    val iconSizeDp: Int,
    val adjacentSpacingDp: Int,
)

data class ReaderFocusModeToggleArea(
    val horizontalPaddingDp: Int,
)

data class ReaderBottomChromeMetrics(
    val buttonSizeDp: Int,
    val topSasayakiButtonSizeDp: Int,
    val topStatisticsButtonSizeDp: Int,
    val primaryIconSizeDp: Int,
    val secondaryIconSizeDp: Int,
    val topSasayakiIconSizeDp: Int,
    val topStatisticsIconSizeDp: Int,
    val horizontalPaddingDp: Int,
    val bottomPaddingDp: Int,
    val trailingButtonSpacingDp: Int,
    val menuWidthDp: Int,
    val menuVerticalPaddingDp: Int,
    val menuItemHorizontalPaddingDp: Int,
    val menuItemVerticalPaddingDp: Int,
    val menuItemIconBoxSizeDp: Int,
    val menuItemSpacingDp: Int,
) {
    val webViewBottomPaddingDp: Int = buttonSizeDp + bottomPaddingDp
    val menuBottomPaddingDp: Int = webViewBottomPaddingDp
}

fun readerChromeLayout(
    state: ReaderChromeState,
    settings: ReaderSettings,
    showSasayakiToggle: Boolean = false,
    showStatisticsToggle: Boolean = false,
    focusMode: Boolean = false,
): ReaderChromeLayout {
    val progress = state.progressText(settings)
    val statistics = state.statisticsText(settings)
    val showProgressInBottomBar = !settings.showProgressTop && progress.isNotBlank()
    val showStatisticsInBottomBar = statistics.isNotBlank()
    return ReaderChromeLayout(
        topWebViewPaddingDp = readerWebViewTopPaddingDp(
            state = state,
            settings = settings,
            showSasayakiToggle = showSasayakiToggle,
            showStatisticsToggle = showStatisticsToggle,
        ),
        showProgressInBottomBar = showProgressInBottomBar,
        showStatisticsInBottomBar = showStatisticsInBottomBar,
        bottomCenterLineCount = listOf(showStatisticsInBottomBar, showProgressInBottomBar).count { it },
        bottomCenterMaxHeightDp = ReaderBottomChromeButtonSizeDp,
    )
}

fun readerWebViewTopPaddingDp(
    state: ReaderChromeState,
    settings: ReaderSettings,
    showSasayakiToggle: Boolean = false,
    showStatisticsToggle: Boolean = false,
): Int {
    val progress = state.progressText(settings)
    val textRows = listOf(
        settings.showTitle,
        settings.showProgressTop && progress.isNotBlank(),
    ).count { it }
    val textHeight = textRows * ReaderChromeLineHeightDp
    val buttonHeight = if (showSasayakiToggle || showStatisticsToggle) ReaderTopButtonSizeDp else 0
    return ReaderWebViewTopBasePaddingDp + maxOf(textHeight, buttonHeight)
}

fun readerBottomChromeMetrics(): ReaderBottomChromeMetrics =
    ReaderBottomChromeMetrics(
        buttonSizeDp = ReaderBottomChromeButtonSizeDp,
        topSasayakiButtonSizeDp = ReaderTopButtonSizeDp,
        topStatisticsButtonSizeDp = ReaderTopButtonSizeDp,
        primaryIconSizeDp = 28,
        secondaryIconSizeDp = 28,
        topSasayakiIconSizeDp = ReaderTopButtonIconSizeDp,
        topStatisticsIconSizeDp = ReaderTopButtonIconSizeDp,
        horizontalPaddingDp = 22,
        bottomPaddingDp = 2,
        trailingButtonSpacingDp = 8,
        menuWidthDp = 204,
        menuVerticalPaddingDp = 4,
        menuItemHorizontalPaddingDp = 16,
        menuItemVerticalPaddingDp = 8,
        menuItemIconBoxSizeDp = 24,
        menuItemSpacingDp = 12,
    )

fun readerSasayakiBottomSkipButtons(
    settings: moe.antimony.hoshi.features.sasayaki.SasayakiSettings,
    hasAudio: Boolean,
    metrics: ReaderBottomChromeMetrics,
): ReaderSasayakiBottomSkipButtons =
    ReaderSasayakiBottomSkipButtons(
        visible = settings.enabled && settings.showReaderSkipButtons && hasAudio,
        buttonSizeDp = metrics.buttonSizeDp,
        iconSizeDp = metrics.secondaryIconSizeDp,
        adjacentSpacingDp = metrics.trailingButtonSpacingDp,
    )

fun readerFocusModeToggleArea(
    metrics: ReaderBottomChromeMetrics,
    sasayakiSkipButtons: ReaderSasayakiBottomSkipButtons,
    focusMode: Boolean,
): ReaderFocusModeToggleArea {
    if (focusMode) {
        return ReaderFocusModeToggleArea(horizontalPaddingDp = 0)
    }
    val sideClusterWidth = if (sasayakiSkipButtons.visible) {
        metrics.buttonSizeDp + sasayakiSkipButtons.adjacentSpacingDp + sasayakiSkipButtons.buttonSizeDp
    } else {
        metrics.buttonSizeDp
    }
    return ReaderFocusModeToggleArea(
        horizontalPaddingDp = metrics.horizontalPaddingDp + sideClusterWidth,
    )
}

fun readerChromeColors(settings: ReaderSettings, systemDark: Boolean): ReaderChromeColors = when {
    settings.eInkMode && settings.usesDarkInterface(systemDark) -> ReaderChromeColors(
        buttonContainer = 0xFF000000,
        buttonContent = 0xFFFFFFFF,
        menuContainer = 0xFF000000,
        menuContent = 0xFFFFFFFF,
        menuBorder = 0xFFFFFFFF,
        infoText = 0xFFFFFFFF,
    )
    settings.eInkMode -> ReaderChromeColors(
        buttonContainer = 0xFFFFFFFF,
        buttonContent = 0xFF000000,
        menuContainer = 0xFFFFFFFF,
        menuContent = 0xFF000000,
        menuBorder = 0xFF000000,
        infoText = 0xFF000000,
    )
    settings.usesDarkInterface(systemDark) -> ReaderChromeColors(
        buttonContainer = 0x661A1A1A,
        buttonContent = 0xFFF4F4F4,
        menuContainer = 0xF21F1F1F,
        menuContent = 0xFFF4F4F4,
        menuBorder = 0x26FFFFFF,
        infoText = 0x99FFFFFF,
    )
    settings.usesSepiaLightContent(systemDark) -> ReaderChromeColors(
        buttonContainer = 0x40FFFFFF,
        buttonContent = 0xFF1F170D,
        menuContainer = 0xFFF8EFDD,
        menuContent = 0xFF1F170D,
        menuBorder = 0xB3FFFFFF,
        infoText = 0x7A5C5448,
    )
    else -> ReaderChromeColors(
        buttonContainer = 0xD9FFFFFF,
        buttonContent = 0xFF111111,
        menuContainer = 0xFAFFFFFF,
        menuContent = 0xFF111111,
        menuBorder = 0xCCFFFFFF,
        infoText = 0x8A000000,
    )
}

private fun ReaderStatisticsChromeState.readingTimeText(): String {
    val totalMinutes = (readingTimeSeconds / 60.0).toLong()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return "$hours:${minutes.toString().padStart(2, '0')}"
}

private const val ReaderWebViewTopBasePaddingDp = 4
private const val ReaderChromeLineHeightDp = 20
private const val ReaderBottomChromeButtonSizeDp = 44
private const val ReaderTopButtonSizeDp = 36
private const val ReaderTopButtonIconSizeDp = 20
