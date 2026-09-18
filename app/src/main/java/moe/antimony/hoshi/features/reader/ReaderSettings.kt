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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.display.AppDisplayMigrationPayload
import moe.antimony.hoshi.features.display.AppDisplaySettings
import moe.antimony.hoshi.features.display.AppDisplaySettingsMigrationSource
import moe.antimony.hoshi.features.display.DisplayPalettePreset
import moe.antimony.hoshi.features.display.LegacyDisplaySettingsSnapshot
import moe.antimony.hoshi.features.display.LegacyDisplayTheme
import moe.antimony.hoshi.features.display.resolveDisplaySettings
import moe.antimony.hoshi.features.sync.StatisticsSyncMode
import moe.antimony.hoshi.profiles.ProfileRepository
import java.util.Locale

internal const val ReaderPopupScaleMin = 0.8
internal const val ReaderPopupScaleMax = 2.0
internal const val ReaderPopupScaleStep = 0.05
internal const val ReaderPopupScaleSliderSteps = 23
internal const val ReaderTopSafeAreaDefaultDp = 30
internal const val ReaderTopSafeAreaMinDp = ReaderTopSafeAreaDefaultDp
internal const val ReaderTopSafeAreaMaxDp = 72
internal const val ReaderTopSafeAreaStepDp = 2
internal const val ReaderBottomSafeAreaDefaultDp = 18
internal const val ReaderBottomSafeAreaMinDp = ReaderBottomSafeAreaDefaultDp
internal const val ReaderBottomSafeAreaMaxDp = 72
internal const val ReaderBottomSafeAreaStepDp = 2
internal const val ReaderPageSwipeThresholdDefaultPx = 72
internal const val ReaderPageSwipeThresholdMinPx = 0
internal const val ReaderPageSwipeThresholdMaxPx = 360
internal const val ReaderPageSwipeThresholdStepPx = 18

internal fun Double.coerceReaderPopupScale(): Double =
    coerceIn(ReaderPopupScaleMin, ReaderPopupScaleMax)

internal fun Int.coerceReaderPageSwipeThresholdPx(): Int =
    coerceIn(ReaderPageSwipeThresholdMinPx, ReaderPageSwipeThresholdMaxPx)

internal fun Int.coerceReaderTopSafeAreaDp(): Int {
    val clamped = coerceIn(ReaderTopSafeAreaMinDp, ReaderTopSafeAreaMaxDp)
    val offset = clamped - ReaderTopSafeAreaMinDp
    val lower = ReaderTopSafeAreaMinDp + (offset / ReaderTopSafeAreaStepDp) * ReaderTopSafeAreaStepDp
    val upper = (lower + ReaderTopSafeAreaStepDp).coerceAtMost(ReaderTopSafeAreaMaxDp)
    return if (clamped - lower < upper - clamped) lower else upper
}

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
    val selectedFontFamilyId: String? = null,
    val selectedFontVariantId: String? = null,
    val fontVariantSelections: Map<String, String> = emptyMap(),
    val fontSize: Int = 22,
    val furiganaMode: FuriganaMode = FuriganaMode.Off,
    val viewMode: ReaderViewMode = ReaderViewMode.Paginated,
    val visualNovelRevealSpeed: Int = 45,
    val visualNovelScreenMode: VisualNovelScreenMode = VisualNovelScreenMode.Block,
    val visualNovelSentencesPerScreen: Int = 1,
    val visualNovelPreserveDialogueBubbles: Boolean = false,
    val visualNovelClickAdvance: Boolean = false,
    val visualNovelMergeCrossScreenSasayakiCues: Boolean = false,
    val statisticsAutostartOnBookOpen: Boolean = false,
    val statisticsAutostartOnPageTurn: Boolean = false,
    val statisticsResetMinutes: Int = 0,
    val statisticsSyncEnabled: Boolean = true,
    val statisticsSyncMode: StatisticsSyncMode = StatisticsSyncMode.Merge,
    val showStatisticsToggle: Boolean = false,
    val showReadingSpeed: Boolean = false,
    val showReadingTime: Boolean = false,
    val chapterSwipeDistance: Int = 20,
    val pageSwipeThresholdPx: Int = ReaderPageSwipeThresholdDefaultPx,
    val horizontalPadding: Int = 5,
    val verticalPadding: Int = 0,
    val topSafeAreaDp: Int = ReaderTopSafeAreaDefaultDp,
    val bottomSafeAreaDp: Int = ReaderBottomSafeAreaDefaultDp,
    val avoidPageBreak: Boolean = false,
    val justifyText: Boolean = false,
    val blurImages: Boolean = false,
    val layoutAdvanced: Boolean = false,
    val lineHeight: Double = 1.65,
    val characterSpacing: Double = 0.0,
    val paragraphSpacing: Double = 0.0,
    val showTitle: Boolean = true,
    val showProgress: Boolean = true,
    val showChapterProgress: Boolean = false,
    val showCharacters: Boolean = true,
    val showPercentage: Boolean = true,
    val alwaysShowProgress: Boolean = true,
    val showProgressTop: Boolean = true,
    val showReaderBackButton: Boolean = true,
    val popupWidth: Int = 500,
    val popupHeight: Int = 500,
    val popupScale: Double = 1.0,
    val popupActionBar: Boolean = false,
    val popupFullWidth: Boolean = false,
    val popupSwipeToDismiss: Boolean = true,
    val popupSwipeThreshold: Int = 30,
    val popupReducedMotionScrolling: Boolean = false,
    val popupReducedMotionScrollPercent: Int = 100,
    val popupReducedMotionSwipeThreshold: Int = 40,
    val volumeKeysTurnPages: Boolean = false,
    val volumeKeysNavigatePopupTerms: Boolean = false,
    val volumeKeysSeekSasayaki: Boolean = false,
    val reverseVolumeKeyDirection: Boolean = false,
    val keepScreenOnWhileReading: Boolean = false,
    val lockCurrentOrientation: Boolean = false,
    val openLastReadBookOnLaunch: Boolean = false,
    val displaySettings: AppDisplaySettings? = null,
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
        displaySettings?.let { return resolveDisplaySettings(it, systemDark).backgroundColor }
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
        backgroundColor(systemDark).toReaderCssColor(
            includeAlpha = displaySettings?.let {
                val resolved = resolveDisplaySettings(it, systemDark)
                !resolved.eInkMode && resolved.palette == DisplayPalettePreset.Custom
            } ?: (!eInkMode && theme == ReaderTheme.Custom),
        )

    fun textColorCss(systemDark: Boolean): String {
        displaySettings?.let { global ->
            val resolved = resolveDisplaySettings(global, systemDark)
            return resolved.textColor.toDisplayCssColor(
                includeAlpha = !resolved.eInkMode && resolved.palette == DisplayPalettePreset.Custom,
            )
        }
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
}

