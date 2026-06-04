package moe.antimony.hoshi.features.dictionary

import de.manhhao.hoshi.FrequencyEntry
import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.PitchEntry
import de.manhhao.hoshi.TermResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.antimony.hoshi.ui.UiText

class DictionarySearchViewModelTest {
    @Test
    fun initializesLookupQueryLikePreviousComposableLaunchEffect() {
        val repository = FakeDictionarySearchRepository()

        viewModel(repository)

        assertEquals(1, repository.rebuildCount)
    }

    @Test
    fun lookupPublishesRenderedStateAndResetsPopupAndHistoryState() {
        val repository = FakeDictionarySearchRepository(
            lookupResults = listOf(lookupResult("猫")),
            dictionaryStyles = mapOf("JMdict" to ".entry {}"),
            dictionarySettings = DictionarySettings(
                lookupLanguage = DictionaryLanguage.French,
                maxResults = 2,
                scanLength = 7,
            ),
            audioSettings = AudioSettings(enableAutoplay = true),
        )
        val viewModel = viewModel(repository)
        viewModel.setPopups(listOf(popup("old")))
        viewModel.recordLookupRedirected(1)
        viewModel.navigateBack()

        viewModel.updateQuery(" 猫 ")
        viewModel.runLookup(
            assets = LookupPopupAssets(
                popupJs = "window.renderPopup = function() {};",
                popupCss = ".entry {}",
            ),
            darkMode = true,
            eInkMode = true,
        )

        val state = viewModel.uiState.value
        assertEquals("猫", state.lastQuery)
        assertTrue(state.hasSearched)
        assertFalse(state.isSearching)
        assertNull(state.errorMessage)
        assertEquals(listOf("猫:2:7:fr"), repository.lookupCalls)
        assertEquals(2, repository.rebuildCount)
        assertEquals(1, state.results.size)
        assertEquals("猫", state.results.single().matched)
        assertTrue(state.html.contains("window.lookupEntries = ["))
        assertTrue(state.html.contains("""data-hoshi-color-scheme="dark""""))
        assertTrue(state.html.contains("""data-hoshi-eink-mode="true""""))
        assertEquals(mapOf("JMdict" to ".entry {}"), state.dictionaryStyles)
        assertEquals(emptyList<LookupPopupItem>(), state.popups)
        assertEquals(0, state.resultClearSelectionSignal)
        assertEquals(0, state.backCount)
        assertEquals(0, state.forwardCount)
        assertEquals(0, state.backSignal)
        assertEquals(0, state.forwardSignal)
    }

    @Test
    fun blankLookupClearsContentWithoutCallingLookupEngine() {
        val repository = FakeDictionarySearchRepository(
            lookupResults = listOf(lookupResult("猫")),
        )
        val viewModel = viewModel(repository)

        viewModel.updateQuery("   ")
        viewModel.runLookup(assets = LookupPopupAssets(popupJs = "", popupCss = ""))

        val state = viewModel.uiState.value
        assertEquals("", state.lastQuery)
        assertEquals("", state.html)
        assertEquals(emptyList<LookupResult>(), state.results)
        assertFalse(state.hasResults)
        assertTrue(state.hasSearched)
        assertEquals(emptyList<String>(), repository.lookupCalls)
    }

    @Test
    fun failedLookupClearsContentAndPublishesError() {
        val repository = FakeDictionarySearchRepository(error = IllegalStateException("native lookup failed"))
        val viewModel = viewModel(repository)

        viewModel.updateQuery("猫")
        viewModel.runLookup(assets = LookupPopupAssets(popupJs = "", popupCss = ""))

        val state = viewModel.uiState.value
        assertEquals("", state.html)
        assertEquals(emptyList<LookupResult>(), state.results)
        assertEquals(emptyMap<String, String>(), state.dictionaryStyles)
        assertEquals(emptyList<LookupPopupItem>(), state.popups)
        assertTrue(state.hasSearched)
        assertFalse(state.isSearching)
        assertEquals(UiText.Literal("native lookup failed"), state.errorMessage)
    }

    @Test
    fun settingsAndAudioFlowsUpdateSearchState() {
        val repository = FakeDictionarySearchRepository()
        val viewModel = viewModel(repository)

        repository.dictionarySettingsFlow.value = DictionarySettings(maxResults = 9, scanLength = 10)
        repository.audioSettingsFlow.value = AudioSettings(enableAutoplay = true)

        assertEquals(9, viewModel.uiState.value.dictionarySettings.maxResults)
        assertEquals(10, viewModel.uiState.value.dictionarySettings.scanLength)
        assertTrue(viewModel.uiState.value.audioSettings.enableAutoplay)
    }

    @Test
    fun popupAndRedirectEventsUpdateSearchState() {
        val repository = FakeDictionarySearchRepository(
            lookupResults = listOf(lookupResult("食べる")),
            dictionaryStyles = mapOf("Dict" to ".term {}"),
            dictionarySettings = DictionarySettings(
                lookupLanguage = DictionaryLanguage.German,
                maxResults = 4,
                scanLength = 12,
            ),
        )
        val viewModel = viewModel(repository)
        val highlightCount = viewModel.openRootPopup(
            selection = selection("食べる"),
            options = LookupPopupOptions(
                isVertical = false,
                dictionarySettings = viewModel.uiState.value.dictionarySettings,
            ),
        )

        assertEquals(3, highlightCount)
        assertEquals(1, viewModel.uiState.value.popups.size)
        assertEquals(listOf("食べる:4:12:de"), repository.lookupCalls)

        viewModel.recordLookupRedirected(2)
        viewModel.navigateBack()
        assertEquals(1, viewModel.uiState.value.backSignal)
        assertEquals(0, viewModel.uiState.value.backCount)
        assertEquals(1, viewModel.uiState.value.forwardCount)

        viewModel.navigateForward()
        assertEquals(1, viewModel.uiState.value.forwardSignal)
        assertEquals(1, viewModel.uiState.value.backCount)
        assertEquals(0, viewModel.uiState.value.forwardCount)

        viewModel.dismissRootPopup()
        assertEquals(emptyList<LookupPopupItem>(), viewModel.uiState.value.popups)
        assertEquals(1, viewModel.uiState.value.resultClearSelectionSignal)
    }

    @Test
    fun rootPopupSelectionWithNoResultsStillClearsExistingPopups() {
        val repository = FakeDictionarySearchRepository()
        val viewModel = viewModel(repository)
        viewModel.setPopups(listOf(popup("old")))

        val highlightCount = viewModel.openRootPopup(
            selection = selection("missing"),
            options = LookupPopupOptions(isVertical = false),
        )

        assertNull(highlightCount)
        assertEquals(emptyList<LookupPopupItem>(), viewModel.uiState.value.popups)
    }

    private fun viewModel(repository: FakeDictionarySearchRepository): DictionarySearchViewModel =
        DictionarySearchViewModel(
            repository = repository,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun popup(id: String): LookupPopupItem = LookupPopupItem(
        id = id,
        state = LookupPopupState(
            selection = selection("old"),
            results = listOf(lookupResult("old")),
        ),
    )

    private fun selection(text: String): ReaderSelectionData = ReaderSelectionData(
        text = text,
        sentence = text,
        rect = ReaderSelectionRect(x = 1.0, y = 2.0, width = 3.0, height = 4.0),
        normalizedOffset = 0,
    )

    private fun lookupResult(matched: String): LookupResult = LookupResult(
        matched = matched,
        deinflected = matched,
        process = emptyArray(),
        term = TermResult(
            expression = matched,
            reading = matched,
            rules = "",
            glossaries = arrayOf(
                GlossaryEntry(
                    dictName = "JMdict",
                    glossary = "glossary",
                    definitionTags = "",
                    termTags = "",
                ),
            ),
            frequencies = emptyArray<FrequencyEntry>(),
            pitches = emptyArray<PitchEntry>(),
        ),
        preprocessorSteps = 0,
    )
}

private class FakeDictionarySearchRepository(
    lookupResults: List<LookupResult> = emptyList(),
    private val dictionaryStyles: Map<String, String> = emptyMap(),
    dictionarySettings: DictionarySettings = DictionarySettings(),
    audioSettings: AudioSettings = AudioSettings(),
    private val error: Throwable? = null,
) : DictionarySearchRepository {
    val dictionarySettingsFlow = MutableStateFlow(dictionarySettings)
    val audioSettingsFlow = MutableStateFlow(audioSettings)
    val lookupCalls = mutableListOf<String>()
    var rebuildCount = 0
    private var lookupResults = lookupResults

    override val dictionarySettings: StateFlow<DictionarySettings> = dictionarySettingsFlow
    override val audioSettings: StateFlow<AudioSettings> = audioSettingsFlow

    override suspend fun rebuildLookupQuery() {
        rebuildCount += 1
    }

    override fun lookup(query: String, maxResults: Int, scanLength: Int, language: String): List<LookupResult> {
        lookupCalls += "$query:$maxResults:$scanLength:$language"
        error?.let { throw it }
        return lookupResults
    }

    override fun dictionaryStyles(): Map<String, String> = dictionaryStyles
}
