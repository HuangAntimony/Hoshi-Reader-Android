package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

data class SasayakiSettings(
    val enabled: Boolean = false,
    val showReaderToggle: Boolean = false,
    val copyAudiobookToPrivateStorage: Boolean = false,
    val autoScroll: Boolean = true,
    val autoPause: Boolean = true,
    val lightTextColor: Long = 0xFF000000,
    val lightBackgroundColor: Long = 0x6687CEEB,
    val darkTextColor: Long = 0xFFFFFFFF,
    val darkBackgroundColor: Long = 0x6687CEEB,
) {
    fun textColor(dark: Boolean): Long =
        if (dark) darkTextColor else lightTextColor

    fun backgroundColor(dark: Boolean): Long =
        if (dark) darkBackgroundColor else lightBackgroundColor
}

class SasayakiSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("sasayaki-settings", Context.MODE_PRIVATE)

    fun load(): SasayakiSettings =
        SasayakiSettings(
            enabled = preferences.getBoolean(KEY_ENABLE, false),
            showReaderToggle = preferences.getBoolean(KEY_SHOW_READER_TOGGLE, false),
            copyAudiobookToPrivateStorage = preferences.getBoolean(KEY_COPY_AUDIOBOOK_TO_PRIVATE_STORAGE, false),
            autoScroll = preferences.getBoolean(KEY_AUTO_SCROLL, true),
            autoPause = preferences.getBoolean(KEY_AUTO_PAUSE, true),
            lightTextColor = preferences.getLong(KEY_LIGHT_TEXT_COLOR, 0xFF000000),
            lightBackgroundColor = preferences.getLong(KEY_LIGHT_BACKGROUND_COLOR, 0x6687CEEB),
            darkTextColor = preferences.getLong(KEY_DARK_TEXT_COLOR, 0xFFFFFFFF),
            darkBackgroundColor = preferences.getLong(KEY_DARK_BACKGROUND_COLOR, 0x6687CEEB),
        )

    fun save(settings: SasayakiSettings) {
        preferences.edit()
            .putBoolean(KEY_ENABLE, settings.enabled)
            .putBoolean(KEY_SHOW_READER_TOGGLE, settings.showReaderToggle)
            .putBoolean(KEY_COPY_AUDIOBOOK_TO_PRIVATE_STORAGE, settings.copyAudiobookToPrivateStorage)
            .putBoolean(KEY_AUTO_SCROLL, settings.autoScroll)
            .putBoolean(KEY_AUTO_PAUSE, settings.autoPause)
            .putLong(KEY_LIGHT_TEXT_COLOR, settings.lightTextColor)
            .putLong(KEY_LIGHT_BACKGROUND_COLOR, settings.lightBackgroundColor)
            .putLong(KEY_DARK_TEXT_COLOR, settings.darkTextColor)
            .putLong(KEY_DARK_BACKGROUND_COLOR, settings.darkBackgroundColor)
            .apply()
    }

    private companion object {
        const val KEY_ENABLE = "enableSasayaki"
        const val KEY_SHOW_READER_TOGGLE = "readerShowSasayakiToggle"
        const val KEY_COPY_AUDIOBOOK_TO_PRIVATE_STORAGE = "sasayakiCopyAudiobookToPrivateStorage"
        const val KEY_AUTO_SCROLL = "sasayakiAutoScroll"
        const val KEY_AUTO_PAUSE = "sasayakiAutoPause"
        const val KEY_LIGHT_TEXT_COLOR = "sasayakiTextColor"
        const val KEY_LIGHT_BACKGROUND_COLOR = "sasayakiBackgroundColor"
        const val KEY_DARK_TEXT_COLOR = "sasayakiDarkTextColor"
        const val KEY_DARK_BACKGROUND_COLOR = "sasayakiDarkBackgroundColor"
    }
}

internal fun Color.toSasayakiColorLong(): Long =
    toArgb().toLong() and 0xFFFFFFFF
