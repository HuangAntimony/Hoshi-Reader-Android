package moe.antimony.hoshi.features.reader

import android.content.Context
import java.util.Locale

data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.System,
    val verticalWriting: Boolean = true,
    val selectedFont: String = "Hiragino Mincho ProN",
    val fontSize: Int = 22,
    val horizontalPadding: Int = 5,
    val verticalPadding: Int = 0,
    val lineHeight: Double = 1.65,
) {
    val bottomOverlapPx: Int
        get() = if (verticalWriting) fontSize else 0

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

class ReaderSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("reader-settings", Context.MODE_PRIVATE)

    fun load(): ReaderSettings = ReaderSettings(
        theme = preferences.getString("theme", null)
            ?.let { saved -> ReaderTheme.entries.firstOrNull { it.label == saved } }
            ?: ReaderTheme.System,
        verticalWriting = preferences.getBoolean("verticalWriting", true),
        selectedFont = preferences.getString("selectedFont", null) ?: "Hiragino Mincho ProN",
        fontSize = preferences.getInt("fontSize", 22),
        horizontalPadding = preferences.getInt("layoutHorizontalPadding", 5),
        verticalPadding = preferences.getInt("layoutVerticalPadding", 0),
        lineHeight = preferences.getFloat("lineHeight", 1.65f).toDouble(),
    )

    fun save(settings: ReaderSettings) {
        preferences.edit()
            .putString("theme", settings.theme.label)
            .putBoolean("verticalWriting", settings.verticalWriting)
            .putString("selectedFont", settings.selectedFont)
            .putInt("fontSize", settings.fontSize)
            .putInt("layoutHorizontalPadding", settings.horizontalPadding)
            .putInt("layoutVerticalPadding", settings.verticalPadding)
            .putFloat("lineHeight", settings.lineHeight.toFloat())
            .apply()
    }
}

internal fun Double.cssNumber(): String =
    String.format(Locale.US, "%.1f", this)
