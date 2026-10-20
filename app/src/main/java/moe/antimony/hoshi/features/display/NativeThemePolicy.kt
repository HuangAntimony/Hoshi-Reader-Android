package moe.antimony.hoshi.features.display

internal enum class NativeThemePolicy { FollowSystem, Light, Dark }

internal fun nativeThemePolicy(settings: AppDisplaySettings, systemDark: Boolean): NativeThemePolicy = when {
    settings.autoSwitch -> NativeThemePolicy.FollowSystem
    resolveDisplaySettings(settings, systemDark).isDark -> NativeThemePolicy.Dark
    else -> NativeThemePolicy.Light
}