private fun Long.toDisplayCssColor(includeAlpha: Boolean): String = when (this) {
    0xFF332A1BL -> "#332A1B"
    0xFFF2E2C9L -> "#F2E2C9"
    else -> toReaderCssColor(includeAlpha)
}

internal fun ReaderSettings.withFontSelection(
    family: ReaderFontFamily,
    variant: ReaderFontVariant,
): ReaderSettings {
    val legacyName = if (family.source == ReaderFontSource.PUBLISHER) {
        ReaderFontManager.publisherFont
    } else {
        family.displayName
    }
    return copy(
        selectedFont = legacyName,
        selectedFontFamilyId = family.id,
        selectedFontVariantId = variant.id,
        fontVariantSelections = fontVariantSelections + (family.id to variant.id),
    )
}

internal fun ReaderSettings.withDefaultFont(): ReaderSettings = copy(
    selectedFont = ReaderFontManager.defaultMinchoFont,
    selectedFontFamilyId = ReaderFontManager.systemMinchoFamilyId,
    selectedFontVariantId = "wght-400-normal",
    fontVariantSelections = fontVariantSelections +
        (ReaderFontManager.systemMinchoFamilyId to "wght-400-normal"),
)

@Serializable
enum class FuriganaMode(@param:StringRes val labelResId: Int) {
    Off(R.string.reader_furigana_off),
    Dimmed(R.string.reader_furigana_dimmed),
    Toggle(R.string.reader_furigana_toggle),
    Hidden(R.string.reader_furigana_hidden);

