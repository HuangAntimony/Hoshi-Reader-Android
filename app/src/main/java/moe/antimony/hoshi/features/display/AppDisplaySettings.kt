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

@Serializable
enum class DisplayPaletteSlot {
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
    val manualPaletteSlot: DisplayPaletteSlot = DisplayPaletteSlot.Light,
    val lightPalette: DisplayPaletteSelection = DisplayPaletteSelection(
        preset = DisplayPalettePreset.Light,
    ),
    val darkPalette: DisplayPaletteSelection = DisplayPaletteSelection(
        preset = DisplayPalettePreset.Dark,
        customBackgroundColor = 0xFF000000L,
        customTextColor = 0xFFFFFFFFL,
    ),
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
    val slot = settings.activePaletteSlot(systemDark)
    val selection = settings.selection(slot)
    val colors = selection.resolvedColors()
    val isDark = slot == DisplayPaletteSlot.Dark
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
            manualPaletteSlot = if (systemDark) DisplayPaletteSlot.Dark else DisplayPaletteSlot.Light,
            eInkDarkTheme = if (eInkMode) systemDark else eInkDarkTheme,
        )
    }
    return copy(autoSwitch = true)
}

fun AppDisplaySettings.activePaletteSlot(systemDark: Boolean): DisplayPaletteSlot = when {
    !autoSwitch -> manualPaletteSlot
    systemDark -> DisplayPaletteSlot.Dark
    else -> DisplayPaletteSlot.Light
}

fun AppDisplaySettings.selection(slot: DisplayPaletteSlot): DisplayPaletteSelection = when (slot) {
    DisplayPaletteSlot.Light -> lightPalette
    DisplayPaletteSlot.Dark -> darkPalette
}

fun AppDisplaySettings.withSelectedPreset(
    slot: DisplayPaletteSlot,
    preset: DisplayPalettePreset,
): AppDisplaySettings {
    val updated = when (slot) {
        DisplayPaletteSlot.Light -> copy(lightPalette = lightPalette.copy(preset = preset))
        DisplayPaletteSlot.Dark -> copy(darkPalette = darkPalette.copy(preset = preset))
    }
    return if (autoSwitch) updated else updated.copy(manualPaletteSlot = slot)
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
        DisplayPaletteSlot.Light -> copy(lightPalette = lightPalette.updated())
        DisplayPaletteSlot.Dark -> copy(darkPalette = darkPalette.updated())
    }
}

internal fun AppDisplaySettings.normalized(): AppDisplaySettings = copy(
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

/** Used only for v1.3.3 migration; runtime brightness belongs to the selected slot. */
internal fun DisplayPaletteSelection.legacySlot(): DisplayPaletteSlot = if (when (preset) {
    DisplayPalettePreset.Light,
    DisplayPalettePreset.Sepia,
    -> false
    DisplayPalettePreset.Dark,
    DisplayPalettePreset.DarkSepia,
    -> true
    DisplayPalettePreset.Custom -> customBackgroundColor.rgbLuminance() < 0.5
}) DisplayPaletteSlot.Dark else DisplayPaletteSlot.Light

private const val OpaqueBlack = 0xFF000000L
private const val OpaqueWhite = 0xFFFFFFFFL
