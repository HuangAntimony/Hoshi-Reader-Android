package moe.antimony.hoshi.features.dictionary

import de.manhhao.hoshi.LookupResult
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.anki.AnkiPopupSettings
import moe.antimony.hoshi.ui.UiText

internal data class DictionarySearchUiState(
    val query: String = "",
    val lastQuery: String = "",
    val html: String = "",
    val results: List<LookupResult> = emptyList(),
    val hasSearched: Boolean = false,
    val isSearching: Boolean = false,
    val errorMessage: UiText? = null,
    val dictionaryStyles: Map<String, String> = emptyMap(),
    val dictionarySettings: DictionarySettings = DictionarySettings(),
    val audioSettings: AudioSettings = AudioSettings(),
    val popups: List<LookupPopupItem> = emptyList(),
    val resultClearSelectionSignal: Int = 0,
    val backCount: Int = 0,
    val forwardCount: Int = 0,
    val backSignal: Int = 0,
    val forwardSignal: Int = 0,
) {
    val hasResults: Boolean get() = results.isNotEmpty()
}

internal data class DictionarySearchRenderState(
    val lastQuery: String,
    val html: String,
    val results: List<LookupResult>,
    val hasResults: Boolean,
    val dictionaryStyles: Map<String, String>,
)

internal object DictionarySearchContent {
    fun runLookup(
        query: String,
        lookup: (String) -> List<LookupResult>,
        assets: LookupPopupAssets? = null,
        dictionaryStyles: Map<String, String> = emptyMap(),
        dictionarySettings: DictionarySettings = DictionarySettings(),
        darkMode: Boolean = false,
        eInkMode: Boolean = false,
        audioSettings: AudioSettings = AudioSettings(),
        ankiSettings: AnkiPopupSettings = AnkiPopupSettings(),
        fontFaceCss: String = "",
        popupScale: Double = 1.0,
        topSpacerPx: Int = 0,
    ): DictionarySearchRenderState {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return DictionarySearchRenderState(
                lastQuery = "",
                html = "",
                results = emptyList(),
                hasResults = false,
                dictionaryStyles = emptyMap(),
            )
        }
        val results = lookup(trimmed)
        if (results.isEmpty()) {
            return DictionarySearchRenderState(
                lastQuery = trimmed,
                html = "",
                results = emptyList(),
                hasResults = false,
                dictionaryStyles = emptyMap(),
            )
        }
        return renderExistingResults(
            lastQuery = trimmed,
            results = results,
            assets = assets,
            dictionaryStyles = dictionaryStyles,
            dictionarySettings = dictionarySettings,
            darkMode = darkMode,
            eInkMode = eInkMode,
            audioSettings = audioSettings,
            ankiSettings = ankiSettings,
            fontFaceCss = fontFaceCss,
            popupScale = popupScale,
            topSpacerPx = topSpacerPx,
        )
    }

    fun renderExistingResults(
        lastQuery: String,
        results: List<LookupResult>,
        assets: LookupPopupAssets? = null,
        dictionaryStyles: Map<String, String> = emptyMap(),
        dictionarySettings: DictionarySettings = DictionarySettings(),
        darkMode: Boolean = false,
        eInkMode: Boolean = false,
        audioSettings: AudioSettings = AudioSettings(),
        ankiSettings: AnkiPopupSettings = AnkiPopupSettings(),
        fontFaceCss: String = "",
        popupScale: Double = 1.0,
        topSpacerPx: Int = 0,
    ): DictionarySearchRenderState {
        if (results.isEmpty()) {
            return DictionarySearchRenderState(
                lastQuery = lastQuery,
                html = "",
                results = emptyList(),
                hasResults = false,
                dictionaryStyles = emptyMap(),
            )
        }
        return DictionarySearchRenderState(
            lastQuery = lastQuery,
            html = LookupPopupHtml.render(
                results = results,
                assets = assets,
                dictionaryStyles = dictionaryStyles,
                topSpacerPx = topSpacerPx,
                settings = dictionarySettings,
                darkMode = darkMode,
                eInkMode = eInkMode,
                audioSettings = audioSettings,
                ankiSettings = ankiSettings,
                fontFaceCss = fontFaceCss,
                popupScale = popupScale,
            ),
            results = results,
            hasResults = true,
            dictionaryStyles = dictionaryStyles,
        )
    }
}