    companion object {
        fun fromStorage(value: String?, legacyHidden: Boolean = false): FuriganaMode =
            entries.firstOrNull { it.name == value } ?: if (legacyHidden) Hidden else Off
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

internal data class LegacyStatisticsAutostart(
    val onBookOpen: Boolean,
    val onPageTurn: Boolean,
)

internal fun migrateLegacyStatisticsAutostart(rawValue: String?): LegacyStatisticsAutostart =
    when (rawValue) {
        "On" -> LegacyStatisticsAutostart(onBookOpen = true, onPageTurn = false)
        "Page Turn" -> LegacyStatisticsAutostart(onBookOpen = false, onPageTurn = true)
        else -> LegacyStatisticsAutostart(onBookOpen = false, onPageTurn = false)
    }

fun ReaderSettings.usesDarkInterface(systemDark: Boolean): Boolean =
    displaySettings?.let { resolveDisplaySettings(it, systemDark).isDark } ?: when (theme) {
        ReaderTheme.System -> systemDark
        ReaderTheme.Light -> false
        ReaderTheme.Dark -> true
        ReaderTheme.Sepia -> sepiaInvertInDark && systemDark
        ReaderTheme.Custom -> uiTheme.usesDarkInterface(systemDark)
    }

fun ReaderSettings.usesDarkSystemBarIcons(systemDark: Boolean): Boolean =
    !usesDarkInterface(systemDark)

fun ReaderSettings.usesSepiaLightContent(systemDark: Boolean): Boolean =
    displaySettings?.let { global ->
        val resolved = resolveDisplaySettings(global, systemDark)
        !resolved.eInkMode && resolved.palette == DisplayPalettePreset.Sepia
    } ?: (!eInkMode && (
        theme == ReaderTheme.Sepia && !(sepiaInvertInDark && systemDark) ||
            theme == ReaderTheme.System && systemLightSepia && !systemDark
        ))

fun ReaderSettings.resolvedForDisplay(systemDark: Boolean): ReaderSettings {
    val global = displaySettings ?: return this
    val resolved = resolveDisplaySettings(global, systemDark)
    val baseColors = resolveDisplaySettings(global.copy(eInkMode = false), systemDark)
    val selection = resolved.selection
    val projectedTheme = if (resolved.eInkMode) {
        if (resolved.isDark) ReaderTheme.Dark else ReaderTheme.Light
    } else when (selection.preset) {
        DisplayPalettePreset.Light -> ReaderTheme.Light
        DisplayPalettePreset.Sepia -> ReaderTheme.Sepia
        DisplayPalettePreset.Dark -> ReaderTheme.Dark
        DisplayPalettePreset.DarkSepia,
        DisplayPalettePreset.Custom,
        -> ReaderTheme.Custom
    }
    return copy(
        theme = projectedTheme,
        eInkMode = global.eInkMode,
        uiTheme = if (resolved.isDark) ReaderInterfaceTheme.Dark else ReaderInterfaceTheme.Light,
        systemLightSepia = false,
        sepiaInvertInDark = false,
        customBackgroundColor = baseColors.backgroundColor,
        customTextColor = baseColors.textColor,
        customInfoColor = baseColors.infoColor,
        displaySettings = null,
    )
}

interface ReaderSettingsLegacySource {
    fun load(): ReaderSettings
}

class ReaderSettingsStore(context: Context) : ReaderSettingsLegacySource {
    private val preferences = context.getSharedPreferences("reader-settings", Context.MODE_PRIVATE)

    override fun load(): ReaderSettings {
        val legacyStatisticsAutostart = migrateLegacyStatisticsAutostart(
            preferences.getString("statisticsAutostartMode", null),
        )
        return ReaderSettings(
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
        selectedFontFamilyId = preferences.getString("selectedFontFamilyId", null),
        selectedFontVariantId = preferences.getString("selectedFontVariantId", null),
        fontVariantSelections = preferences.getString("fontVariantSelections", null)
            ?.let { runCatching { Json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            .orEmpty(),
        fontSize = preferences.getInt("fontSize", 22),
        furiganaMode = FuriganaMode.fromStorage(
            preferences.getString("furiganaMode", null),
            preferences.getBoolean("readerHideFurigana", false),
        ),
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
        statisticsAutostartOnBookOpen = if (preferences.contains("statisticsAutostartOnBookOpen")) {
            preferences.getBoolean("statisticsAutostartOnBookOpen", false)
        } else {
            legacyStatisticsAutostart.onBookOpen
        },
        statisticsAutostartOnPageTurn = if (preferences.contains("statisticsAutostartOnPageTurn")) {
            preferences.getBoolean("statisticsAutostartOnPageTurn", false)
        } else {
            legacyStatisticsAutostart.onPageTurn
        },
        statisticsSyncEnabled = preferences.getBoolean("statisticsEnableSync", true),
        statisticsSyncMode = StatisticsSyncMode.fromRawValue(preferences.getString("statisticsSyncMode", null)),
        showStatisticsToggle = preferences.getBoolean("readerShowStatisticsToggle", false),
        showReadingSpeed = preferences.getBoolean("readerShowReadingSpeed", false),
        showReadingTime = preferences.getBoolean("readerShowReadingTime", false),
        chapterSwipeDistance = preferences.getInt("chapterSwipeDistance", 20).coerceIn(10, 60),
        pageSwipeThresholdPx = preferences.getInt(
            "pageSwipeThresholdPx",
            ReaderPageSwipeThresholdDefaultPx,
        ).coerceReaderPageSwipeThresholdPx(),
        horizontalPadding = preferences.getInt("layoutHorizontalPadding", 5),
        verticalPadding = preferences.getInt("layoutVerticalPadding", 0),
        topSafeAreaDp = preferences.getInt("readerTopSafeAreaDp", ReaderTopSafeAreaDefaultDp)
            .coerceReaderTopSafeAreaDp(),
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
        showProgress = preferences.getBoolean("readerShowProgress", true),
        showChapterProgress = preferences.getBoolean("readerShowChapterProgress", false),
        showCharacters = preferences.getBoolean("readerShowCharacters", true),
        showPercentage = preferences.getBoolean("readerShowPercentage", true),
        alwaysShowProgress = preferences.getBoolean("readerAlwaysShowProgress", true),
        showProgressTop = preferences.getBoolean("readerShowProgressTop", true),
        showReaderBackButton = preferences.getBoolean("readerShowBackButton", true),
        popupWidth = preferences.getInt("popupWidth", 500),
        popupHeight = preferences.getInt("popupHeight", 500),
        popupScale = preferences.getFloat("popupScale", 1.0f).toDouble().coerceReaderPopupScale(),
        popupActionBar = preferences.getBoolean("popupActionBar", false),
        popupFullWidth = preferences.getBoolean("popupFullWidth", false),
        popupSwipeToDismiss = preferences.getBoolean("popupSwipeToDismiss", true),
        popupSwipeThreshold = preferences.getInt("popupSwipeThreshold", 30).coerceIn(20, 60),
        popupReducedMotionScrolling = preferences.getBoolean("popupReducedMotionScrolling", false),
        popupReducedMotionScrollPercent = preferences.getInt("popupReducedMotionScrollPercent", 100).coerceIn(40, 100),
        popupReducedMotionSwipeThreshold = preferences.getInt("popupReducedMotionSwipeThreshold", 40).coerceIn(0, 100),
        volumeKeysTurnPages = preferences.getBoolean("volumeKeysTurnPages", false),
        volumeKeysNavigatePopupTerms = preferences.getBoolean("volumeKeysNavigatePopupTerms", false),
        volumeKeysSeekSasayaki = preferences.getBoolean("volumeKeysSeekSasayaki", false),
        reverseVolumeKeyDirection = preferences.getBoolean("reverseVolumeKeyDirection", false),
        keepScreenOnWhileReading = preferences.getBoolean("keepScreenOnWhileReading", false),
        lockCurrentOrientation = preferences.getBoolean("lockCurrentOrientation", false),
        openLastReadBookOnLaunch = preferences.getBoolean("openLastReadBookOnLaunch", false),
        )
    }

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
            .putString("selectedFontFamilyId", settings.selectedFontFamilyId)
            .putString("selectedFontVariantId", settings.selectedFontVariantId)
            .putString("fontVariantSelections", Json.encodeToString(settings.fontVariantSelections))
            .putInt("fontSize", settings.fontSize)
            .putString("furiganaMode", settings.furiganaMode.name)
            .putBoolean("readerHideFurigana", settings.furiganaMode == FuriganaMode.Hidden)
            .putString("readerViewMode", settings.viewMode.rawValue)
            .putBoolean("continuousMode", settings.continuousMode)
            .putInt("visualNovelRevealSpeed", settings.visualNovelRevealSpeed.coerceVisualNovelRevealSpeed())
            .putString("visualNovelScreenMode", settings.visualNovelScreenMode.rawValue)
            .putInt("visualNovelSentencesPerScreen", settings.visualNovelSentencesPerScreen.coerceIn(1, 12))
            .putBoolean("visualNovelPreserveDialogueBubbles", settings.visualNovelPreserveDialogueBubbles)
            .putBoolean("visualNovelClickAdvance", settings.visualNovelClickAdvance)
            .putBoolean("visualNovelMergeCrossScreenSasayakiCues", settings.visualNovelMergeCrossScreenSasayakiCues)
            .putBoolean("statisticsAutostartOnBookOpen", settings.statisticsAutostartOnBookOpen)
            .putBoolean("statisticsAutostartOnPageTurn", settings.statisticsAutostartOnPageTurn)
            .remove("statisticsAutostartMode")
            .putBoolean("statisticsEnableSync", settings.statisticsSyncEnabled)
            .putString("statisticsSyncMode", settings.statisticsSyncMode.rawValue)
            .putBoolean("readerShowStatisticsToggle", settings.showStatisticsToggle)
            .putBoolean("readerShowReadingSpeed", settings.showReadingSpeed)
            .putBoolean("readerShowReadingTime", settings.showReadingTime)
            .putInt("chapterSwipeDistance", settings.chapterSwipeDistance)
            .putInt("pageSwipeThresholdPx", settings.pageSwipeThresholdPx.coerceReaderPageSwipeThresholdPx())
            .putInt("layoutHorizontalPadding", settings.horizontalPadding)
            .putInt("layoutVerticalPadding", settings.verticalPadding)
            .putInt("readerTopSafeAreaDp", settings.topSafeAreaDp.coerceReaderTopSafeAreaDp())
            .putInt("readerBottomSafeAreaDp", settings.bottomSafeAreaDp.coerceReaderBottomSafeAreaDp())
            .putBoolean("avoidPageBreak", settings.avoidPageBreak)
            .putBoolean("justifyText", settings.justifyText)
            .putBoolean("blurImages", settings.blurImages)
            .putBoolean("layoutAdvanced", settings.layoutAdvanced)
            .putFloat("lineHeight", settings.lineHeight.toFloat())
            .putFloat("characterSpacing", settings.characterSpacing.toFloat())
            .putFloat("paragraphSpacing", settings.paragraphSpacing.toFloat())
            .putBoolean("readerShowTitle", settings.showTitle)
            .putBoolean("readerShowProgress", settings.showProgress)
            .putBoolean("readerShowChapterProgress", settings.showChapterProgress)
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
            .putBoolean("volumeKeysNavigatePopupTerms", settings.volumeKeysNavigatePopupTerms)
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
    displaySettings: Flow<AppDisplaySettings>? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): ReaderSettingsRepository =
    ReaderSettingsRepository(
        dataStore = readerSettingsDataStore,
        legacySource = ReaderSettingsStore(this),
        profileRepository = profileRepository,
        displaySettings = displaySettings,
        ioDispatcher = ioDispatcher,
    )

class ReaderSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val legacySource: ReaderSettingsLegacySource? = null,
    private val profileRepository: ProfileRepository? = null,
    private val displaySettings: Flow<AppDisplaySettings>? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val profileSettingsVersion = MutableStateFlow(0)
    private val profileSettingsLock = Mutex()

    private val profileSettings: Flow<ReaderSettings> =
        if (profileRepository == null) {
            dataStore.data
                .onStart { migrateSettingsIfNeeded() }
                .map { preferences -> preferences.toReaderSettings() }
        } else {
            combine(
                dataStore.data.onStart { migrateSettingsIfNeeded() },
                profileRepository.state,
                profileSettingsVersion,
            ) { preferences, _, _ ->
                val globalSettings = preferences.toReaderSettings()
                globalSettings.withProfileAppearance(profileAppearanceSettingsOrMigrate(globalSettings))
            }
        }

    val settings: Flow<ReaderSettings> = displaySettings?.let { globalDisplay ->
        combine(profileSettings, globalDisplay) { reader, display ->
            reader.withDisplaySettingsProjection(display)
        }.onStart {
            // Snapshot legacy colors before profile initialization can create its JSON file.
            globalDisplay.first()
        }
    } ?: profileSettings

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        migrateSettingsIfNeeded()
        if (profileRepository != null) {
            val globalCurrent = dataStore.data.first().toReaderSettings()
            val updated = profileSettingsLock.withLock {
                val appearance = readProfileAppearanceSettingsOrMigrate(globalCurrent)
                val current = globalCurrent.withProfileAppearance(appearance).let { reader ->
                    displaySettings?.first()?.let(reader::withDisplaySettingsProjection) ?: reader
                }
                transform(current).also { settings ->
                    val appearanceToSave = settings.toProfileAppearanceSettings().let { updatedAppearance ->
                        if (displaySettings == null) updatedAppearance else updatedAppearance.withLegacyDisplayFrom(appearance)
                    }
                    saveProfileAppearanceSettings(
                        appearanceToSave,
                    )
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
            preferences.writeReaderSettings(transform(current))
            preferences[KEY_MIGRATED_FROM_SHARED_PREFERENCES] = true
        }
    }

    private suspend fun migrateSettingsIfNeeded() {
        dataStore.edit { preferences ->
            if (preferences[KEY_MIGRATED_FROM_SHARED_PREFERENCES] != true) {
                preferences.writeReaderSettings(legacySource?.load() ?: ReaderSettings())
                preferences[KEY_MIGRATED_FROM_SHARED_PREFERENCES] = true
            }
            if (preferences[KEY_FURIGANA_MODE] == null) {
                preferences[KEY_FURIGANA_MODE] = FuriganaMode.fromStorage(
                    null,
                    preferences[KEY_HIDE_FURIGANA] ?: false,
                ).name
            }
            preferences.migrateStatisticsAutostartIfNeeded()
        }
    }

    private fun MutablePreferences.migrateStatisticsAutostartIfNeeded() {
        val legacy = migrateLegacyStatisticsAutostart(this[KEY_STATISTICS_AUTOSTART_MODE])
        if (this[KEY_STATISTICS_AUTOSTART_ON_BOOK_OPEN] == null) {
            this[KEY_STATISTICS_AUTOSTART_ON_BOOK_OPEN] = legacy.onBookOpen
        }
        if (this[KEY_STATISTICS_AUTOSTART_ON_PAGE_TURN] == null) {
            this[KEY_STATISTICS_AUTOSTART_ON_PAGE_TURN] = legacy.onPageTurn
        }
        remove(KEY_STATISTICS_AUTOSTART_MODE)
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
            selectedFontFamilyId = this[KEY_SELECTED_FONT_FAMILY_ID],
            selectedFontVariantId = this[KEY_SELECTED_FONT_VARIANT_ID],
            fontVariantSelections = this[KEY_FONT_VARIANT_SELECTIONS]
                ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
                .orEmpty(),
            fontSize = this[KEY_FONT_SIZE] ?: 22,
            furiganaMode = FuriganaMode.fromStorage(this[KEY_FURIGANA_MODE], this[KEY_HIDE_FURIGANA] ?: false),
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
            statisticsAutostartOnBookOpen = this[KEY_STATISTICS_AUTOSTART_ON_BOOK_OPEN] ?: false,
            statisticsAutostartOnPageTurn = this[KEY_STATISTICS_AUTOSTART_ON_PAGE_TURN] ?: false,
            statisticsResetMinutes = this[KEY_STATISTICS_RESET_MINUTES] ?: 0,
            statisticsSyncEnabled = this[KEY_STATISTICS_SYNC_ENABLED] ?: true,
            statisticsSyncMode = StatisticsSyncMode.fromRawValue(this[KEY_STATISTICS_SYNC_MODE]),
            showStatisticsToggle = this[KEY_SHOW_STATISTICS_TOGGLE] ?: false,
            showReadingSpeed = this[KEY_SHOW_READING_SPEED] ?: false,
            showReadingTime = this[KEY_SHOW_READING_TIME] ?: false,
            chapterSwipeDistance = (this[KEY_CHAPTER_SWIPE_DISTANCE] ?: 20).coerceIn(10, 60),
            pageSwipeThresholdPx = (
                this[KEY_PAGE_SWIPE_THRESHOLD_PX] ?: ReaderPageSwipeThresholdDefaultPx
            ).coerceReaderPageSwipeThresholdPx(),
            horizontalPadding = this[KEY_HORIZONTAL_PADDING] ?: 5,
            verticalPadding = this[KEY_VERTICAL_PADDING] ?: 0,
            topSafeAreaDp = (this[KEY_TOP_SAFE_AREA_DP] ?: ReaderTopSafeAreaDefaultDp)
                .coerceReaderTopSafeAreaDp(),
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
            showProgress = this[KEY_SHOW_PROGRESS] ?: true,
            showChapterProgress = this[KEY_SHOW_CHAPTER_PROGRESS] ?: false,
            showCharacters = this[KEY_SHOW_CHARACTERS] ?: true,
            showPercentage = this[KEY_SHOW_PERCENTAGE] ?: true,
            alwaysShowProgress = this[KEY_ALWAYS_SHOW_PROGRESS] ?: true,
            showProgressTop = this[KEY_SHOW_PROGRESS_TOP] ?: true,
            showReaderBackButton = this[KEY_SHOW_READER_BACK_BUTTON] ?: true,
            popupWidth = this[KEY_POPUP_WIDTH] ?: 500,
            popupHeight = this[KEY_POPUP_HEIGHT] ?: 500,
            popupScale = (this[KEY_POPUP_SCALE] ?: 1.0f).toDouble().coerceReaderPopupScale(),
            popupActionBar = this[KEY_POPUP_ACTION_BAR] ?: false,
            popupFullWidth = this[KEY_POPUP_FULL_WIDTH] ?: false,
            popupSwipeToDismiss = this[KEY_POPUP_SWIPE_TO_DISMISS] ?: true,
            popupSwipeThreshold = (this[KEY_POPUP_SWIPE_THRESHOLD] ?: 30).coerceIn(20, 60),
            popupReducedMotionScrolling = this[KEY_POPUP_REDUCED_MOTION_SCROLLING] ?: false,
            popupReducedMotionScrollPercent = (this[KEY_POPUP_REDUCED_MOTION_SCROLL_PERCENT] ?: 100).coerceIn(40, 100),
            popupReducedMotionSwipeThreshold = (this[KEY_POPUP_REDUCED_MOTION_SWIPE_THRESHOLD] ?: 40).coerceIn(0, 100),
            volumeKeysTurnPages = this[KEY_VOLUME_KEYS_TURN_PAGES] ?: false,
            volumeKeysNavigatePopupTerms = this[KEY_VOLUME_KEYS_NAVIGATE_POPUP_TERMS] ?: false,
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
        settings.selectedFontFamilyId?.let { this[KEY_SELECTED_FONT_FAMILY_ID] = it }
            ?: remove(KEY_SELECTED_FONT_FAMILY_ID)
        settings.selectedFontVariantId?.let { this[KEY_SELECTED_FONT_VARIANT_ID] = it }
            ?: remove(KEY_SELECTED_FONT_VARIANT_ID)
        this[KEY_FONT_VARIANT_SELECTIONS] = json.encodeToString(settings.fontVariantSelections)
        this[KEY_FONT_SIZE] = settings.fontSize
        this[KEY_FURIGANA_MODE] = settings.furiganaMode.name
        this[KEY_HIDE_FURIGANA] = settings.furiganaMode == FuriganaMode.Hidden
        this[KEY_READER_VIEW_MODE] = settings.viewMode.rawValue
        this[KEY_CONTINUOUS_MODE] = settings.continuousMode
        this[KEY_VISUAL_NOVEL_REVEAL_SPEED] = settings.visualNovelRevealSpeed.coerceVisualNovelRevealSpeed()
        this[KEY_VISUAL_NOVEL_SCREEN_MODE] = settings.visualNovelScreenMode.rawValue
        this[KEY_VISUAL_NOVEL_SENTENCES_PER_SCREEN] = settings.visualNovelSentencesPerScreen.coerceIn(1, 12)
        this[KEY_VISUAL_NOVEL_PRESERVE_DIALOGUE_BUBBLES] = settings.visualNovelPreserveDialogueBubbles
        this[KEY_VISUAL_NOVEL_CLICK_ADVANCE] = settings.visualNovelClickAdvance
        this[KEY_VISUAL_NOVEL_MERGE_CROSS_SCREEN_SASAYAKI_CUES] = settings.visualNovelMergeCrossScreenSasayakiCues
        this[KEY_STATISTICS_AUTOSTART_ON_BOOK_OPEN] = settings.statisticsAutostartOnBookOpen
        this[KEY_STATISTICS_AUTOSTART_ON_PAGE_TURN] = settings.statisticsAutostartOnPageTurn
        remove(KEY_STATISTICS_AUTOSTART_MODE)
        this[KEY_STATISTICS_RESET_MINUTES] = settings.statisticsResetMinutes
        this[KEY_STATISTICS_SYNC_ENABLED] = settings.statisticsSyncEnabled
        this[KEY_STATISTICS_SYNC_MODE] = settings.statisticsSyncMode.rawValue
        this[KEY_SHOW_STATISTICS_TOGGLE] = settings.showStatisticsToggle
        this[KEY_SHOW_READING_SPEED] = settings.showReadingSpeed
        this[KEY_SHOW_READING_TIME] = settings.showReadingTime
        this[KEY_CHAPTER_SWIPE_DISTANCE] = settings.chapterSwipeDistance
        this[KEY_PAGE_SWIPE_THRESHOLD_PX] = settings.pageSwipeThresholdPx.coerceReaderPageSwipeThresholdPx()
        this[KEY_HORIZONTAL_PADDING] = settings.horizontalPadding
        this[KEY_VERTICAL_PADDING] = settings.verticalPadding
        this[KEY_TOP_SAFE_AREA_DP] = settings.topSafeAreaDp.coerceReaderTopSafeAreaDp()
        this[KEY_BOTTOM_SAFE_AREA_DP] = settings.bottomSafeAreaDp.coerceReaderBottomSafeAreaDp()
        this[KEY_AVOID_PAGE_BREAK] = settings.avoidPageBreak
        this[KEY_JUSTIFY_TEXT] = settings.justifyText
        this[KEY_BLUR_IMAGES] = settings.blurImages
        this[KEY_LAYOUT_ADVANCED] = settings.layoutAdvanced
        this[KEY_LINE_HEIGHT] = settings.lineHeight.toFloat()
        this[KEY_CHARACTER_SPACING] = settings.characterSpacing.toFloat()
        this[KEY_PARAGRAPH_SPACING] = settings.paragraphSpacing.toFloat()
        this[KEY_SHOW_TITLE] = settings.showTitle
        this[KEY_SHOW_PROGRESS] = settings.showProgress
        this[KEY_SHOW_CHAPTER_PROGRESS] = settings.showChapterProgress
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
        this[KEY_VOLUME_KEYS_NAVIGATE_POPUP_TERMS] = settings.volumeKeysNavigatePopupTerms
        this[KEY_VOLUME_KEYS_SEEK_SASAYAKI] = settings.volumeKeysSeekSasayaki
        this[KEY_REVERSE_VOLUME_KEY_DIRECTION] = settings.reverseVolumeKeyDirection
        this[KEY_KEEP_SCREEN_ON_WHILE_READING] = settings.keepScreenOnWhileReading
        this[KEY_LOCK_CURRENT_ORIENTATION] = settings.lockCurrentOrientation
        this[KEY_OPEN_LAST_READ_BOOK_ON_LAUNCH] = settings.openLastReadBookOnLaunch
    }

    private fun MutablePreferences.writeGlobalReaderSettings(settings: ReaderSettings) {
        this[KEY_STATISTICS_AUTOSTART_ON_BOOK_OPEN] = settings.statisticsAutostartOnBookOpen
        this[KEY_STATISTICS_AUTOSTART_ON_PAGE_TURN] = settings.statisticsAutostartOnPageTurn
        remove(KEY_STATISTICS_AUTOSTART_MODE)
        this[KEY_STATISTICS_RESET_MINUTES] = settings.statisticsResetMinutes
        this[KEY_STATISTICS_SYNC_ENABLED] = settings.statisticsSyncEnabled
        this[KEY_STATISTICS_SYNC_MODE] = settings.statisticsSyncMode.rawValue
        this[KEY_VOLUME_KEYS_TURN_PAGES] = settings.volumeKeysTurnPages
        this[KEY_VOLUME_KEYS_NAVIGATE_POPUP_TERMS] = settings.volumeKeysNavigatePopupTerms
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
        private val KEY_SELECTED_FONT_FAMILY_ID = stringPreferencesKey("selectedFontFamilyId")
        private val KEY_SELECTED_FONT_VARIANT_ID = stringPreferencesKey("selectedFontVariantId")
        private val KEY_FONT_VARIANT_SELECTIONS = stringPreferencesKey("fontVariantSelections")
        private val KEY_FONT_SIZE = intPreferencesKey("fontSize")
        private val KEY_FURIGANA_MODE = stringPreferencesKey("furiganaMode")
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
        private val KEY_STATISTICS_AUTOSTART_MODE = stringPreferencesKey("statisticsAutostartMode")
        private val KEY_STATISTICS_AUTOSTART_ON_BOOK_OPEN =
            booleanPreferencesKey("statisticsAutostartOnBookOpen")
        private val KEY_STATISTICS_AUTOSTART_ON_PAGE_TURN =
            booleanPreferencesKey("statisticsAutostartOnPageTurn")
        private val KEY_STATISTICS_RESET_MINUTES = intPreferencesKey("statisticsResetMinutes")
        private val KEY_STATISTICS_SYNC_ENABLED = booleanPreferencesKey("statisticsEnableSync")
        private val KEY_STATISTICS_SYNC_MODE = stringPreferencesKey("statisticsSyncMode")
        private val KEY_SHOW_STATISTICS_TOGGLE = booleanPreferencesKey("readerShowStatisticsToggle")
        private val KEY_SHOW_READING_SPEED = booleanPreferencesKey("readerShowReadingSpeed")
        private val KEY_SHOW_READING_TIME = booleanPreferencesKey("readerShowReadingTime")
        private val KEY_CHAPTER_SWIPE_DISTANCE = intPreferencesKey("chapterSwipeDistance")
        private val KEY_PAGE_SWIPE_THRESHOLD_PX = intPreferencesKey("pageSwipeThresholdPx")
        private val KEY_HORIZONTAL_PADDING = intPreferencesKey("layoutHorizontalPadding")
        private val KEY_VERTICAL_PADDING = intPreferencesKey("layoutVerticalPadding")
        private val KEY_TOP_SAFE_AREA_DP = intPreferencesKey("readerTopSafeAreaDp")
        private val KEY_BOTTOM_SAFE_AREA_DP = intPreferencesKey("readerBottomSafeAreaDp")
        private val KEY_AVOID_PAGE_BREAK = booleanPreferencesKey("avoidPageBreak")
        private val KEY_JUSTIFY_TEXT = booleanPreferencesKey("justifyText")
        private val KEY_BLUR_IMAGES = booleanPreferencesKey("blurImages")
        private val KEY_LAYOUT_ADVANCED = booleanPreferencesKey("layoutAdvanced")
        private val KEY_LINE_HEIGHT = floatPreferencesKey("lineHeight")
        private val KEY_CHARACTER_SPACING = floatPreferencesKey("characterSpacing")
        private val KEY_PARAGRAPH_SPACING = floatPreferencesKey("paragraphSpacing")
        private val KEY_SHOW_TITLE = booleanPreferencesKey("readerShowTitle")
        private val KEY_SHOW_PROGRESS = booleanPreferencesKey("readerShowProgress")
        private val KEY_SHOW_CHAPTER_PROGRESS = booleanPreferencesKey("readerShowChapterProgress")
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
        private val KEY_VOLUME_KEYS_NAVIGATE_POPUP_TERMS = booleanPreferencesKey("volumeKeysNavigatePopupTerms")
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

internal class ReaderDisplaySettingsMigrationSource(
    private val dataStore: DataStore<Preferences>,
    private val legacySource: ReaderSettingsLegacySource?,
    private val profileRepository: ProfileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppDisplaySettingsMigrationSource {
    override suspend fun loadMigrationPayload(): AppDisplayMigrationPayload = withContext(ioDispatcher) {
        val state = profileRepository.state.value
        val globalProfile = state.globalActiveProfile
        val globalFile = profileRepository.readerSettingsFile(globalProfile.id)
        val profileSettings = globalFile.takeIf { it.isFile }?.let { file ->
            runCatching {
                migrationJson.decodeFromString<ProfileReaderAppearanceSettings>(file.readText())
                    .toLegacyDisplaySnapshot()
            }.getOrNull()
        }
        val active = profileSettings ?: run {
            val preferences = dataStore.data.first()
            preferences.toLegacyDisplaySnapshotOrNull()
                ?: legacySource?.load()?.toLegacyDisplaySnapshot()
        }
        AppDisplayMigrationPayload(activeSettings = active)
    }

    private fun Preferences.toLegacyDisplaySnapshotOrNull(): LegacyDisplaySettingsSnapshot? {
        val hasStoredDisplay = this[DISPLAY_MIGRATED_KEY] != null ||
            this[DISPLAY_THEME_KEY] != null ||
            this[DISPLAY_CUSTOM_BACKGROUND_KEY] != null ||
            this[DISPLAY_CUSTOM_TEXT_KEY] != null ||
            this[DISPLAY_CUSTOM_INFO_KEY] != null
        if (!hasStoredDisplay) return null
        return LegacyDisplaySettingsSnapshot(
            theme = this[DISPLAY_THEME_KEY].toLegacyDisplayTheme(),
            eInkMode = this[DISPLAY_E_INK_KEY] ?: false,
            systemLightSepia = this[DISPLAY_SYSTEM_LIGHT_SEPIA_KEY] ?: false,
            sepiaInvertInDark = this[DISPLAY_SEPIA_INVERT_KEY] ?: false,
            customBackgroundColor = this[DISPLAY_CUSTOM_BACKGROUND_KEY] ?: 0xFFFFFFFFL,
            customTextColor = this[DISPLAY_CUSTOM_TEXT_KEY] ?: 0xFF000000L,
            customInfoColor = this[DISPLAY_CUSTOM_INFO_KEY] ?: 0xFF999999L,
        )
    }

    companion object {
        private val DISPLAY_MIGRATED_KEY = booleanPreferencesKey("readerSettingsMigratedFromSharedPreferences")
        private val DISPLAY_THEME_KEY = stringPreferencesKey("theme")
        private val DISPLAY_E_INK_KEY = booleanPreferencesKey("eInkMode")
        private val DISPLAY_SYSTEM_LIGHT_SEPIA_KEY = booleanPreferencesKey("systemLightSepia")
        private val DISPLAY_SEPIA_INVERT_KEY = booleanPreferencesKey("sepiaInvertInDark")
        private val DISPLAY_CUSTOM_BACKGROUND_KEY = longPreferencesKey("customBackgroundColor")
        private val DISPLAY_CUSTOM_TEXT_KEY = longPreferencesKey("customTextColor")
        private val DISPLAY_CUSTOM_INFO_KEY = longPreferencesKey("customInfoColor")
        private val migrationJson = Json { ignoreUnknownKeys = true }
    }
}

internal fun Context.readerDisplaySettingsMigrationSource(
    profileRepository: ProfileRepository,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): AppDisplaySettingsMigrationSource = ReaderDisplaySettingsMigrationSource(
    dataStore = readerSettingsDataStore,
    legacySource = ReaderSettingsStore(this),
    profileRepository = profileRepository,
    ioDispatcher = ioDispatcher,
)

private fun ProfileReaderAppearanceSettings.toLegacyDisplaySnapshot(): LegacyDisplaySettingsSnapshot =
    LegacyDisplaySettingsSnapshot(
        theme = theme.toLegacyDisplayTheme(),
        eInkMode = eInkMode,
        systemLightSepia = systemLightSepia,
        sepiaInvertInDark = sepiaInvertInDark,
        customBackgroundColor = customBackgroundColor,
        customTextColor = customTextColor,
        customInfoColor = customInfoColor,
    )

private fun ReaderSettings.toLegacyDisplaySnapshot(): LegacyDisplaySettingsSnapshot =
    LegacyDisplaySettingsSnapshot(
        theme = theme.toLegacyDisplayTheme(),
        eInkMode = eInkMode,
        systemLightSepia = systemLightSepia,
        sepiaInvertInDark = sepiaInvertInDark,
        customBackgroundColor = customBackgroundColor,
        customTextColor = customTextColor,
        customInfoColor = customInfoColor,
    )

private fun ReaderTheme.toLegacyDisplayTheme(): LegacyDisplayTheme = when (this) {
    ReaderTheme.System -> LegacyDisplayTheme.System
    ReaderTheme.Light -> LegacyDisplayTheme.Light
    ReaderTheme.Dark -> LegacyDisplayTheme.Dark
    ReaderTheme.Sepia -> LegacyDisplayTheme.Sepia
    ReaderTheme.Custom -> LegacyDisplayTheme.Custom
}

private fun String?.toLegacyDisplayTheme(): LegacyDisplayTheme =
    LegacyDisplayTheme.entries.firstOrNull { it.name == this } ?: LegacyDisplayTheme.System

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
    val selectedFontFamilyId: String? = null,
    val selectedFontVariantId: String? = null,
    val fontVariantSelections: Map<String, String> = emptyMap(),
    val fontSize: Int = 22,
    val furiganaMode: FuriganaMode? = null,
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
    val pageSwipeThresholdPx: Int = ReaderPageSwipeThresholdDefaultPx,
    val horizontalPadding: Int = 5,
    val verticalPadding: Int = 0,
    val topSafeAreaDp: Int = ReaderTopSafeAreaDefaultDp,
    val bottomSafeAreaDp: Int = ReaderBottomSafeAreaDefaultDp,
    val avoidPageBreak: Boolean = false,
    val justifyText: Boolean = false,
    val blurImages: Boolean = false,
    val layoutAdvanced: Boolean = false,
    val lineHeight: Double = 1.65,
    val characterSpacing: Double = 0.0,
    val paragraphSpacing: Double = 0.0,
    val showTitle: Boolean = true,
    val showProgress: Boolean = true,
    val showChapterProgress: Boolean = false,
    val showCharacters: Boolean = true,
    val showPercentage: Boolean = true,
    val alwaysShowProgress: Boolean = true,
    val showProgressTop: Boolean = true,
    val showReaderBackButton: Boolean = true,
    val popupWidth: Int = 500,
    val popupHeight: Int = 500,
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
        selectedFontFamilyId = selectedFontFamilyId,
        selectedFontVariantId = selectedFontVariantId,
        fontVariantSelections = fontVariantSelections,
        fontSize = fontSize,
        furiganaMode = furiganaMode,
        hideFurigana = furiganaMode == FuriganaMode.Hidden,
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
        pageSwipeThresholdPx = pageSwipeThresholdPx.coerceReaderPageSwipeThresholdPx(),
        horizontalPadding = horizontalPadding,
        verticalPadding = verticalPadding,
        topSafeAreaDp = topSafeAreaDp.coerceReaderTopSafeAreaDp(),
        bottomSafeAreaDp = bottomSafeAreaDp.coerceReaderBottomSafeAreaDp(),
        avoidPageBreak = avoidPageBreak,
        justifyText = justifyText,
        blurImages = blurImages,
        layoutAdvanced = layoutAdvanced,
        lineHeight = lineHeight,
        characterSpacing = characterSpacing,
        paragraphSpacing = paragraphSpacing,
        showTitle = showTitle,
        showProgress = showProgress,
        showChapterProgress = showChapterProgress,
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
        selectedFontFamilyId = appearance.selectedFontFamilyId,
        selectedFontVariantId = appearance.selectedFontVariantId,
        fontVariantSelections = appearance.fontVariantSelections,
        fontSize = appearance.fontSize,
        furiganaMode = appearance.furiganaMode ?: FuriganaMode.fromStorage(null, appearance.hideFurigana),
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
        pageSwipeThresholdPx = appearance.pageSwipeThresholdPx.coerceReaderPageSwipeThresholdPx(),
        horizontalPadding = appearance.horizontalPadding,
        verticalPadding = appearance.verticalPadding,
        topSafeAreaDp = appearance.topSafeAreaDp.coerceReaderTopSafeAreaDp(),
        bottomSafeAreaDp = appearance.bottomSafeAreaDp.coerceReaderBottomSafeAreaDp(),
        avoidPageBreak = appearance.avoidPageBreak,
        justifyText = appearance.justifyText,
        blurImages = appearance.blurImages,
        layoutAdvanced = appearance.layoutAdvanced,
        lineHeight = appearance.lineHeight,
        characterSpacing = appearance.characterSpacing,
        paragraphSpacing = appearance.paragraphSpacing,
        showTitle = appearance.showTitle,
        showProgress = appearance.showProgress,
        showChapterProgress = appearance.showChapterProgress,
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

private fun ProfileReaderAppearanceSettings.withLegacyDisplayFrom(
    original: ProfileReaderAppearanceSettings,
): ProfileReaderAppearanceSettings = copy(
    theme = original.theme,
    eInkMode = original.eInkMode,
    uiTheme = original.uiTheme,
    systemLightSepia = original.systemLightSepia,
    sepiaInvertInDark = original.sepiaInvertInDark,
    customBackgroundColor = original.customBackgroundColor,
    customTextColor = original.customTextColor,
    customInfoColor = original.customInfoColor,
)

private fun ReaderSettings.withDisplaySettingsProjection(display: AppDisplaySettings): ReaderSettings {
    if (!display.autoSwitch) {
        return projectLegacyDisplay(display).copy(displaySettings = display)
    }
    val light = display.lightPalette.preset
    val dark = display.darkPalette.preset
    val legacy = when {
        light == DisplayPalettePreset.Light && dark == DisplayPalettePreset.Dark -> copy(
            theme = ReaderTheme.System,
            systemLightSepia = false,
            sepiaInvertInDark = false,
        )
        light == DisplayPalettePreset.Sepia && dark == DisplayPalettePreset.Dark -> copy(
            theme = ReaderTheme.System,
            systemLightSepia = true,
            sepiaInvertInDark = false,
        )
        light == DisplayPalettePreset.Sepia && dark == DisplayPalettePreset.DarkSepia -> copy(
            theme = ReaderTheme.Sepia,
            systemLightSepia = false,
            sepiaInvertInDark = true,
        )
        else -> copy(
            theme = ReaderTheme.System,
            systemLightSepia = false,
            sepiaInvertInDark = false,
        )
    }
    return legacy.copy(eInkMode = display.eInkMode, displaySettings = display)
}

private fun ReaderSettings.projectLegacyDisplay(display: AppDisplaySettings): ReaderSettings {
    val resolved = resolveDisplaySettings(display.copy(eInkMode = false), systemDark = false)
    val legacyTheme = when (resolved.palette) {
        DisplayPalettePreset.Light -> ReaderTheme.Light
        DisplayPalettePreset.Sepia -> ReaderTheme.Sepia
        DisplayPalettePreset.Dark -> ReaderTheme.Dark
        DisplayPalettePreset.DarkSepia,
        DisplayPalettePreset.Custom,
        -> ReaderTheme.Custom
    }
    return copy(
        theme = legacyTheme,
        eInkMode = display.eInkMode,
        uiTheme = if (resolved.isDark) ReaderInterfaceTheme.Dark else ReaderInterfaceTheme.Light,
        systemLightSepia = false,
        sepiaInvertInDark = false,
        customBackgroundColor = resolved.backgroundColor,
        customTextColor = resolved.textColor,
        customInfoColor = resolved.infoColor,
    )
}

internal fun Double.cssNumber(): String =
    String.format(Locale.US, "%.1f", this)

private fun Int.coerceVisualNovelRevealSpeed(): Int =
    (roundToNearestStep(step = 5)).coerceIn(0, 120)

private fun Int.roundToNearestStep(step: Int): Int =
    ((this + step / 2) / step) * step
