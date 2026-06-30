package moe.antimony.hoshi.features.reader

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.sync.StatisticsSyncMode
import moe.antimony.hoshi.profiles.ProfileRepository
import java.util.Locale

internal const val ReaderPopupScaleMin = 0.8
internal const val ReaderPopupScaleMax = 2.0
internal const val ReaderPopupScaleStep = 0.05
internal const val ReaderPopupScaleSliderSteps = 23
internal const val ReaderBottomSafeAreaDefaultDp = 18
internal const val ReaderBottomSafeAreaMinDp = ReaderBottomSafeAreaDefaultDp
internal const val ReaderBottomSafeAreaMaxDp = 72
internal const val ReaderBottomSafeAreaStepDp = 2

internal fun Double.coerceReaderPopupScale(): Double =
    coerceIn(ReaderPopupScaleMin, ReaderPopupScaleMax)

internal fun Int.coerceReaderBottomSafeAreaDp(): Int {
    val clamped = coerceIn(ReaderBottomSafeAreaMinDp, ReaderBottomSafeAreaMaxDp)
    val offset = clamped - ReaderBottomSafeAreaMinDp
    val lower = ReaderBottomSafeAreaMinDp + (offset / ReaderBottomSafeAreaStepDp) * ReaderBottomSafeAreaStepDp
    val upper = (lower + ReaderBottomSafeAreaStepDp).coerceAtMost(ReaderBottomSafeAreaMaxDp)
    return if (clamped - lower < upper - clamped) lower else upper
}

data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.System,
    val eInkMode: Boolean = false,
    val uiTheme: ReaderInterfaceTheme = ReaderInterfaceTheme.System,
    val systemLightSepia: Boolean = false,
    val sepiaInvertInDark: Boolean = false,
    val customBackgroundColor: Long = 0xFFFFFFFF,
    val customTextColor: Long = 0xFF000000,
    val customInfoColor: Long = 0xFF999999,
    val verticalWriting: Boolean = true,
    val selectedFont: String = ReaderFontManager.defaultMinchoFont,
    val fontSize: Int = 22,
    val hideFurigana: Boolean = false,
    val viewMode: ReaderViewMode = ReaderViewMode.Paginated,
    val visualNovelRevealSpeed: Int = 45,
    val visualNovelScreenMode: VisualNovelScreenMode = VisualNovelScreenMode.Block,
    val visualNovelSentencesPerScreen: Int = 1,
    val visualNovelPreserveDialogueBubbles: Boolean = false,
    val visualNovelClickAdvance: Boolean = false,
    val visualNovelMergeCrossScreenSasayakiCues: Boolean = false,
    val enableStatistics: Boolean = false,
    val showStatisticsTab: Boolean = true,
    val statisticsAutostartMode: StatisticsAutostartMode = StatisticsAutostartMode.Off,
    val statisticsSyncEnabled: Boolean = false,
    val statisticsSyncMode: StatisticsSyncMode = StatisticsSyncMode.Merge,
    val showStatisticsToggle: Boolean = false,
    val showReadingSpeed: Boolean = false,
    val showReadingTime: Boolean = false,
    val chapterSwipeDistance: Int = 20,
    val horizontalPadding: Int = 5,
    val verticalPadding: Int = 0,
    val bottomSafeAreaDp: Int = ReaderBottomSafeAreaDefaultDp,
    val avoidPageBreak: Boolean = false,
    val justifyText: Boolean = false,
    val blurImages: Boolean = false,
    val layoutAdvanced: Boolean = false,
    val lineHeight: Double = 1.65,
    val characterSpacing: Double = 0.0,
    val paragraphSpacing: Double = 0.0,
    val showTitle: Boolean = true,
    val showCharacters: Boolean = true,
    val showPercentage: Boolean = true,
    val alwaysShowProgress: Boolean = true,
    val showProgressTop: Boolean = true,
    val showReaderBackButton: Boolean = true,
    val popupWidth: Int = 320,
    val popupHeight: Int = 250,
    val popupScale: Double = 1.0,
    val popupActionBar: Boolean = false,
    val popupFullWidth: Boolean = false,
    val popupSwipeToDismiss: Boolean = true,
    val popupSwipeThreshold: Int = 30,
    val popupReducedMotionScrolling: Boolean = false,
    val popupReducedMotionScrollPercent: Int = 100,
    val popupReducedMotionSwipeThreshold: Int = 40,
    val volumeKeysTurnPages: Boolean = false,
    val volumeKeysSeekSasayaki: Boolean = false,
    val reverseVolumeKeyDirection: Boolean = false,
    val keepScreenOnWhileReading: Boolean = false,
    val lockCurrentOrientation: Boolean = false,
    val openLastReadBookOnLaunch: Boolean = false,
) {
    val continuousMode: Boolean
        get() = viewMode == ReaderViewMode.Continuous

    val bottomOverlapPx: Int
        get() = if (verticalWriting) fontSize else 0

    val writingModeCss: String
        get() = if (verticalWriting) "vertical-rl" else "horizontal-tb"

    val imageWidthViewportRatio: Double
        get() = (100 - horizontalPadding).coerceAtLeast(1) / 100.0

    val imageHeightViewportRatio: Double
        get() = (100 - verticalPadding).coerceAtLeast(1) / 100.0

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

    val verticalPaddingBlockCss: String
        get() = "var(--hoshi-vertical-padding-block, ${(verticalPadding / 2.0).cssNumber()}vh)"

    val pagePaddingCss: String
        get() = "$verticalPaddingBlockCss ${(horizontalPadding / 2.0).cssNumber()}vw"

    val bottomPaddingCss: String
        get() = if (verticalWriting && bottomOverlapPx > 0) {
            "calc($verticalPaddingBlockCss + ${bottomOverlapPx}px)"
        } else {
            verticalPaddingBlockCss
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
            ReaderTheme.System -> if (systemDark) 0xFF000000 else if (systemLightSepia) 0xFFF2E2C9 else 0xFFFFFFFF
            ReaderTheme.Dark -> 0xFF000000
            ReaderTheme.Sepia -> if (sepiaInvertInDark && systemDark) 0xFF17150F else 0xFFF2E2C9
            ReaderTheme.Light -> 0xFFFFFFFF
            ReaderTheme.Custom -> customBackgroundColor
        }
    }

    fun backgroundColorCss(systemDark: Boolean): String =
        backgroundColor(systemDark).toReaderCssColor(includeAlpha = !eInkMode && theme == ReaderTheme.Custom)

    fun textColorCss(systemDark: Boolean): String {
        if (eInkMode) {
            return if (usesDarkInterface(systemDark)) "#fff" else "#000"
        }
        return when (theme) {
            ReaderTheme.System -> if (systemDark) "#fff" else if (systemLightSepia) "#332A1B" else "#000"
            ReaderTheme.Light -> "#000"
            ReaderTheme.Dark -> "#fff"
            ReaderTheme.Sepia -> if (sepiaInvertInDark && systemDark) "#F2E2C9" else "#332A1B"
            ReaderTheme.Custom -> customTextColor.toReaderCssColor(includeAlpha = true)
        }
    }

    fun withStatisticsEnabled(enabled: Boolean): ReaderSettings {
        if (enabled && !enableStatistics) {
            return copy(
                enableStatistics = true,
                showStatisticsToggle = true,
                showReadingSpeed = true,
                showReadingTime = true,
            )
        }
        return copy(enableStatistics = enabled)
    }
}

