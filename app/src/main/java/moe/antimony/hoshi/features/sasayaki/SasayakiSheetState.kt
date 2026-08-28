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

internal fun sasayakiShouldShowPlaybackHeader(hasAudio: Boolean): Boolean =
    hasAudio

internal data class SasayakiMatchDependencies(
    val bookEntry: BookEntry,
    val bookRepository: SasayakiSidecarRepository,
    val epubBookParser: EpubBookParser,
)

internal fun sasayakiSubtitleMatchSummary(matchData: SasayakiMatchData?): String? =
    matchData?.matchRateText()

internal data class SasayakiSubtitleMatchUiState(
    val selectedFileName: String? = null,
    val isMatching: Boolean = false,
    val errorMessage: String? = null,
) {
    fun acceptFile(fileName: String): SasayakiSubtitleSelectionTransition =
        if (isMatching) {
            SasayakiSubtitleSelectionTransition(state = this, shouldStartMatching = false)
        } else {
            SasayakiSubtitleSelectionTransition(
                state = copy(
                    selectedFileName = fileName,
                    isMatching = true,
                    errorMessage = null,
                ),
                shouldStartMatching = true,
            )
        }

    fun finishMatching(errorMessage: String?): SasayakiSubtitleMatchUiState =
        copy(isMatching = false, errorMessage = errorMessage)
}

internal data class SasayakiSubtitleSelectionTransition(
    val state: SasayakiSubtitleMatchUiState,
    val shouldStartMatching: Boolean,
)
