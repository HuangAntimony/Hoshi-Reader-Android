package moe.antimony.hoshi.features.sasayaki

import androidx.annotation.StringRes
import moe.antimony.hoshi.R

internal enum class SasayakiSheetTab(@param:StringRes val labelRes: Int) {
    Resources(R.string.sasayaki_tab_resources),
    Chapters(R.string.sasayaki_tab_chapters),
    Settings(R.string.sasayaki_tab_settings),
}

internal fun sasayakiDefaultSheetTab(
    hasAudio: Boolean,
    hasChapters: Boolean,
): SasayakiSheetTab =
    if (hasAudio && hasChapters) {
        SasayakiSheetTab.Chapters
    } else {
        SasayakiSheetTab.Resources
    }
