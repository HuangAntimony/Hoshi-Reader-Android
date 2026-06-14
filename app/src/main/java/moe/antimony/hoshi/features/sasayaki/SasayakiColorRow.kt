package moe.antimony.hoshi.features.sasayaki

import androidx.annotation.StringRes
import moe.antimony.hoshi.R

internal enum class SasayakiColorRow(
    @get:StringRes val labelRes: Int,
    val defaultColor: Long,
) {
    LightText(R.string.reader_appearance_text_color, 0xFF000000),
    LightBackground(R.string.reader_appearance_background_color, 0x6687CEEB),
    DarkText(R.string.reader_appearance_text_color, 0xFFFFFFFF),
    DarkBackground(R.string.reader_appearance_background_color, 0x6687CEEB);

    fun color(settings: SasayakiSettings): Long =
        when (this) {
            LightText -> settings.lightTextColor
            LightBackground -> settings.lightBackgroundColor
            DarkText -> settings.darkTextColor
            DarkBackground -> settings.darkBackgroundColor
        }

    fun updated(settings: SasayakiSettings, color: Long): SasayakiSettings =
        when (this) {
            LightText -> settings.copy(lightTextColor = color)
            LightBackground -> settings.copy(lightBackgroundColor = color)
            DarkText -> settings.copy(darkTextColor = color)
            DarkBackground -> settings.copy(darkBackgroundColor = color)
        }

    companion object {
        val lightRows = listOf(LightText, LightBackground)
        val darkRows = listOf(DarkText, DarkBackground)
    }
}