enum class ReaderTheme(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
    Sepia("Sepia"),
    Custom("Custom"),
}

enum class ReaderInterfaceTheme(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark");

    fun usesDarkInterface(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }

    companion object {
        fun fromStorage(value: String?): ReaderInterfaceTheme =
            entries.firstOrNull { it.label == value || it.name == value } ?: System
    }
}

enum class ReaderViewMode(val rawValue: String) {
    Paginated("paginated"),
    Continuous("continuous"),
    VisualNovel("visualNovel");

    companion object {
        fun fromStorage(value: String?, legacyContinuousMode: Boolean = false): ReaderViewMode =
            entries.firstOrNull { it.rawValue == value || it.name == value }
                ?: if (legacyContinuousMode) Continuous else Paginated
    }
}

enum class VisualNovelScreenMode(val rawValue: String, @get:StringRes val labelRes: Int) {
    Block("block", R.string.reader_visual_novel_screen_mode_block),
    Sentences("sentences", R.string.reader_visual_novel_screen_mode_sentences);

    companion object {
        fun fromStorage(value: String?): VisualNovelScreenMode =
            entries.firstOrNull { it.rawValue == value || it.name == value } ?: Block
    }
}

enum class StatisticsAutostartMode(val rawValue: String, @get:StringRes val labelRes: Int) {
    Off("Off", R.string.reader_statistics_autostart_off),
    PageTurn("Page Turn", R.string.reader_statistics_autostart_page_turn),
    On("On", R.string.reader_statistics_autostart_on);

    companion object {
        fun fromRawValue(rawValue: String?): StatisticsAutostartMode =
            entries.firstOrNull { it.rawValue == rawValue } ?: Off
    }
}

fun ReaderSettings.usesDarkInterface(systemDark: Boolean): Boolean = when (theme) {
    ReaderTheme.System -> systemDark
    ReaderTheme.Light -> false
    ReaderTheme.Dark -> true
    ReaderTheme.Sepia -> sepiaInvertInDark && systemDark
    ReaderTheme.Custom -> uiTheme.usesDarkInterface(systemDark)
}

fun ReaderSettings.usesDarkSystemBarIcons(systemDark: Boolean): Boolean =
    !usesDarkInterface(systemDark)

