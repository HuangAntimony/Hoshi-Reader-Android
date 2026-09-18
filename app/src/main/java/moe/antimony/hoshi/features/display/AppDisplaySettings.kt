package moe.antimony.hoshi.features.display

import kotlinx.serialization.Serializable

@Serializable
enum class DisplayPalettePreset {
    Light,
    Sepia,
    Dark,
    DarkSepia,
    Custom,
}

enum class DisplayPaletteSlot {
    Single,
    Light,
    Dark,
}

@Serializable
enum class DisplayAccentSource {
    System,
    Custom,
}

@Serializable
data class DisplayPaletteSelection(
    val preset: DisplayPalettePreset = DisplayPalettePreset.Light,
    val customBackgroundColor: Long = 0xFFFFFFFFL,
    val customTextColor: Long = 0xFF000000L,
    val customInfoColor: Long = 0xFF999999L,
)

@Serializable
data class AppDisplaySettings(
    val autoSwitch: Boolean = true,
    val singlePalette: DisplayPaletteSelection = DisplayPaletteSelection(),
    val lightPalette: DisplayPaletteSelection = DisplayPaletteSelection(
        preset = DisplayPalettePreset.Light,
    ),
    val darkPalette: DisplayPaletteSelection = DisplayPaletteSelection(
        preset = DisplayPalettePreset.Dark,
        customBackgroundColor = 0xFF000000L,
        customTextColor = 0xFFFFFFFFL,
    ),
    val automaticInitialized: Boolean = true,
    val accentSource: DisplayAccentSource = DisplayAccentSource.System,
    val accentSeed: Long = DefaultAccentSeed,
    val eInkMode: Boolean = false,
    val eInkDarkTheme: Boolean? = null,
    val migrationVersion: Int = 0,
) {
    companion object {
        const val DefaultAccentSeed: Long = 0xFF6650A4L
    }
}

data class ResolvedDisplaySettings(
    val palette: DisplayPalettePreset,
    val selection: DisplayPaletteSelection,
    val backgroundColor: Long,
    val textColor: Long,
    val infoColor: Long,
    val isDark: Boolean,
    val eInkMode: Boolean,
)

fun resolveDisplaySettings(
    settings: AppDisplaySettings,
    systemDark: Boolean,
): ResolvedDisplaySettings {
    val selection = when {
        !settings.autoSwitch -> settings.singlePalette
        systemDark -> settings.darkPalette
        else -> settings.lightPalette
    }
    val colors = selection.resolvedColors()
    val isDark = resolvePaletteIsDark(selection)
    if (settings.eInkMode) {
        val eInkDark = if (settings.autoSwitch) systemDark else settings.eInkDarkTheme ?: isDark
        val text = if (eInkDark) OpaqueWhite else OpaqueBlack
        return ResolvedDisplaySettings(
            palette = selection.preset,
            selection = selection,
            backgroundColor = if (eInkDark) OpaqueBlack else OpaqueWhite,
            textColor = text,
            infoColor = text,
            isDark = eInkDark,
            eInkMode = true,
        )
    }
    return ResolvedDisplaySettings(
        palette = selection.preset,
        selection = selection,
        backgroundColor = colors.background,
        textColor = colors.text,
        infoColor = colors.info,
        isDark = isDark,
        eInkMode = false,
    )
}

fun AppDisplaySettings.withAutoSwitch(enabled: Boolean, systemDark: Boolean): AppDisplaySettings {
    if (enabled == autoSwitch) return this
    if (!enabled) {
        return copy(
            autoSwitch = false,
            singlePalette = if (systemDark) darkPalette else lightPalette,
            eInkDarkTheme = if (eInkMode) systemDark else eInkDarkTheme,
        )
    }
    if (automaticInitialized) return copy(autoSwitch = true)
    return if (resolvePaletteIsDark(singlePalette)) {
        copy(
            autoSwitch = true,
            darkPalette = singlePalette,
            lightPalette = defaultLightPalette(),
            automaticInitialized = true,
        )
    } else {
        copy(
            autoSwitch = true,
            lightPalette = singlePalette,
            darkPalette = defaultDarkPalette(),
            automaticInitialized = true,
        )
    }
}

