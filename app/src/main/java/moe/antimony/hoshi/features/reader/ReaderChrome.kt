package moe.antimony.hoshi.features.reader

import java.util.Locale

data class ReaderChromeState(
    val title: String,
    val currentCharacter: Int,
    val totalCharacters: Int,
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

}

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
)

data class ReaderBottomChromeMetrics(
    val buttonSizeDp: Int,
    val topSasayakiButtonSizeDp: Int,
    val primaryIconSizeDp: Int,
    val secondaryIconSizeDp: Int,
    val topSasayakiIconSizeDp: Int,
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
): ReaderChromeLayout {
    val progress = state.progressText(settings)
    return ReaderChromeLayout(
        topWebViewPaddingDp = readerWebViewTopPaddingDp(state, settings, showSasayakiToggle),
        showProgressInBottomBar = !settings.showProgressTop && progress.isNotBlank(),
    )
}

fun readerWebViewTopPaddingDp(
    state: ReaderChromeState,
    settings: ReaderSettings,
    showSasayakiToggle: Boolean = false,
): Int {
    val progress = state.progressText(settings)
    val textRows = listOf(
        settings.showTitle,
        settings.showProgressTop && progress.isNotBlank(),
    ).count { it }
    val textHeight = textRows * ReaderChromeLineHeightDp
    val buttonHeight = if (showSasayakiToggle) ReaderTopSasayakiButtonSizeDp else 0
    return ReaderWebViewTopBasePaddingDp + maxOf(textHeight, buttonHeight)
}

fun readerBottomChromeMetrics(): ReaderBottomChromeMetrics =
    ReaderBottomChromeMetrics(
        buttonSizeDp = 44,
        topSasayakiButtonSizeDp = ReaderTopSasayakiButtonSizeDp,
        primaryIconSizeDp = 28,
        secondaryIconSizeDp = 28,
        topSasayakiIconSizeDp = 20,
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
    settings.theme == ReaderTheme.Sepia -> ReaderChromeColors(
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

private const val ReaderWebViewTopBasePaddingDp = 4
private const val ReaderChromeLineHeightDp = 20
private const val ReaderTopSasayakiButtonSizeDp = 36