fun ReaderSettings.usesSepiaLightContent(systemDark: Boolean): Boolean =
    !eInkMode && (
        theme == ReaderTheme.Sepia && !(sepiaInvertInDark && systemDark) ||
            theme == ReaderTheme.System && systemLightSepia && !systemDark
        )

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
        uiTheme = ReaderInterfaceTheme.fromStorage(preferences.getString("uiTheme", null)),
        systemLightSepia = preferences.getBoolean("systemLightSepia", false),
        sepiaInvertInDark = preferences.getBoolean("sepiaInvertInDark", false),
        customBackgroundColor = preferences.getLong("customBackgroundColor", 0xFFFFFFFF),
        customTextColor = preferences.getLong("customTextColor", 0xFF000000),
        customInfoColor = preferences.getLong("customInfoColor", 0xFF999999),
        verticalWriting = preferences.getBoolean("verticalWriting", true),
        selectedFont = preferences.getString("selectedFont", null) ?: ReaderFontManager.defaultMinchoFont,
        fontSize = preferences.getInt("fontSize", 22),
        hideFurigana = preferences.getBoolean("readerHideFurigana", false),
        viewMode = ReaderViewMode.fromStorage(
            preferences.getString("readerViewMode", null),
            legacyContinuousMode = preferences.getBoolean("continuousMode", false),
        ),
        visualNovelRevealSpeed = preferences.getInt("visualNovelRevealSpeed", 45).coerceVisualNovelRevealSpeed(),
        visualNovelScreenMode = VisualNovelScreenMode.fromStorage(preferences.getString("visualNovelScreenMode", null)),
        visualNovelSentencesPerScreen = preferences.getInt("visualNovelSentencesPerScreen", 1).coerceIn(1, 12),
        visualNovelPreserveDialogueBubbles = preferences.getBoolean("visualNovelPreserveDialogueBubbles", false),
        visualNovelClickAdvance = preferences.getBoolean("visualNovelClickAdvance", false),
        visualNovelMergeCrossScreenSasayakiCues = preferences.getBoolean(
            "visualNovelMergeCrossScreenSasayakiCues",
            false,
        ),
        enableStatistics = preferences.getBoolean("enableStatistics", false),
        showStatisticsTab = preferences.getBoolean("showStatisticsTab", true),
        statisticsAutostartMode = StatisticsAutostartMode.fromRawValue(
            preferences.getString("statisticsAutostartMode", null),
        ),
        statisticsSyncEnabled = preferences.getBoolean("statisticsEnableSync", false),
        statisticsSyncMode = StatisticsSyncMode.fromRawValue(preferences.getString("statisticsSyncMode", null)),
        showStatisticsToggle = preferences.getBoolean("readerShowStatisticsToggle", false),
        showReadingSpeed = preferences.getBoolean("readerShowReadingSpeed", false),
        showReadingTime = preferences.getBoolean("readerShowReadingTime", false),
        chapterSwipeDistance = preferences.getInt("chapterSwipeDistance", 20).coerceIn(10, 60),
        horizontalPadding = preferences.getInt("layoutHorizontalPadding", 5),
        verticalPadding = preferences.getInt("layoutVerticalPadding", 0),
        bottomSafeAreaDp = preferences.getInt("readerBottomSafeAreaDp", ReaderBottomSafeAreaDefaultDp)
            .coerceReaderBottomSafeAreaDp(),
        avoidPageBreak = preferences.getBoolean("avoidPageBreak", false),
        justifyText = preferences.getBoolean("justifyText", false),
        blurImages = preferences.getBoolean("blurImages", false),
        layoutAdvanced = preferences.getBoolean("layoutAdvanced", false),
        lineHeight = preferences.getFloat("lineHeight", 1.65f).toDouble(),
        characterSpacing = preferences.getFloat("characterSpacing", 0f).toDouble(),
        paragraphSpacing = preferences.getFloat("paragraphSpacing", 0f).toDouble(),
        showTitle = preferences.getBoolean("readerShowTitle", true),
        showCharacters = preferences.getBoolean("readerShowCharacters", true),
        showPercentage = preferences.getBoolean("readerShowPercentage", true),
        alwaysShowProgress = preferences.getBoolean("readerAlwaysShowProgress", true),
        showProgressTop = preferences.getBoolean("readerShowProgressTop", true),
        showReaderBackButton = preferences.getBoolean("readerShowBackButton", true),
        popupWidth = preferences.getInt("popupWidth", 320),
        popupHeight = preferences.getInt("popupHeight", 250),
        popupScale = preferences.getFloat("popupScale", 1.0f).toDouble().coerceReaderPopupScale(),
        popupActionBar = preferences.getBoolean("popupActionBar", false),
        popupFullWidth = preferences.getBoolean("popupFullWidth", false),
        popupSwipeToDismiss = preferences.getBoolean("popupSwipeToDismiss", true),
        popupSwipeThreshold = preferences.getInt("popupSwipeThreshold", 30).coerceIn(20, 60),
        popupReducedMotionScrolling = preferences.getBoolean("popupReducedMotionScrolling", false),
        popupReducedMotionScrollPercent = preferences.getInt("popupReducedMotionScrollPercent", 100).coerceIn(40, 100),
        popupReducedMotionSwipeThreshold = preferences.getInt("popupReducedMotionSwipeThreshold", 40).coerceIn(0, 100),
        volumeKeysTurnPages = preferences.getBoolean("volumeKeysTurnPages", false),
        volumeKeysSeekSasayaki = preferences.getBoolean("volumeKeysSeekSasayaki", false),
        reverseVolumeKeyDirection = preferences.getBoolean("reverseVolumeKeyDirection", false),
        keepScreenOnWhileReading = preferences.getBoolean("keepScreenOnWhileReading", false),
        lockCurrentOrientation = preferences.getBoolean("lockCurrentOrientation", false),
        openLastReadBookOnLaunch = preferences.getBoolean("openLastReadBookOnLaunch", false),
    )

    fun save(settings: ReaderSettings) {
        preferences.edit()
            .putString("theme", settings.theme.label)
            .putBoolean("eInkMode", settings.eInkMode)
            .putString("uiTheme", settings.uiTheme.label)
            .putBoolean("systemLightSepia", settings.systemLightSepia)
            .putBoolean("sepiaInvertInDark", settings.sepiaInvertInDark)
            .putLong("customBackgroundColor", settings.customBackgroundColor)
            .putLong("customTextColor", settings.customTextColor)
            .putLong("customInfoColor", settings.customInfoColor)
            .putBoolean("verticalWriting", settings.verticalWriting)
            .putString("selectedFont", settings.selectedFont)
            .putInt("fontSize", settings.fontSize)
            .putBoolean("readerHideFurigana", settings.hideFurigana)
            .putString("readerViewMode", settings.viewMode.rawValue)
            .putBoolean("continuousMode", settings.continuousMode)
            .putInt("visualNovelRevealSpeed", settings.visualNovelRevealSpeed.coerceVisualNovelRevealSpeed())
            .putString("visualNovelScreenMode", settings.visualNovelScreenMode.rawValue)
            .putInt("visualNovelSentencesPerScreen", settings.visualNovelSentencesPerScreen.coerceIn(1, 12))
            .putBoolean("visualNovelPreserveDialogueBubbles", settings.visualNovelPreserveDialogueBubbles)
            .putBoolean("visualNovelClickAdvance", settings.visualNovelClickAdvance)
            .putBoolean("visualNovelMergeCrossScreenSasayakiCues", settings.visualNovelMergeCrossScreenSasayakiCues)
            .putBoolean("enableStatistics", settings.enableStatistics)
            .putBoolean("showStatisticsTab", settings.showStatisticsTab)
            .putString("statisticsAutostartMode", settings.statisticsAutostartMode.rawValue)
            .putBoolean("statisticsEnableSync", settings.statisticsSyncEnabled)
            .putString("statisticsSyncMode", settings.statisticsSyncMode.rawValue)
            .putBoolean("readerShowStatisticsToggle", settings.showStatisticsToggle)
            .putBoolean("readerShowReadingSpeed", settings.showReadingSpeed)
            .putBoolean("readerShowReadingTime", settings.showReadingTime)
            .putInt("chapterSwipeDistance", settings.chapterSwipeDistance)
            .putInt("layoutHorizontalPadding", settings.horizontalPadding)
            .putInt("layoutVerticalPadding", settings.verticalPadding)
            .putInt("readerBottomSafeAreaDp", settings.bottomSafeAreaDp.coerceReaderBottomSafeAreaDp())
            .putBoolean("avoidPageBreak", settings.avoidPageBreak)
            .putBoolean("justifyText", settings.justifyText)
            .putBoolean("blurImages", settings.blurImages)
            .putBoolean("layoutAdvanced", settings.layoutAdvanced)
            .putFloat("lineHeight", settings.lineHeight.toFloat())
            .putFloat("characterSpacing", settings.characterSpacing.toFloat())
            .putFloat("paragraphSpacing", settings.paragraphSpacing.toFloat())
            .putBoolean("readerShowTitle", settings.showTitle)
            .putBoolean("readerShowCharacters", settings.showCharacters)
            .putBoolean("readerShowPercentage", settings.showPercentage)
            .putBoolean("readerAlwaysShowProgress", settings.alwaysShowProgress)
            .putBoolean("readerShowProgressTop", settings.showProgressTop)
            .putBoolean("readerShowBackButton", settings.showReaderBackButton)
            .putInt("popupWidth", settings.popupWidth)
            .putInt("popupHeight", settings.popupHeight)
            .putFloat("popupScale", settings.popupScale.coerceReaderPopupScale().toFloat())
            .putBoolean("popupActionBar", settings.popupActionBar)
            .putBoolean("popupFullWidth", settings.popupFullWidth)
            .putBoolean("popupSwipeToDismiss", settings.popupSwipeToDismiss)
            .putInt("popupSwipeThreshold", settings.popupSwipeThreshold)
            .putBoolean("popupReducedMotionScrolling", settings.popupReducedMotionScrolling)
            .putInt("popupReducedMotionScrollPercent", settings.popupReducedMotionScrollPercent)
            .putInt("popupReducedMotionSwipeThreshold", settings.popupReducedMotionSwipeThreshold)
            .putBoolean("volumeKeysTurnPages", settings.volumeKeysTurnPages)
            .putBoolean("volumeKeysSeekSasayaki", settings.volumeKeysSeekSasayaki)
            .putBoolean("reverseVolumeKeyDirection", settings.reverseVolumeKeyDirection)
            .putBoolean("keepScreenOnWhileReading", settings.keepScreenOnWhileReading)
            .putBoolean("lockCurrentOrientation", settings.lockCurrentOrientation)
            .putBoolean("openLastReadBookOnLaunch", settings.openLastReadBookOnLaunch)
            .apply()
    }
}