fun AppDisplaySettings.withSelectedPreset(
    slot: DisplayPaletteSlot,
    preset: DisplayPalettePreset,
): AppDisplaySettings = when (slot) {
    DisplayPaletteSlot.Single -> copy(singlePalette = singlePalette.copy(preset = preset))
    DisplayPaletteSlot.Light -> copy(lightPalette = lightPalette.copy(preset = preset))
    DisplayPaletteSlot.Dark -> copy(darkPalette = darkPalette.copy(preset = preset))
}

fun AppDisplaySettings.withCustomPalette(
    slot: DisplayPaletteSlot,
    backgroundColor: Long,
    textColor: Long,
    infoColor: Long,
): AppDisplaySettings {
    fun DisplayPaletteSelection.updated() = copy(
        customBackgroundColor = backgroundColor.argbColor(),
        customTextColor = textColor.argbColor(),
        customInfoColor = infoColor.argbColor(),
    )
    return when (slot) {
        DisplayPaletteSlot.Single -> copy(singlePalette = singlePalette.updated())
        DisplayPaletteSlot.Light -> copy(lightPalette = lightPalette.updated())
        DisplayPaletteSlot.Dark -> copy(darkPalette = darkPalette.updated())
    }
}

internal fun AppDisplaySettings.normalized(): AppDisplaySettings = copy(
    singlePalette = singlePalette.normalized(),
    lightPalette = lightPalette.normalized(),
    darkPalette = darkPalette.normalized(),
    accentSeed = accentSeed.opaqueColor(),
)

private data class DisplayColors(
    val background: Long,
    val text: Long,
    val info: Long,
)

private fun DisplayPaletteSelection.resolvedColors(): DisplayColors = when (preset) {
    DisplayPalettePreset.Light -> DisplayColors(OpaqueWhite, OpaqueBlack, 0xB3111111L)
    DisplayPalettePreset.Sepia -> DisplayColors(0xFFF2E2C9L, 0xFF332A1BL, 0xB35C5448L)
    DisplayPalettePreset.Dark -> DisplayColors(OpaqueBlack, OpaqueWhite, 0xCCFFFFFFL)
    DisplayPalettePreset.DarkSepia -> DisplayColors(0xFF17150FL, 0xFFF2E2C9L, 0xCCF2E2C9L)
    DisplayPalettePreset.Custom -> DisplayColors(
        customBackgroundColor.argbColor(),
        customTextColor.argbColor(),
        customInfoColor.argbColor(),
    )
}

private fun DisplayPaletteSelection.normalized(): DisplayPaletteSelection = copy(
    customBackgroundColor = customBackgroundColor.argbColor(),
    customTextColor = customTextColor.argbColor(),
    customInfoColor = customInfoColor.argbColor(),
)

private fun Long.argbColor(): Long = this and 0xFFFFFFFFL

private fun Long.opaqueColor(): Long = (this and 0x00FFFFFFL) or 0xFF000000L

private fun Long.rgbLuminance(): Double {
    fun linear(channel: Long): Double {
        val encoded = channel.toDouble() / 255.0
        return if (encoded <= 0.04045) encoded / 12.92 else Math.pow((encoded + 0.055) / 1.055, 2.4)
    }
    val red = linear((this ushr 16) and 0xFF)
    val green = linear((this ushr 8) and 0xFF)
    val blue = linear(this and 0xFF)
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}

private fun resolvePaletteIsDark(selection: DisplayPaletteSelection): Boolean = when (selection.preset) {
    DisplayPalettePreset.Light,
    DisplayPalettePreset.Sepia,
    -> false
    DisplayPalettePreset.Dark,
    DisplayPalettePreset.DarkSepia,
    -> true
    DisplayPalettePreset.Custom -> selection.customBackgroundColor.rgbLuminance() < 0.5
}

private fun defaultLightPalette(): DisplayPaletteSelection = DisplayPaletteSelection(
    preset = DisplayPalettePreset.Light,
)

private fun defaultDarkPalette(): DisplayPaletteSelection = DisplayPaletteSelection(
    preset = DisplayPalettePreset.Dark,
    customBackgroundColor = OpaqueBlack,
    customTextColor = OpaqueWhite,
)

private const val OpaqueBlack = 0xFF000000L
private const val OpaqueWhite = 0xFFFFFFFFL
