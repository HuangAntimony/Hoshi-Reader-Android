package moe.antimony.hoshi.features.reader

import android.content.Context
import kotlin.math.ceil
import java.util.Locale

data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.System,
    val verticalWriting: Boolean = true,
    val selectedFont: String = "Hiragino Mincho ProN",
    val fontSize: Int = 22,
    val hideFurigana: Boolean = false,
    val horizontalPadding: Int = 5,
    val verticalPadding: Int = 0,
    val avoidPageBreak: Boolean = false,
    val justifyText: Boolean = false,
    val layoutAdvanced: Boolean = false,
    val lineHeight: Double = 1.65,
    val characterSpacing: Double = 0.0,
    val showTitle: Boolean = true,
    val showCharacters: Boolean = true,
    val showPercentage: Boolean = true,
    val showProgressTop: Boolean = true,
    val popupWidth: Int = 320,
    val popupHeight: Int = 250,
    val popupFullWidth: Boolean = false,
    val popupSwipeToDismiss: Boolean = false,
    val popupSwipeThreshold: Int = 40,
) {
    val bottomOverlapPx: Int
        get() = if (verticalWriting) fontSize else 0

    val horizontalGlyphGuardPx: Int
        get() = if (verticalWriting) {
            0
        } else {
            ceil((fontSize * 0.4).coerceIn(10.0, 24.0)).toInt()
        }

    val writingModeCss: String
        get() = if (verticalWriting) "vertical-rl" else "horizontal-tb"

    val imageWidthViewportRatio: Double
        get() = (100 - horizontalPadding).coerceAtLeast(1) / 100.0

    val columnGapCss: String
        get() {
            val unit = if (verticalWriting) "vh" else "vw"
            val value = if (verticalWriting) verticalPadding else horizontalPadding
            return "calc(${value}${unit} + ${bottomOverlapPx}px)"
        }

    val columnWidthCss: String
        get() = if (verticalWriting) {
            "var(--page-width, 100vw)"
        } else {
            "calc(var(--page-width, 100vw) - ${horizontalPadding.toDouble().cssNumber()}vw)"
        }

    val bodyHeightCss: String
        get() = "var(--content-height, ${bodyHeightFallbackCss})"

    val bodyHeightFallbackCss: String
        get() = if (horizontalGlyphGuardPx > 0) {
            "calc(var(--page-height, 100vh) - ${horizontalGlyphGuardPx}px)"
        } else {
            "var(--page-height, 100vh)"
        }

    val pagePaddingCss: String
        get() = "${(verticalPadding / 2.0).cssNumber()}vh ${(horizontalPadding / 2.0).cssNumber()}vw"

    val bottomPaddingCss: String
        get() = if (verticalWriting && bottomOverlapPx > 0) {
            "calc(${(verticalPadding / 2.0).cssNumber()}vh + ${bottomOverlapPx}px)"
        } else {
            "${(verticalPadding / 2.0).cssNumber()}vh"
        }

    val imageMaxWidthFallbackCss: String
        get() = "${100 - horizontalPadding}vw"

    val imageMaxHeightFallbackCss: String
        get() = "calc(var(--page-height, 100vh) - ${bottomOverlapPx}px)"

    val trailingSpacerHeightCss: String
        get() = if (verticalWriting) bottomPaddingCss else "0"

    val trailingSpacerWidthCss: String
        get() = if (verticalWriting) "0" else "${(horizontalPadding / 2.0).cssNumber()}vw"

    val backgroundColor: Long
        get() = when (theme) {
            ReaderTheme.Dark -> 0xFF000000
            ReaderTheme.Sepia -> 0xFFF2E2C9
            else -> 0xFFFFFFFF
        }

    val textColorCss: String?
        get() = when (theme) {
            ReaderTheme.Light -> "#000"
            ReaderTheme.Dark -> "#fff"
            ReaderTheme.Sepia -> "#332A1B"
            ReaderTheme.System -> null
        }
}

enum class ReaderTheme(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
    Sepia("Sepia"),
}

fun ReaderSettings.usesDarkInterface(systemDark: Boolean): Boolean = when (theme) {
    ReaderTheme.System -> systemDark
    ReaderTheme.Light -> false
    ReaderTheme.Dark -> true
    ReaderTheme.Sepia -> false
}

class ReaderSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("reader-settings", Context.MODE_PRIVATE)

    fun load(): ReaderSettings = ReaderSettings(
        theme = preferences.getString("theme", null)
            ?.let { saved -> ReaderTheme.entries.firstOrNull { it.label == saved } }
            ?: ReaderTheme.System,
        verticalWriting = preferences.getBoolean("verticalWriting", true),
        selectedFont = preferences.getString("selectedFont", null) ?: "Hiragino Mincho ProN",
        fontSize = preferences.getInt("fontSize", 22),
        hideFurigana = preferences.getBoolean("readerHideFurigana", false),
        horizontalPadding = preferences.getInt("layoutHorizontalPadding", 5),
        verticalPadding = preferences.getInt("layoutVerticalPadding", 0),
        avoidPageBreak = preferences.getBoolean("avoidPageBreak", false),
        justifyText = preferences.getBoolean("justifyText", false),
        layoutAdvanced = preferences.getBoolean("layoutAdvanced", false),
        lineHeight = preferences.getFloat("lineHeight", 1.65f).toDouble(),
        characterSpacing = preferences.getFloat("characterSpacing", 0f).toDouble(),
        showTitle = preferences.getBoolean("readerShowTitle", true),
        showCharacters = preferences.getBoolean("readerShowCharacters", true),
        showPercentage = preferences.getBoolean("readerShowPercentage", true),
        showProgressTop = preferences.getBoolean("readerShowProgressTop", true),
        popupWidth = preferences.getInt("popupWidth", 320),
        popupHeight = preferences.getInt("popupHeight", 250),
        popupFullWidth = preferences.getBoolean("popupFullWidth", false),
        popupSwipeToDismiss = preferences.getBoolean("popupSwipeToDismiss", false),
        popupSwipeThreshold = preferences.getInt("popupSwipeThreshold", 40),
    )

    fun save(settings: ReaderSettings) {
        preferences.edit()
            .putString("theme", settings.theme.label)
            .putBoolean("verticalWriting", settings.verticalWriting)
            .putString("selectedFont", settings.selectedFont)
            .putInt("fontSize", settings.fontSize)
            .putBoolean("readerHideFurigana", settings.hideFurigana)
            .putInt("layoutHorizontalPadding", settings.horizontalPadding)
            .putInt("layoutVerticalPadding", settings.verticalPadding)
            .putBoolean("avoidPageBreak", settings.avoidPageBreak)
            .putBoolean("justifyText", settings.justifyText)
            .putBoolean("layoutAdvanced", settings.layoutAdvanced)
            .putFloat("lineHeight", settings.lineHeight.toFloat())
            .putFloat("characterSpacing", settings.characterSpacing.toFloat())
            .putBoolean("readerShowTitle", settings.showTitle)
            .putBoolean("readerShowCharacters", settings.showCharacters)
            .putBoolean("readerShowPercentage", settings.showPercentage)
            .putBoolean("readerShowProgressTop", settings.showProgressTop)
            .putInt("popupWidth", settings.popupWidth)
            .putInt("popupHeight", settings.popupHeight)
            .putBoolean("popupFullWidth", settings.popupFullWidth)
            .putBoolean("popupSwipeToDismiss", settings.popupSwipeToDismiss)
            .putInt("popupSwipeThreshold", settings.popupSwipeThreshold)
            .apply()
    }
}

internal fun Double.cssNumber(): String =
    String.format(Locale.US, "%.1f", this)