private val Context.readerSettingsDataStore by preferencesDataStore(name = ReaderSettingsRepository.DataStoreName)

fun Context.readerSettingsRepository(
    profileRepository: ProfileRepository? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): ReaderSettingsRepository =
    ReaderSettingsRepository(
        dataStore = readerSettingsDataStore,
        legacySource = ReaderSettingsStore(this),
        profileRepository = profileRepository,
        ioDispatcher = ioDispatcher,
    )

class ReaderSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val legacySource: ReaderSettingsLegacySource? = null,
    private val profileRepository: ProfileRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val profileSettingsVersion = MutableStateFlow(0)
    private val profileSettingsLock = Mutex()

    val settings: Flow<ReaderSettings> =
        if (profileRepository == null) {
            dataStore.data
                .onStart { migrateLegacySettingsIfNeeded() }
                .map { preferences -> preferences.toReaderSettings() }
        } else {
            combine(
                dataStore.data.onStart { migrateLegacySettingsIfNeeded() },
                profileRepository.state,
                profileSettingsVersion,
            ) { preferences, _, _ ->
                val globalSettings = preferences.toReaderSettings()
                globalSettings.withProfileAppearance(profileAppearanceSettingsOrMigrate(globalSettings))
            }
        }

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        migrateLegacySettingsIfNeeded()
        if (profileRepository != null) {
            val globalCurrent = dataStore.data.first().toReaderSettings()
            val updated = profileSettingsLock.withLock {
                val current = globalCurrent.withProfileAppearance(
                    readProfileAppearanceSettingsOrMigrate(globalCurrent),
                )
                transform(current).withStatisticsTransitionFrom(current).also { settings ->
                    saveProfileAppearanceSettings(settings.toProfileAppearanceSettings())
                }
            }
            dataStore.edit { preferences ->
                preferences.writeGlobalReaderSettings(updated)
                preferences[KEY_MIGRATED_FROM_SHARED_PREFERENCES] = true
            }
            profileSettingsVersion.value += 1
            return
        }
        dataStore.edit { preferences ->
            val current = preferences.toReaderSettings()
            preferences.writeReaderSettings(transform(current).withStatisticsTransitionFrom(current))
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
            uiTheme = ReaderInterfaceTheme.fromStorage(this[KEY_UI_THEME]),
            systemLightSepia = this[KEY_SYSTEM_LIGHT_SEPIA] ?: false,
            sepiaInvertInDark = this[KEY_SEPIA_INVERT_IN_DARK] ?: false,
            customBackgroundColor = this[KEY_CUSTOM_BACKGROUND_COLOR] ?: 0xFFFFFFFF,
            customTextColor = this[KEY_CUSTOM_TEXT_COLOR] ?: 0xFF000000,
            customInfoColor = this[KEY_CUSTOM_INFO_COLOR] ?: 0xFF999999,
            verticalWriting = this[KEY_VERTICAL_WRITING] ?: true,
            selectedFont = this[KEY_SELECTED_FONT] ?: ReaderFontManager.defaultMinchoFont,
            fontSize = this[KEY_FONT_SIZE] ?: 22,
            hideFurigana = this[KEY_HIDE_FURIGANA] ?: false,
            viewMode = ReaderViewMode.fromStorage(
                this[KEY_READER_VIEW_MODE],
                legacyContinuousMode = this[KEY_CONTINUOUS_MODE] ?: false,
            ),
            visualNovelRevealSpeed = (this[KEY_VISUAL_NOVEL_REVEAL_SPEED] ?: 45).coerceVisualNovelRevealSpeed(),
            visualNovelScreenMode = VisualNovelScreenMode.fromStorage(this[KEY_VISUAL_NOVEL_SCREEN_MODE]),
            visualNovelSentencesPerScreen = (this[KEY_VISUAL_NOVEL_SENTENCES_PER_SCREEN] ?: 1).coerceIn(1, 12),
            visualNovelPreserveDialogueBubbles = this[KEY_VISUAL_NOVEL_PRESERVE_DIALOGUE_BUBBLES] ?: false,
            visualNovelClickAdvance = this[KEY_VISUAL_NOVEL_CLICK_ADVANCE] ?: false,
            visualNovelMergeCrossScreenSasayakiCues = this[KEY_VISUAL_NOVEL_MERGE_CROSS_SCREEN_SASAYAKI_CUES] ?: false,
            enableStatistics = this[KEY_ENABLE_STATISTICS] ?: false,
            showStatisticsTab = this[KEY_SHOW_STATISTICS_TAB] ?: true,
            statisticsAutostartMode = StatisticsAutostartMode.fromRawValue(this[KEY_STATISTICS_AUTOSTART_MODE]),
            statisticsSyncEnabled = this[KEY_STATISTICS_SYNC_ENABLED] ?: false,
            statisticsSyncMode = StatisticsSyncMode.fromRawValue(this[KEY_STATISTICS_SYNC_MODE]),
            showStatisticsToggle = this[KEY_SHOW_STATISTICS_TOGGLE] ?: false,
            showReadingSpeed = this[KEY_SHOW_READING_SPEED] ?: false,
            showReadingTime = this[KEY_SHOW_READING_TIME] ?: false,
            chapterSwipeDistance = (this[KEY_CHAPTER_SWIPE_DISTANCE] ?: 20).coerceIn(10, 60),
            horizontalPadding = this[KEY_HORIZONTAL_PADDING] ?: 5,
            verticalPadding = this[KEY_VERTICAL_PADDING] ?: 0,
            bottomSafeAreaDp = (this[KEY_BOTTOM_SAFE_AREA_DP] ?: ReaderBottomSafeAreaDefaultDp)
                .coerceReaderBottomSafeAreaDp(),
            avoidPageBreak = this[KEY_AVOID_PAGE_BREAK] ?: false,
            justifyText = this[KEY_JUSTIFY_TEXT] ?: false,
            blurImages = this[KEY_BLUR_IMAGES] ?: false,
            layoutAdvanced = this[KEY_LAYOUT_ADVANCED] ?: false,
            lineHeight = (this[KEY_LINE_HEIGHT] ?: 1.65f).toDouble(),
            characterSpacing = (this[KEY_CHARACTER_SPACING] ?: 0f).toDouble(),
            paragraphSpacing = (this[KEY_PARAGRAPH_SPACING] ?: 0f).toDouble(),
            showTitle = this[KEY_SHOW_TITLE] ?: true,
            showCharacters = this[KEY_SHOW_CHARACTERS] ?: true,
            showPercentage = this[KEY_SHOW_PERCENTAGE] ?: true,
            alwaysShowProgress = this[KEY_ALWAYS_SHOW_PROGRESS] ?: true,
            showProgressTop = this[KEY_SHOW_PROGRESS_TOP] ?: true,
            showReaderBackButton = this[KEY_SHOW_READER_BACK_BUTTON] ?: true,
            popupWidth = this[KEY_POPUP_WIDTH] ?: 320,
            popupHeight = this[KEY_POPUP_HEIGHT] ?: 250,
            popupScale = (this[KEY_POPUP_SCALE] ?: 1.0f).toDouble().coerceReaderPopupScale(),
            popupActionBar = this[KEY_POPUP_ACTION_BAR] ?: false,
            popupFullWidth = this[KEY_POPUP_FULL_WIDTH] ?: false,
            popupSwipeToDismiss = this[KEY_POPUP_SWIPE_TO_DISMISS] ?: true,
            popupSwipeThreshold = (this[KEY_POPUP_SWIPE_THRESHOLD] ?: 30).coerceIn(20, 60),
            popupReducedMotionScrolling = this[KEY_POPUP_REDUCED_MOTION_SCROLLING] ?: false,
            popupReducedMotionScrollPercent = (this[KEY_POPUP_REDUCED_MOTION_SCROLL_PERCENT] ?: 100).coerceIn(40, 100),
            popupReducedMotionSwipeThreshold = (this[KEY_POPUP_REDUCED_MOTION_SWIPE_THRESHOLD] ?: 40).coerceIn(0, 100),
            volumeKeysTurnPages = this[KEY_VOLUME_KEYS_TURN_PAGES] ?: false,
            volumeKeysSeekSasayaki = this[KEY_VOLUME_KEYS_SEEK_SASAYAKI] ?: false,
            reverseVolumeKeyDirection = this[KEY_REVERSE_VOLUME_KEY_DIRECTION] ?: false,
            keepScreenOnWhileReading = this[KEY_KEEP_SCREEN_ON_WHILE_READING] ?: false,
            lockCurrentOrientation = this[KEY_LOCK_CURRENT_ORIENTATION] ?: false,
            openLastReadBookOnLaunch = this[KEY_OPEN_LAST_READ_BOOK_ON_LAUNCH] ?: false,
        )

    private fun MutablePreferences.writeReaderSettings(settings: ReaderSettings) {
        this[KEY_THEME] = settings.theme.label
        this[KEY_E_INK_MODE] = settings.eInkMode
        this[KEY_UI_THEME] = settings.uiTheme.label
        this[KEY_SYSTEM_LIGHT_SEPIA] = settings.systemLightSepia
        this[KEY_SEPIA_INVERT_IN_DARK] = settings.sepiaInvertInDark
        this[KEY_CUSTOM_BACKGROUND_COLOR] = settings.customBackgroundColor
        this[KEY_CUSTOM_TEXT_COLOR] = settings.customTextColor
        this[KEY_CUSTOM_INFO_COLOR] = settings.customInfoColor
        this[KEY_VERTICAL_WRITING] = settings.verticalWriting
        this[KEY_SELECTED_FONT] = settings.selectedFont
        this[KEY_FONT_SIZE] = settings.fontSize
        this[KEY_HIDE_FURIGANA] = settings.hideFurigana
        this[KEY_READER_VIEW_MODE] = settings.viewMode.rawValue
        this[KEY_CONTINUOUS_MODE] = settings.continuousMode
        this[KEY_VISUAL_NOVEL_REVEAL_SPEED] = settings.visualNovelRevealSpeed.coerceVisualNovelRevealSpeed()
        this[KEY_VISUAL_NOVEL_SCREEN_MODE] = settings.visualNovelScreenMode.rawValue
        this[KEY_VISUAL_NOVEL_SENTENCES_PER_SCREEN] = settings.visualNovelSentencesPerScreen.coerceIn(1, 12)
        this[KEY_VISUAL_NOVEL_PRESERVE_DIALOGUE_BUBBLES] = settings.visualNovelPreserveDialogueBubbles
        this[KEY_VISUAL_NOVEL_CLICK_ADVANCE] = settings.visualNovelClickAdvance
        this[KEY_VISUAL_NOVEL_MERGE_CROSS_SCREEN_SASAYAKI_CUES] = settings.visualNovelMergeCrossScreenSasayakiCues
        this[KEY_ENABLE_STATISTICS] = settings.enableStatistics
        this[KEY_SHOW_STATISTICS_TAB] = settings.showStatisticsTab
        this[KEY_STATISTICS_AUTOSTART_MODE] = settings.statisticsAutostartMode.rawValue
        this[KEY_STATISTICS_SYNC_ENABLED] = settings.statisticsSyncEnabled
        this[KEY_STATISTICS_SYNC_MODE] = settings.statisticsSyncMode.rawValue
        this[KEY_SHOW_STATISTICS_TOGGLE] = settings.showStatisticsToggle
        this[KEY_SHOW_READING_SPEED] = settings.showReadingSpeed
        this[KEY_SHOW_READING_TIME] = settings.showReadingTime
        this[KEY_CHAPTER_SWIPE_DISTANCE] = settings.chapterSwipeDistance
        this[KEY_HORIZONTAL_PADDING] = settings.horizontalPadding
        this[KEY_VERTICAL_PADDING] = settings.verticalPadding
        this[KEY_BOTTOM_SAFE_AREA_DP] = settings.bottomSafeAreaDp.coerceReaderBottomSafeAreaDp()
        this[KEY_AVOID_PAGE_BREAK] = settings.avoidPageBreak
        this[KEY_JUSTIFY_TEXT] = settings.justifyText
        this[KEY_BLUR_IMAGES] = settings.blurImages
        this[KEY_LAYOUT_ADVANCED] = settings.layoutAdvanced
        this[KEY_LINE_HEIGHT] = settings.lineHeight.toFloat()
        this[KEY_CHARACTER_SPACING] = settings.characterSpacing.toFloat()
        this[KEY_PARAGRAPH_SPACING] = settings.paragraphSpacing.toFloat()
        this[KEY_SHOW_TITLE] = settings.showTitle
        this[KEY_SHOW_CHARACTERS] = settings.showCharacters
        this[KEY_SHOW_PERCENTAGE] = settings.showPercentage
        this[KEY_ALWAYS_SHOW_PROGRESS] = settings.alwaysShowProgress
        this[KEY_SHOW_PROGRESS_TOP] = settings.showProgressTop
        this[KEY_SHOW_READER_BACK_BUTTON] = settings.showReaderBackButton
        this[KEY_POPUP_WIDTH] = settings.popupWidth
        this[KEY_POPUP_HEIGHT] = settings.popupHeight
        this[KEY_POPUP_SCALE] = settings.popupScale.coerceReaderPopupScale().toFloat()
        this[KEY_POPUP_ACTION_BAR] = settings.popupActionBar
        this[KEY_POPUP_FULL_WIDTH] = settings.popupFullWidth
        this[KEY_POPUP_SWIPE_TO_DISMISS] = settings.popupSwipeToDismiss
        this[KEY_POPUP_SWIPE_THRESHOLD] = settings.popupSwipeThreshold
        this[KEY_POPUP_REDUCED_MOTION_SCROLLING] = settings.popupReducedMotionScrolling
        this[KEY_POPUP_REDUCED_MOTION_SCROLL_PERCENT] = settings.popupReducedMotionScrollPercent
        this[KEY_POPUP_REDUCED_MOTION_SWIPE_THRESHOLD] = settings.popupReducedMotionSwipeThreshold
        this[KEY_VOLUME_KEYS_TURN_PAGES] = settings.volumeKeysTurnPages
        this[KEY_VOLUME_KEYS_SEEK_SASAYAKI] = settings.volumeKeysSeekSasayaki
        this[KEY_REVERSE_VOLUME_KEY_DIRECTION] = settings.reverseVolumeKeyDirection
        this[KEY_KEEP_SCREEN_ON_WHILE_READING] = settings.keepScreenOnWhileReading
        this[KEY_LOCK_CURRENT_ORIENTATION] = settings.lockCurrentOrientation
        this[KEY_OPEN_LAST_READ_BOOK_ON_LAUNCH] = settings.openLastReadBookOnLaunch
    }

    private fun MutablePreferences.writeGlobalReaderSettings(settings: ReaderSettings) {
        this[KEY_ENABLE_STATISTICS] = settings.enableStatistics
        this[KEY_SHOW_STATISTICS_TAB] = settings.showStatisticsTab
        this[KEY_STATISTICS_AUTOSTART_MODE] = settings.statisticsAutostartMode.rawValue
        this[KEY_STATISTICS_SYNC_ENABLED] = settings.statisticsSyncEnabled
        this[KEY_STATISTICS_SYNC_MODE] = settings.statisticsSyncMode.rawValue
        this[KEY_VOLUME_KEYS_TURN_PAGES] = settings.volumeKeysTurnPages
        this[KEY_VOLUME_KEYS_SEEK_SASAYAKI] = settings.volumeKeysSeekSasayaki
        this[KEY_REVERSE_VOLUME_KEY_DIRECTION] = settings.reverseVolumeKeyDirection
        this[KEY_KEEP_SCREEN_ON_WHILE_READING] = settings.keepScreenOnWhileReading
        this[KEY_LOCK_CURRENT_ORIENTATION] = settings.lockCurrentOrientation
        this[KEY_OPEN_LAST_READ_BOOK_ON_LAUNCH] = settings.openLastReadBookOnLaunch
    }

    private suspend fun profileAppearanceSettingsOrMigrate(globalSettings: ReaderSettings): ProfileReaderAppearanceSettings =
        profileSettingsLock.withLock {
            readProfileAppearanceSettingsOrMigrate(globalSettings)
        }

    private suspend fun readProfileAppearanceSettingsOrMigrate(globalSettings: ReaderSettings): ProfileReaderAppearanceSettings = withContext(ioDispatcher) {
        val repository = profileRepository
        if (repository == null) {
            globalSettings.toProfileAppearanceSettings()
        } else {
            val file = repository.readerSettingsFile()
            if (file.isFile) {
                runCatching {
                    json.decodeFromString<ProfileReaderAppearanceSettings>(file.readText())
                }.getOrDefault(globalSettings.toProfileAppearanceSettings())
            } else {
                val migrated = globalSettings.toProfileAppearanceSettings()
                saveProfileAppearanceSettings(migrated)
                migrated
            }
        }
    }

    private suspend fun saveProfileAppearanceSettings(settings: ProfileReaderAppearanceSettings) = withContext(ioDispatcher) {
        val file = profileRepository?.readerSettingsFile() ?: return@withContext
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(ProfileReaderAppearanceSettings.serializer(), settings))
    }

    companion object {
        const val DataStoreName = "reader-settings"

        private val KEY_MIGRATED_FROM_SHARED_PREFERENCES =
            booleanPreferencesKey("readerSettingsMigratedFromSharedPreferences")
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_E_INK_MODE = booleanPreferencesKey("eInkMode")
        private val KEY_UI_THEME = stringPreferencesKey("uiTheme")
        private val KEY_SYSTEM_LIGHT_SEPIA = booleanPreferencesKey("systemLightSepia")
        private val KEY_SEPIA_INVERT_IN_DARK = booleanPreferencesKey("sepiaInvertInDark")
        private val KEY_CUSTOM_BACKGROUND_COLOR = longPreferencesKey("customBackgroundColor")
        private val KEY_CUSTOM_TEXT_COLOR = longPreferencesKey("customTextColor")
        private val KEY_CUSTOM_INFO_COLOR = longPreferencesKey("customInfoColor")
        private val KEY_VERTICAL_WRITING = booleanPreferencesKey("verticalWriting")
        private val KEY_SELECTED_FONT = stringPreferencesKey("selectedFont")
        private val KEY_FONT_SIZE = intPreferencesKey("fontSize")
        private val KEY_HIDE_FURIGANA = booleanPreferencesKey("readerHideFurigana")
        private val KEY_READER_VIEW_MODE = stringPreferencesKey("readerViewMode")
        private val KEY_CONTINUOUS_MODE = booleanPreferencesKey("continuousMode")
        private val KEY_VISUAL_NOVEL_REVEAL_SPEED = intPreferencesKey("visualNovelRevealSpeed")
        private val KEY_VISUAL_NOVEL_SCREEN_MODE = stringPreferencesKey("visualNovelScreenMode")
        private val KEY_VISUAL_NOVEL_SENTENCES_PER_SCREEN = intPreferencesKey("visualNovelSentencesPerScreen")
        private val KEY_VISUAL_NOVEL_PRESERVE_DIALOGUE_BUBBLES =
            booleanPreferencesKey("visualNovelPreserveDialogueBubbles")
        private val KEY_VISUAL_NOVEL_CLICK_ADVANCE = booleanPreferencesKey("visualNovelClickAdvance")
        private val KEY_VISUAL_NOVEL_MERGE_CROSS_SCREEN_SASAYAKI_CUES =
            booleanPreferencesKey("visualNovelMergeCrossScreenSasayakiCues")
        private val KEY_ENABLE_STATISTICS = booleanPreferencesKey("enableStatistics")
        private val KEY_SHOW_STATISTICS_TAB = booleanPreferencesKey("showStatisticsTab")
        private val KEY_STATISTICS_AUTOSTART_MODE = stringPreferencesKey("statisticsAutostartMode")
        private val KEY_STATISTICS_SYNC_ENABLED = booleanPreferencesKey("statisticsEnableSync")
        private val KEY_STATISTICS_SYNC_MODE = stringPreferencesKey("statisticsSyncMode")
        private val KEY_SHOW_STATISTICS_TOGGLE = booleanPreferencesKey("readerShowStatisticsToggle")
        private val KEY_SHOW_READING_SPEED = booleanPreferencesKey("readerShowReadingSpeed")
        private val KEY_SHOW_READING_TIME = booleanPreferencesKey("readerShowReadingTime")
        private val KEY_CHAPTER_SWIPE_DISTANCE = intPreferencesKey("chapterSwipeDistance")
        private val KEY_HORIZONTAL_PADDING = intPreferencesKey("layoutHorizontalPadding")
        private val KEY_VERTICAL_PADDING = intPreferencesKey("layoutVerticalPadding")
        private val KEY_BOTTOM_SAFE_AREA_DP = intPreferencesKey("readerBottomSafeAreaDp")
        private val KEY_AVOID_PAGE_BREAK = booleanPreferencesKey("avoidPageBreak")
        private val KEY_JUSTIFY_TEXT = booleanPreferencesKey("justifyText")
        private val KEY_BLUR_IMAGES = booleanPreferencesKey("blurImages")
        private val KEY_LAYOUT_ADVANCED = booleanPreferencesKey("layoutAdvanced")
        private val KEY_LINE_HEIGHT = floatPreferencesKey("lineHeight")
        private val KEY_CHARACTER_SPACING = floatPreferencesKey("characterSpacing")
        private val KEY_PARAGRAPH_SPACING = floatPreferencesKey("paragraphSpacing")
        private val KEY_SHOW_TITLE = booleanPreferencesKey("readerShowTitle")
        private val KEY_SHOW_CHARACTERS = booleanPreferencesKey("readerShowCharacters")
        private val KEY_SHOW_PERCENTAGE = booleanPreferencesKey("readerShowPercentage")
        private val KEY_ALWAYS_SHOW_PROGRESS = booleanPreferencesKey("readerAlwaysShowProgress")
        private val KEY_SHOW_PROGRESS_TOP = booleanPreferencesKey("readerShowProgressTop")
        private val KEY_SHOW_READER_BACK_BUTTON = booleanPreferencesKey("readerShowBackButton")
        private val KEY_POPUP_WIDTH = intPreferencesKey("popupWidth")
        private val KEY_POPUP_HEIGHT = intPreferencesKey("popupHeight")
        private val KEY_POPUP_SCALE = floatPreferencesKey("popupScale")
        private val KEY_POPUP_ACTION_BAR = booleanPreferencesKey("popupActionBar")
        private val KEY_POPUP_FULL_WIDTH = booleanPreferencesKey("popupFullWidth")
        private val KEY_POPUP_SWIPE_TO_DISMISS = booleanPreferencesKey("popupSwipeToDismiss")
        private val KEY_POPUP_SWIPE_THRESHOLD = intPreferencesKey("popupSwipeThreshold")
        private val KEY_POPUP_REDUCED_MOTION_SCROLLING = booleanPreferencesKey("popupReducedMotionScrolling")
        private val KEY_POPUP_REDUCED_MOTION_SCROLL_PERCENT = intPreferencesKey("popupReducedMotionScrollPercent")
        private val KEY_POPUP_REDUCED_MOTION_SWIPE_THRESHOLD = intPreferencesKey("popupReducedMotionSwipeThreshold")
        private val KEY_VOLUME_KEYS_TURN_PAGES = booleanPreferencesKey("volumeKeysTurnPages")
        private val KEY_VOLUME_KEYS_SEEK_SASAYAKI = booleanPreferencesKey("volumeKeysSeekSasayaki")
        private val KEY_REVERSE_VOLUME_KEY_DIRECTION = booleanPreferencesKey("reverseVolumeKeyDirection")
        private val KEY_KEEP_SCREEN_ON_WHILE_READING = booleanPreferencesKey("keepScreenOnWhileReading")
        private val KEY_LOCK_CURRENT_ORIENTATION = booleanPreferencesKey("lockCurrentOrientation")
        private val KEY_OPEN_LAST_READ_BOOK_ON_LAUNCH = booleanPreferencesKey("openLastReadBookOnLaunch")

        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

@Serializable
private data class ProfileReaderAppearanceSettings(
    val theme: ReaderTheme = ReaderTheme.System,
    val eInkMode: Boolean = false,
    val uiTheme: ReaderInterfaceTheme = ReaderInterfaceTheme.System,
    val systemLightSepia: Boolean = false,
    val sepiaInvertInDark: Boolean = false,
    val customBackgroundColor: Long = 0xFFFFFFFF,
    val customTextColor: Long = 0xFF000000,
    val customInfoColor: Long = 0xFF999999,
    val verticalWriting: Boolean = true,
    val selectedFont: String = ReaderFontManager.defaultMinchoFont,
    val fontSize: Int = 22,
    val hideFurigana: Boolean = false,
    val viewMode: ReaderViewMode? = null,
    val continuousMode: Boolean = false,
    val visualNovelRevealSpeed: Int = 45,
    val visualNovelScreenMode: VisualNovelScreenMode = VisualNovelScreenMode.Block,
    val visualNovelSentencesPerScreen: Int = 1,
    val visualNovelPreserveDialogueBubbles: Boolean = false,
    val visualNovelClickAdvance: Boolean = false,
    val visualNovelMergeCrossScreenSasayakiCues: Boolean = false,
    val showStatisticsToggle: Boolean = false,
    val showReadingSpeed: Boolean = false,
    val showReadingTime: Boolean = false,
    val chapterSwipeDistance: Int = 20,
    val horizontalPadding: Int = 5,
    val verticalPadding: Int = 0,
    val bottomSafeAreaDp: Int = ReaderBottomSafeAreaDefaultDp,
    val avoidPageBreak: Boolean = false,
    val justifyText: Boolean = false,
    val blurImages: Boolean = false,
    val layoutAdvanced: Boolean = false,
    val lineHeight: Double = 1.65,
    val characterSpacing: Double = 0.0,
    val paragraphSpacing: Double = 0.0,
    val showTitle: Boolean = true,
    val showCharacters: Boolean = true,
    val showPercentage: Boolean = true,
    val alwaysShowProgress: Boolean = true,
    val showProgressTop: Boolean = true,
    val showReaderBackButton: Boolean = true,
    val popupWidth: Int = 320,
    val popupHeight: Int = 250,
    val popupScale: Double = 1.0,
    val popupActionBar: Boolean = false,
    val popupFullWidth: Boolean = false,
    val popupSwipeToDismiss: Boolean = true,
    val popupSwipeThreshold: Int = 30,
    val popupReducedMotionScrolling: Boolean = false,
    val popupReducedMotionScrollPercent: Int = 100,
    val popupReducedMotionSwipeThreshold: Int = 40,
)

private fun ReaderSettings.toProfileAppearanceSettings(): ProfileReaderAppearanceSettings =
    ProfileReaderAppearanceSettings(
        theme = theme,
        eInkMode = eInkMode,
        uiTheme = uiTheme,
        systemLightSepia = systemLightSepia,
        sepiaInvertInDark = sepiaInvertInDark,
        customBackgroundColor = customBackgroundColor,
        customTextColor = customTextColor,
        customInfoColor = customInfoColor,
        verticalWriting = verticalWriting,
        selectedFont = selectedFont,
        fontSize = fontSize,
        hideFurigana = hideFurigana,
        viewMode = viewMode,
        continuousMode = continuousMode,
        visualNovelRevealSpeed = visualNovelRevealSpeed.coerceVisualNovelRevealSpeed(),
        visualNovelScreenMode = visualNovelScreenMode,
        visualNovelSentencesPerScreen = visualNovelSentencesPerScreen.coerceIn(1, 12),
        visualNovelPreserveDialogueBubbles = visualNovelPreserveDialogueBubbles,
        visualNovelClickAdvance = visualNovelClickAdvance,
        visualNovelMergeCrossScreenSasayakiCues = visualNovelMergeCrossScreenSasayakiCues,
        showStatisticsToggle = showStatisticsToggle,
        showReadingSpeed = showReadingSpeed,
        showReadingTime = showReadingTime,
        chapterSwipeDistance = chapterSwipeDistance,
        horizontalPadding = horizontalPadding,
        verticalPadding = verticalPadding,
        bottomSafeAreaDp = bottomSafeAreaDp.coerceReaderBottomSafeAreaDp(),
        avoidPageBreak = avoidPageBreak,
        justifyText = justifyText,
        blurImages = blurImages,
        layoutAdvanced = layoutAdvanced,
        lineHeight = lineHeight,
        characterSpacing = characterSpacing,
        paragraphSpacing = paragraphSpacing,
        showTitle = showTitle,
        showCharacters = showCharacters,
        showPercentage = showPercentage,
        alwaysShowProgress = alwaysShowProgress,
        showProgressTop = showProgressTop,
        showReaderBackButton = showReaderBackButton,
        popupWidth = popupWidth,
        popupHeight = popupHeight,
        popupScale = popupScale,
        popupActionBar = popupActionBar,
        popupFullWidth = popupFullWidth,
        popupSwipeToDismiss = popupSwipeToDismiss,
        popupSwipeThreshold = popupSwipeThreshold,
        popupReducedMotionScrolling = popupReducedMotionScrolling,
        popupReducedMotionScrollPercent = popupReducedMotionScrollPercent,
        popupReducedMotionSwipeThreshold = popupReducedMotionSwipeThreshold,
    )

private fun ReaderSettings.withProfileAppearance(appearance: ProfileReaderAppearanceSettings): ReaderSettings =
    copy(
        theme = appearance.theme,
        eInkMode = appearance.eInkMode,
        uiTheme = appearance.uiTheme,
        systemLightSepia = appearance.systemLightSepia,
        sepiaInvertInDark = appearance.sepiaInvertInDark,
        customBackgroundColor = appearance.customBackgroundColor,
        customTextColor = appearance.customTextColor,
        customInfoColor = appearance.customInfoColor,
        verticalWriting = appearance.verticalWriting,
        selectedFont = appearance.selectedFont,
        fontSize = appearance.fontSize,
        hideFurigana = appearance.hideFurigana,
        viewMode = appearance.viewMode ?: if (appearance.continuousMode) {
            ReaderViewMode.Continuous
        } else {
            ReaderViewMode.Paginated
        },
        visualNovelRevealSpeed = appearance.visualNovelRevealSpeed.coerceVisualNovelRevealSpeed(),
        visualNovelScreenMode = appearance.visualNovelScreenMode,
        visualNovelSentencesPerScreen = appearance.visualNovelSentencesPerScreen.coerceIn(1, 12),
        visualNovelPreserveDialogueBubbles = appearance.visualNovelPreserveDialogueBubbles,
        visualNovelClickAdvance = appearance.visualNovelClickAdvance,
        visualNovelMergeCrossScreenSasayakiCues = appearance.visualNovelMergeCrossScreenSasayakiCues,
        showStatisticsToggle = appearance.showStatisticsToggle,
        showReadingSpeed = appearance.showReadingSpeed,
        showReadingTime = appearance.showReadingTime,
        chapterSwipeDistance = appearance.chapterSwipeDistance.coerceIn(10, 60),
        horizontalPadding = appearance.horizontalPadding,
        verticalPadding = appearance.verticalPadding,
        bottomSafeAreaDp = appearance.bottomSafeAreaDp.coerceReaderBottomSafeAreaDp(),
        avoidPageBreak = appearance.avoidPageBreak,
        justifyText = appearance.justifyText,
        blurImages = appearance.blurImages,
        layoutAdvanced = appearance.layoutAdvanced,
        lineHeight = appearance.lineHeight,
        characterSpacing = appearance.characterSpacing,
        paragraphSpacing = appearance.paragraphSpacing,
        showTitle = appearance.showTitle,
        showCharacters = appearance.showCharacters,
        showPercentage = appearance.showPercentage,
        alwaysShowProgress = appearance.alwaysShowProgress,
        showProgressTop = appearance.showProgressTop,
        showReaderBackButton = appearance.showReaderBackButton,
        popupWidth = appearance.popupWidth,
        popupHeight = appearance.popupHeight,
        popupScale = appearance.popupScale.coerceReaderPopupScale(),
        popupActionBar = appearance.popupActionBar,
        popupFullWidth = appearance.popupFullWidth,
        popupSwipeToDismiss = appearance.popupSwipeToDismiss,
        popupSwipeThreshold = appearance.popupSwipeThreshold.coerceIn(20, 60),
        popupReducedMotionScrolling = appearance.popupReducedMotionScrolling,
        popupReducedMotionScrollPercent = appearance.popupReducedMotionScrollPercent.coerceIn(40, 100),
        popupReducedMotionSwipeThreshold = appearance.popupReducedMotionSwipeThreshold.coerceIn(0, 100),
    )

private fun ReaderSettings.withStatisticsTransitionFrom(previous: ReaderSettings): ReaderSettings =
    if (enableStatistics && !previous.enableStatistics) {
        copy(
            showStatisticsToggle = true,
            showReadingSpeed = true,
            showReadingTime = true,
        )
    } else {
        this
    }

internal fun Double.cssNumber(): String =
    String.format(Locale.US, "%.1f", this)

private fun Int.coerceVisualNovelRevealSpeed(): Int =
    (roundToNearestStep(step = 5)).coerceIn(0, 120)

private fun Int.roundToNearestStep(step: Int): Int =
    ((this + step / 2) / step) * step
