package moe.antimony.hoshi.features.reader

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.util.Locale

data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.System,
    val eInkMode: Boolean = false,
    val sepiaInvertInDark: Boolean = false,
    val verticalWriting: Boolean = true,
    val selectedFont: String = ReaderFontManager.defaultMinchoFont,
    val fontSize: Int = 22,
    val hideFurigana: Boolean = false,
    val continuousMode: Boolean = false,
    val chapterSwipeDistance: Int = 20,
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
    val popupActionBar: Boolean = false,
    val popupFullWidth: Boolean = false,
    val popupSwipeToDismiss: Boolean = true,
    val popupSwipeThreshold: Int = 30,
    val volumeKeysTurnPages: Boolean = false,
    val reverseVolumeKeyDirection: Boolean = false,
) {
    val bottomOverlapPx: Int
        get() = if (verticalWriting) fontSize else 0

    val writingModeCss: String
        get() = if (verticalWriting) "vertical-rl" else "horizontal-tb"

    val imageWidthViewportRatio: Double
        get() = (100 - horizontalPadding).coerceAtLeast(1) / 100.0

    val continuousViewportHorizontalPaddingRatio: Double
        get() = if (continuousMode && verticalWriting) {
            horizontalPadding.coerceAtLeast(0) / 200.0
        } else {
            0.0
        }

    val continuousViewportVerticalPaddingRatio: Double
        get() = if (continuousMode && !verticalWriting) {
            verticalPadding.coerceAtLeast(0) / 200.0
        } else {
            0.0
        }

    val columnGapCss: String
        get() {
            if (verticalWriting) {
                return "calc(var(--hoshi-vertical-padding-gap, ${verticalPadding}vh) + ${bottomOverlapPx}px)"
            }
            return "${horizontalPadding}vw"
        }

    val pagePaddingCss: String
        get() = "var(--hoshi-vertical-padding-block, ${(verticalPadding / 2.0).cssNumber()}vh) ${(horizontalPadding / 2.0).cssNumber()}vw"

    val bottomPaddingCss: String
        get() = if (verticalWriting && bottomOverlapPx > 0) {
            "calc(var(--hoshi-vertical-padding-block, ${(verticalPadding / 2.0).cssNumber()}vh) + ${bottomOverlapPx}px)"
        } else {
            "var(--hoshi-vertical-padding-block, ${(verticalPadding / 2.0).cssNumber()}vh)"
        }

    val imageMaxWidthFallbackCss: String
        get() = "${100 - horizontalPadding}vw"

    val imageMaxHeightFallbackCss: String
        get() = "calc(var(--page-height, 100vh) - ${bottomOverlapPx}px)"

    val trailingSpacerHeightCss: String
        get() = if (verticalWriting) bottomPaddingCss else "0"

    val trailingSpacerWidthCss: String
        get() = if (verticalWriting) "0" else "${(horizontalPadding / 2.0).cssNumber()}vw"

    fun backgroundColor(systemDark: Boolean): Long {
        if (eInkMode) {
            return if (usesDarkInterface(systemDark)) 0xFF000000 else 0xFFFFFFFF
        }
        return when (theme) {
            ReaderTheme.System -> if (systemDark) 0xFF000000 else 0xFFFFFFFF
            ReaderTheme.Dark -> 0xFF000000
            ReaderTheme.Sepia -> if (sepiaInvertInDark && systemDark) 0xFF18150C else 0xFFF2E2C9
            ReaderTheme.Light -> 0xFFFFFFFF
        }
    }

    fun textColorCss(systemDark: Boolean): String {
        if (eInkMode) {
            return if (usesDarkInterface(systemDark)) "#fff" else "#000"
        }
        return when (theme) {
            ReaderTheme.System -> if (systemDark) "#fff" else "#000"
            ReaderTheme.Light -> "#000"
            ReaderTheme.Dark -> "#fff"
            ReaderTheme.Sepia -> if (sepiaInvertInDark && systemDark) "#F2E2C9" else "#332A1B"
        }
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
    ReaderTheme.Sepia -> sepiaInvertInDark && systemDark
}

interface ReaderSettingsLegacySource {
    fun load(): ReaderSettings
}

class ReaderSettingsStore(context: Context) : ReaderSettingsLegacySource {
    private val preferences = context.getSharedPreferences("reader-settings", Context.MODE_PRIVATE)

    override fun load(): ReaderSettings = ReaderSettings(
        theme = preferences.getString("theme", null)
            ?.let { saved -> ReaderTheme.entries.firstOrNull { it.label == saved } }
            ?: ReaderTheme.System,
        eInkMode = preferences.getBoolean("eInkMode", false),
        sepiaInvertInDark = preferences.getBoolean("sepiaInvertInDark", false),
        verticalWriting = preferences.getBoolean("verticalWriting", true),
        selectedFont = ReaderFontManager.normalizeDefaultFont(
            preferences.getString("selectedFont", null) ?: ReaderFontManager.defaultMinchoFont,
        ),
        fontSize = preferences.getInt("fontSize", 22),
        hideFurigana = preferences.getBoolean("readerHideFurigana", false),
        continuousMode = preferences.getBoolean("continuousMode", false),
        chapterSwipeDistance = preferences.getInt("chapterSwipeDistance", 20).coerceIn(10, 60),
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
        popupActionBar = preferences.getBoolean("popupActionBar", false),
        popupFullWidth = preferences.getBoolean("popupFullWidth", false),
        popupSwipeToDismiss = preferences.getBoolean("popupSwipeToDismiss", true),
        popupSwipeThreshold = preferences.getInt("popupSwipeThreshold", 30).coerceIn(20, 60),
        volumeKeysTurnPages = preferences.getBoolean("volumeKeysTurnPages", false),
        reverseVolumeKeyDirection = preferences.getBoolean("reverseVolumeKeyDirection", false),
    )

    fun save(settings: ReaderSettings) {
        preferences.edit()
            .putString("theme", settings.theme.label)
            .putBoolean("eInkMode", settings.eInkMode)
            .putBoolean("sepiaInvertInDark", settings.sepiaInvertInDark)
            .putBoolean("verticalWriting", settings.verticalWriting)
            .putString("selectedFont", settings.selectedFont)
            .putInt("fontSize", settings.fontSize)
            .putBoolean("readerHideFurigana", settings.hideFurigana)
            .putBoolean("continuousMode", settings.continuousMode)
            .putInt("chapterSwipeDistance", settings.chapterSwipeDistance)
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
            .putBoolean("popupActionBar", settings.popupActionBar)
            .putBoolean("popupFullWidth", settings.popupFullWidth)
            .putBoolean("popupSwipeToDismiss", settings.popupSwipeToDismiss)
            .putInt("popupSwipeThreshold", settings.popupSwipeThreshold)
            .putBoolean("volumeKeysTurnPages", settings.volumeKeysTurnPages)
            .putBoolean("reverseVolumeKeyDirection", settings.reverseVolumeKeyDirection)
            .apply()
    }
}

private val Context.readerSettingsDataStore by preferencesDataStore(name = ReaderSettingsRepository.DataStoreName)

fun Context.readerSettingsRepository(): ReaderSettingsRepository =
    ReaderSettingsRepository(
        dataStore = readerSettingsDataStore,
        legacySource = ReaderSettingsStore(this),
    )

class ReaderSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val legacySource: ReaderSettingsLegacySource? = null,
) {
    val settings: Flow<ReaderSettings> = dataStore.data
        .onStart { migrateLegacySettingsIfNeeded() }
        .map { preferences -> preferences.toReaderSettings() }

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        migrateLegacySettingsIfNeeded()
        dataStore.edit { preferences ->
            val current = preferences.toReaderSettings()
            preferences.writeReaderSettings(transform(current))
            preferences[KEY_MIGRATED_FROM_SHARED_PREFERENCES] = true
        }
    }

    private suspend fun migrateLegacySettingsIfNeeded() {
        dataStore.edit { preferences ->
            if (preferences[KEY_MIGRATED_FROM_SHARED_PREFERENCES] == true) return@edit
            preferences.writeReaderSettings(legacySource?.load() ?: ReaderSettings())
            preferences[KEY_MIGRATED_FROM_SHARED_PREFERENCES] = true
        }
    }

    private fun Preferences.toReaderSettings(): ReaderSettings =
        ReaderSettings(
            theme = this[KEY_THEME]
                ?.let { saved -> ReaderTheme.entries.firstOrNull { it.label == saved } }
                ?: ReaderTheme.System,
            eInkMode = this[KEY_E_INK_MODE] ?: false,
            sepiaInvertInDark = this[KEY_SEPIA_INVERT_IN_DARK] ?: false,
            verticalWriting = this[KEY_VERTICAL_WRITING] ?: true,
            selectedFont = ReaderFontManager.normalizeDefaultFont(
                this[KEY_SELECTED_FONT] ?: ReaderFontManager.defaultMinchoFont,
            ),
            fontSize = this[KEY_FONT_SIZE] ?: 22,
            hideFurigana = this[KEY_HIDE_FURIGANA] ?: false,
            continuousMode = this[KEY_CONTINUOUS_MODE] ?: false,
            chapterSwipeDistance = (this[KEY_CHAPTER_SWIPE_DISTANCE] ?: 20).coerceIn(10, 60),
            horizontalPadding = this[KEY_HORIZONTAL_PADDING] ?: 5,
            verticalPadding = this[KEY_VERTICAL_PADDING] ?: 0,
            avoidPageBreak = this[KEY_AVOID_PAGE_BREAK] ?: false,
            justifyText = this[KEY_JUSTIFY_TEXT] ?: false,
            layoutAdvanced = this[KEY_LAYOUT_ADVANCED] ?: false,
            lineHeight = (this[KEY_LINE_HEIGHT] ?: 1.65f).toDouble(),
            characterSpacing = (this[KEY_CHARACTER_SPACING] ?: 0f).toDouble(),
            showTitle = this[KEY_SHOW_TITLE] ?: true,
            showCharacters = this[KEY_SHOW_CHARACTERS] ?: true,
            showPercentage = this[KEY_SHOW_PERCENTAGE] ?: true,
            showProgressTop = this[KEY_SHOW_PROGRESS_TOP] ?: true,
            popupWidth = this[KEY_POPUP_WIDTH] ?: 320,
            popupHeight = this[KEY_POPUP_HEIGHT] ?: 250,
            popupActionBar = this[KEY_POPUP_ACTION_BAR] ?: false,
            popupFullWidth = this[KEY_POPUP_FULL_WIDTH] ?: false,
            popupSwipeToDismiss = this[KEY_POPUP_SWIPE_TO_DISMISS] ?: true,
            popupSwipeThreshold = (this[KEY_POPUP_SWIPE_THRESHOLD] ?: 30).coerceIn(20, 60),
            volumeKeysTurnPages = this[KEY_VOLUME_KEYS_TURN_PAGES] ?: false,
            reverseVolumeKeyDirection = this[KEY_REVERSE_VOLUME_KEY_DIRECTION] ?: false,
        )

    private fun MutablePreferences.writeReaderSettings(settings: ReaderSettings) {
        this[KEY_THEME] = settings.theme.label
        this[KEY_E_INK_MODE] = settings.eInkMode
        this[KEY_SEPIA_INVERT_IN_DARK] = settings.sepiaInvertInDark
        this[KEY_VERTICAL_WRITING] = settings.verticalWriting
        this[KEY_SELECTED_FONT] = settings.selectedFont
        this[KEY_FONT_SIZE] = settings.fontSize
        this[KEY_HIDE_FURIGANA] = settings.hideFurigana
        this[KEY_CONTINUOUS_MODE] = settings.continuousMode
        this[KEY_CHAPTER_SWIPE_DISTANCE] = settings.chapterSwipeDistance
        this[KEY_HORIZONTAL_PADDING] = settings.horizontalPadding
        this[KEY_VERTICAL_PADDING] = settings.verticalPadding
        this[KEY_AVOID_PAGE_BREAK] = settings.avoidPageBreak
        this[KEY_JUSTIFY_TEXT] = settings.justifyText
        this[KEY_LAYOUT_ADVANCED] = settings.layoutAdvanced
        this[KEY_LINE_HEIGHT] = settings.lineHeight.toFloat()
        this[KEY_CHARACTER_SPACING] = settings.characterSpacing.toFloat()
        this[KEY_SHOW_TITLE] = settings.showTitle
        this[KEY_SHOW_CHARACTERS] = settings.showCharacters
        this[KEY_SHOW_PERCENTAGE] = settings.showPercentage
        this[KEY_SHOW_PROGRESS_TOP] = settings.showProgressTop
        this[KEY_POPUP_WIDTH] = settings.popupWidth
        this[KEY_POPUP_HEIGHT] = settings.popupHeight
        this[KEY_POPUP_ACTION_BAR] = settings.popupActionBar
        this[KEY_POPUP_FULL_WIDTH] = settings.popupFullWidth
        this[KEY_POPUP_SWIPE_TO_DISMISS] = settings.popupSwipeToDismiss
        this[KEY_POPUP_SWIPE_THRESHOLD] = settings.popupSwipeThreshold
        this[KEY_VOLUME_KEYS_TURN_PAGES] = settings.volumeKeysTurnPages
        this[KEY_REVERSE_VOLUME_KEY_DIRECTION] = settings.reverseVolumeKeyDirection
    }

    companion object {
        const val DataStoreName = "reader-settings"

        private val KEY_MIGRATED_FROM_SHARED_PREFERENCES =
            booleanPreferencesKey("readerSettingsMigratedFromSharedPreferences")
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_E_INK_MODE = booleanPreferencesKey("eInkMode")
        private val KEY_SEPIA_INVERT_IN_DARK = booleanPreferencesKey("sepiaInvertInDark")
        private val KEY_VERTICAL_WRITING = booleanPreferencesKey("verticalWriting")
        private val KEY_SELECTED_FONT = stringPreferencesKey("selectedFont")
        private val KEY_FONT_SIZE = intPreferencesKey("fontSize")
        private val KEY_HIDE_FURIGANA = booleanPreferencesKey("readerHideFurigana")
        private val KEY_CONTINUOUS_MODE = booleanPreferencesKey("continuousMode")
        private val KEY_CHAPTER_SWIPE_DISTANCE = intPreferencesKey("chapterSwipeDistance")
        private val KEY_HORIZONTAL_PADDING = intPreferencesKey("layoutHorizontalPadding")
        private val KEY_VERTICAL_PADDING = intPreferencesKey("layoutVerticalPadding")
        private val KEY_AVOID_PAGE_BREAK = booleanPreferencesKey("avoidPageBreak")
        private val KEY_JUSTIFY_TEXT = booleanPreferencesKey("justifyText")
        private val KEY_LAYOUT_ADVANCED = booleanPreferencesKey("layoutAdvanced")
        private val KEY_LINE_HEIGHT = floatPreferencesKey("lineHeight")
        private val KEY_CHARACTER_SPACING = floatPreferencesKey("characterSpacing")
        private val KEY_SHOW_TITLE = booleanPreferencesKey("readerShowTitle")
        private val KEY_SHOW_CHARACTERS = booleanPreferencesKey("readerShowCharacters")
        private val KEY_SHOW_PERCENTAGE = booleanPreferencesKey("readerShowPercentage")
        private val KEY_SHOW_PROGRESS_TOP = booleanPreferencesKey("readerShowProgressTop")
        private val KEY_POPUP_WIDTH = intPreferencesKey("popupWidth")
        private val KEY_POPUP_HEIGHT = intPreferencesKey("popupHeight")
        private val KEY_POPUP_ACTION_BAR = booleanPreferencesKey("popupActionBar")
        private val KEY_POPUP_FULL_WIDTH = booleanPreferencesKey("popupFullWidth")
        private val KEY_POPUP_SWIPE_TO_DISMISS = booleanPreferencesKey("popupSwipeToDismiss")
        private val KEY_POPUP_SWIPE_THRESHOLD = intPreferencesKey("popupSwipeThreshold")
        private val KEY_VOLUME_KEYS_TURN_PAGES = booleanPreferencesKey("volumeKeysTurnPages")
        private val KEY_REVERSE_VOLUME_KEY_DIRECTION = booleanPreferencesKey("reverseVolumeKeyDirection")
    }
}

internal fun Double.cssNumber(): String =
    String.format(Locale.US, "%.1f", this)
