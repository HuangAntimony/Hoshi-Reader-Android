package moe.antimony.hoshi.features.dictionary

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import moe.antimony.hoshi.dictionary.DictionaryIndex
import moe.antimony.hoshi.dictionary.DictionaryInfo
import moe.antimony.hoshi.dictionary.DictionaryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DictionaryViewModelTest {
    @Test
    fun reloadPublishesDictionariesSettingsAndRebuildsLookupQuery() {
        val term = dictionary("term", "JMdict")
        val repository = FakeDictionaryRepository(
            dictionaries = mapOf(DictionaryType.Term to listOf(term)),
            settings = DictionarySettings(dictionaryTabDefault = true, maxResults = 7),
        )
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.reload()

        assertEquals(DictionaryType.Term, viewModel.uiState.value.selectedType)
        assertEquals(listOf(term), viewModel.uiState.value.currentDictionaries)
        assertEquals(7, viewModel.uiState.value.settings.maxResults)
        assertFalse(viewModel.uiState.value.isImporting)
        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals(1, repository.rebuildCount)
    }

    @Test
    fun selectedTypeControlsCurrentDictionaries() {
        val frequency = dictionary("freq", "Jiten")
        val repository = FakeDictionaryRepository(
            dictionaries = mapOf(DictionaryType.Frequency to listOf(frequency)),
        )
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.reload()

        viewModel.selectType(DictionaryType.Frequency)

        assertEquals(DictionaryType.Frequency, viewModel.uiState.value.selectedType)
        assertEquals(listOf(frequency), viewModel.uiState.value.currentDictionaries)
    }

    @Test
    fun importSuccessRefreshesDictionariesAndClearsImportState() {
        val repository = FakeDictionaryRepository()
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )
        val imported = dictionary("pitch", "Pitch")
        repository.onImport = {
            repository.dictionaries = repository.dictionaries + (DictionaryType.Pitch to listOf(imported))
        }

        viewModel.importDictionaries(
            importKeys = listOf("pitch.zip"),
            type = DictionaryType.Pitch,
            importOperation = { repository.onImport!!.invoke() },
        )

        assertFalse(viewModel.uiState.value.isImporting)
        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals(listOf(imported), viewModel.uiState.value.dictionaries[DictionaryType.Pitch])
        assertEquals(1, repository.rebuildCount)
    }

    @Test
    fun importFailureKeepsExistingDictionariesAndCanRetry() {
        val existing = dictionary("term", "Existing")
        val repository = FakeDictionaryRepository(
            dictionaries = mapOf(DictionaryType.Term to listOf(existing)),
        )
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.reload()

        viewModel.importDictionaries(
            importKeys = listOf("bad.zip"),
            type = DictionaryType.Term,
            importOperation = { error("bad archive") },
        )

        assertFalse(viewModel.uiState.value.isImporting)
        assertEquals("bad archive", viewModel.uiState.value.errorMessage)
        assertEquals(listOf(existing), viewModel.uiState.value.currentDictionaries)

        viewModel.importDictionaries(
            importKeys = listOf("good.zip"),
            type = DictionaryType.Term,
            importOperation = {},
        )

        assertNull(viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isImporting)
    }

    @Test
    fun enabledDeleteMoveAndSettingsChangesPersistThenReload() {
        val dictionary = dictionary("term", "JMdict")
        val repository = FakeDictionaryRepository(
            dictionaries = mapOf(DictionaryType.Term to listOf(dictionary)),
        )
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.reload()

        viewModel.setDictionaryEnabled(dictionary, false)
        viewModel.moveDictionary(0, 0)
        viewModel.deleteDictionary(dictionary)
        viewModel.updateSettings { it.copy(maxResults = 100, compactPitchAccents = false) }

        assertEquals(listOf("term:false"), repository.enabledCalls)
        assertEquals(listOf(DictionaryType.Term to (0 to 0)), repository.moveCalls)
        assertEquals(listOf("term"), repository.deleteCalls)
        assertEquals(50, viewModel.uiState.value.settings.maxResults)
        assertFalse(viewModel.uiState.value.settings.compactPitchAccents)
        assertEquals(viewModel.uiState.value.settings, repository.savedSettings)
        assertTrue(repository.loadDictionariesCount >= 4)
    }

    private companion object {
        val testScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)

        fun dictionary(fileName: String, title: String) = DictionaryInfo(
            index = DictionaryIndex(title = title, format = 3, revision = "rev"),
            path = File(fileName),
        )
    }
}

private class FakeDictionaryRepository(
    var dictionaries: Map<DictionaryType, List<DictionaryInfo>> = emptyMap(),
    settings: DictionarySettings = DictionarySettings(),
) : DictionaryViewModelRepository {
    private val settingsFlow = MutableStateFlow(settings)
    override val settings: StateFlow<DictionarySettings> = settingsFlow
    var rebuildCount = 0
    var loadDictionariesCount = 0
    var onImport: (() -> Unit)? = null
    val enabledCalls = mutableListOf<String>()
    val deleteCalls = mutableListOf<String>()
    val moveCalls = mutableListOf<Pair<DictionaryType, Pair<Int, Int>>>()
    var savedSettings: DictionarySettings? = null

    override suspend fun loadDictionaries(): Map<DictionaryType, List<DictionaryInfo>> {
        loadDictionariesCount += 1
        return dictionaries
    }

    override suspend fun importDictionaries(uris: List<Uri>, type: DictionaryType) = Unit

    override suspend fun setDictionaryEnabled(type: DictionaryType, fileName: String, enabled: Boolean) {
        enabledCalls += "$fileName:$enabled"
    }

    override suspend fun deleteDictionary(type: DictionaryType, fileName: String) {
        deleteCalls += fileName
    }

    override suspend fun moveDictionary(type: DictionaryType, fromIndex: Int, toIndex: Int) {
        moveCalls += type to (fromIndex to toIndex)
    }

    override suspend fun rebuildLookupQuery() {
        rebuildCount += 1
    }

    override suspend fun updateSettings(transform: (DictionarySettings) -> DictionarySettings) {
        val next = transform(settingsFlow.value).normalized()
        settingsFlow.value = next
        savedSettings = next
    }
}
