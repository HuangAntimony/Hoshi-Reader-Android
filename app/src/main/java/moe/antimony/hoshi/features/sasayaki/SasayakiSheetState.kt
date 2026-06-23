package moe.antimony.hoshi.features.sasayaki

import androidx.annotation.StringRes
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiSidecarRepository

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

internal data class SasayakiMatchWindowDependencies(
    val bookEntry: BookEntry,
    val bookRepository: SasayakiSidecarRepository,
    val epubBookParser: EpubBookParser,
)

internal fun sasayakiSubtitleMatchSummary(matchData: SasayakiMatchData?): String? =
    matchData?.matchRateText()
