package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.sasayaki.SasayakiSettings

internal data class ReaderSasayakiColors(
    val textColor: Long,
    val backgroundColor: Long,
)

internal fun readerSasayakiColors(
    settings: ReaderSettings,
    sasayakiSettings: SasayakiSettings,
    systemDark: Boolean,
): ReaderSasayakiColors {
    val useDarkColors = settings.usesDarkInterface(systemDark)
    return ReaderSasayakiColors(
        textColor = sasayakiSettings.textColor(useDarkColors),
        backgroundColor = sasayakiSettings.backgroundColor(useDarkColors),
    )
}
