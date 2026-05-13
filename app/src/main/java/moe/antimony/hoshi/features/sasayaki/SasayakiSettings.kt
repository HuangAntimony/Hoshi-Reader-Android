package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

enum class SasayakiReaderSkipButtonAction(
    val label: String,
    val seconds: Int?,
) {
    Cue("1 sentence", null),
    Seconds5("5 seconds", 5),
    Seconds10("10 seconds", 10),
    Seconds15("15 seconds", 15),
    Seconds30("30 seconds", 30);

    companion object {
        fun fromStorage(value: String?): SasayakiReaderSkipButtonAction =
            entries.firstOrNull { it.name == value } ?: Cue
    }
}

data class SasayakiSettings(
    val enabled: Boolean = true,
    val syncEnabled: Boolean = false,
    val showReaderToggle: Boolean = true,
    val showReaderSkipButtons: Boolean = true,
    val readerSkipButtonAction: SasayakiReaderSkipButtonAction = SasayakiReaderSkipButtonAction.Cue,
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

interface SasayakiSettingsLegacySource {
    fun load(): SasayakiSettings
}

class SasayakiSettingsStore(context: Context) : SasayakiSettingsLegacySource {
    private val preferences = context.getSharedPreferences("sasayaki-settings", Context.MODE_PRIVATE)

    override fun load(): SasayakiSettings =
        SasayakiSettings(
            enabled = preferences.getBoolean(KEY_ENABLE, true),
            syncEnabled = preferences.getBoolean(KEY_SYNC_ENABLED, false),
            showReaderToggle = preferences.getBoolean(KEY_SHOW_READER_TOGGLE, true),
            showReaderSkipButtons = preferences.getBoolean(KEY_SHOW_READER_SKIP_BUTTONS, true),
            readerSkipButtonAction = SasayakiReaderSkipButtonAction.fromStorage(
                preferences.getString(KEY_READER_SKIP_BUTTON_ACTION, null),
            ),
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
            .putBoolean(KEY_SYNC_ENABLED, settings.syncEnabled)
            .putBoolean(KEY_SHOW_READER_TOGGLE, settings.showReaderToggle)
            .putBoolean(KEY_SHOW_READER_SKIP_BUTTONS, settings.showReaderSkipButtons)
            .putString(KEY_READER_SKIP_BUTTON_ACTION, settings.readerSkipButtonAction.name)
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
        const val KEY_SYNC_ENABLED = "sasayakiEnableSync"
        const val KEY_SHOW_READER_TOGGLE = "readerShowSasayakiToggle"
        const val KEY_SHOW_READER_SKIP_BUTTONS = "sasayakiShowReaderSkipButtons"
        const val KEY_READER_SKIP_BUTTON_ACTION = "sasayakiReaderSkipButtonAction"
        const val KEY_COPY_AUDIOBOOK_TO_PRIVATE_STORAGE = "sasayakiCopyAudiobookToPrivateStorage"
        const val KEY_AUTO_SCROLL = "sasayakiAutoScroll"
        const val KEY_AUTO_PAUSE = "sasayakiAutoPause"
        const val KEY_LIGHT_TEXT_COLOR = "sasayakiTextColor"
        const val KEY_LIGHT_BACKGROUND_COLOR = "sasayakiBackgroundColor"
        const val KEY_DARK_TEXT_COLOR = "sasayakiDarkTextColor"
        const val KEY_DARK_BACKGROUND_COLOR = "sasayakiDarkBackgroundColor"
    }
}

private val Context.sasayakiSettingsDataStore by preferencesDataStore(name = SasayakiSettingsRepository.DataStoreName)

fun Context.sasayakiSettingsRepository(): SasayakiSettingsRepository =
    SasayakiSettingsRepository(
        dataStore = sasayakiSettingsDataStore,
        legacySource = SasayakiSettingsStore(this),
    )

class SasayakiSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val legacySource: SasayakiSettingsLegacySource? = null,
) {
    val settings: Flow<SasayakiSettings> = dataStore.data
        .onStart { migrateLegacySettingsIfNeeded() }
        .map { preferences -> preferences.toSasayakiSettings() }

    suspend fun update(transform: (SasayakiSettings) -> SasayakiSettings) {
        migrateLegacySettingsIfNeeded()
        dataStore.edit { preferences ->
            val current = preferences.toSasayakiSettings()
            preferences.writeSasayakiSettings(transform(current))
            preferences[KEY_MIGRATED_FROM_SHARED_PREFERENCES] = true
        }
    }

    private suspend fun migrateLegacySettingsIfNeeded() {
        dataStore.edit { preferences ->
            if (preferences[KEY_MIGRATED_FROM_SHARED_PREFERENCES] == true) return@edit
            preferences.writeSasayakiSettings(legacySource?.load() ?: SasayakiSettings())
            preferences[KEY_MIGRATED_FROM_SHARED_PREFERENCES] = true
        }
    }

    private fun Preferences.toSasayakiSettings(): SasayakiSettings =
        SasayakiSettings(
            enabled = this[KEY_ENABLE] ?: true,
            syncEnabled = this[KEY_SYNC_ENABLED] ?: false,
            showReaderToggle = this[KEY_SHOW_READER_TOGGLE] ?: true,
            showReaderSkipButtons = this[KEY_SHOW_READER_SKIP_BUTTONS] ?: true,
            readerSkipButtonAction = SasayakiReaderSkipButtonAction.fromStorage(this[KEY_READER_SKIP_BUTTON_ACTION]),
            copyAudiobookToPrivateStorage = this[KEY_COPY_AUDIOBOOK_TO_PRIVATE_STORAGE] ?: false,
            autoScroll = this[KEY_AUTO_SCROLL] ?: true,
            autoPause = this[KEY_AUTO_PAUSE] ?: true,
            lightTextColor = this[KEY_LIGHT_TEXT_COLOR] ?: 0xFF000000,
            lightBackgroundColor = this[KEY_LIGHT_BACKGROUND_COLOR] ?: 0x6687CEEB,
            darkTextColor = this[KEY_DARK_TEXT_COLOR] ?: 0xFFFFFFFF,
            darkBackgroundColor = this[KEY_DARK_BACKGROUND_COLOR] ?: 0x6687CEEB,
        )

    private fun MutablePreferences.writeSasayakiSettings(settings: SasayakiSettings) {
        this[KEY_ENABLE] = settings.enabled
        this[KEY_SYNC_ENABLED] = settings.syncEnabled
        this[KEY_SHOW_READER_TOGGLE] = settings.showReaderToggle
        this[KEY_SHOW_READER_SKIP_BUTTONS] = settings.showReaderSkipButtons
        this[KEY_READER_SKIP_BUTTON_ACTION] = settings.readerSkipButtonAction.name
        this[KEY_COPY_AUDIOBOOK_TO_PRIVATE_STORAGE] = settings.copyAudiobookToPrivateStorage
        this[KEY_AUTO_SCROLL] = settings.autoScroll
        this[KEY_AUTO_PAUSE] = settings.autoPause
        this[KEY_LIGHT_TEXT_COLOR] = settings.lightTextColor
        this[KEY_LIGHT_BACKGROUND_COLOR] = settings.lightBackgroundColor
        this[KEY_DARK_TEXT_COLOR] = settings.darkTextColor
        this[KEY_DARK_BACKGROUND_COLOR] = settings.darkBackgroundColor
    }

    companion object {
        const val DataStoreName = "sasayaki-settings"

        private val KEY_MIGRATED_FROM_SHARED_PREFERENCES =
            booleanPreferencesKey("sasayakiSettingsMigratedFromSharedPreferences")
        private val KEY_ENABLE = booleanPreferencesKey("enableSasayaki")
        private val KEY_SYNC_ENABLED = booleanPreferencesKey("sasayakiEnableSync")
        private val KEY_SHOW_READER_TOGGLE = booleanPreferencesKey("readerShowSasayakiToggle")
        private val KEY_SHOW_READER_SKIP_BUTTONS = booleanPreferencesKey("sasayakiShowReaderSkipButtons")
        private val KEY_READER_SKIP_BUTTON_ACTION = stringPreferencesKey("sasayakiReaderSkipButtonAction")
        private val KEY_COPY_AUDIOBOOK_TO_PRIVATE_STORAGE =
            booleanPreferencesKey("sasayakiCopyAudiobookToPrivateStorage")
        private val KEY_AUTO_SCROLL = booleanPreferencesKey("sasayakiAutoScroll")
        private val KEY_AUTO_PAUSE = booleanPreferencesKey("sasayakiAutoPause")
        private val KEY_LIGHT_TEXT_COLOR = longPreferencesKey("sasayakiTextColor")
        private val KEY_LIGHT_BACKGROUND_COLOR = longPreferencesKey("sasayakiBackgroundColor")
        private val KEY_DARK_TEXT_COLOR = longPreferencesKey("sasayakiDarkTextColor")
        private val KEY_DARK_BACKGROUND_COLOR = longPreferencesKey("sasayakiDarkBackgroundColor")
    }
}

internal fun Color.toSasayakiColorLong(): Long =
    toArgb().toLong() and 0xFFFFFFFF
