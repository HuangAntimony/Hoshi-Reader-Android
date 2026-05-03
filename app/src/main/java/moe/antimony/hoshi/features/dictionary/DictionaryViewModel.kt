package moe.antimony.hoshi.features.dictionary

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.dictionary.DictionaryInfo
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.dictionary.DictionaryType

internal interface DictionaryViewModelRepository {
    suspend fun loadDictionaries(): Map<DictionaryType, List<DictionaryInfo>>
    suspend fun importDictionaries(uris: List<Uri>, type: DictionaryType)
    suspend fun setDictionaryEnabled(type: DictionaryType, fileName: String, enabled: Boolean)
    suspend fun deleteDictionary(type: DictionaryType, fileName: String)
    suspend fun moveDictionary(type: DictionaryType, fromIndex: Int, toIndex: Int)
    suspend fun rebuildLookupQuery()
    fun loadSettings(): DictionarySettings
    fun saveSettings(settings: DictionarySettings)
}

internal class AndroidDictionaryViewModelRepository(
    private val contentResolver: ContentResolver,
    private val dictionaryRepository: DictionaryRepository,
    private val settingsStore: DictionarySettingsStore,
) : DictionaryViewModelRepository {
    override suspend fun loadDictionaries(): Map<DictionaryType, List<DictionaryInfo>> =
        DictionaryType.entries.associateWith { type ->
            dictionaryRepository.loadDictionaries(type)
        }

    override suspend fun importDictionaries(uris: List<Uri>, type: DictionaryType) {
        uris.forEach { uri ->
            dictionaryRepository.importDictionary(contentResolver, uri, type)
        }
    }

    override suspend fun setDictionaryEnabled(type: DictionaryType, fileName: String, enabled: Boolean) {
        dictionaryRepository.setDictionaryEnabled(type, fileName, enabled)
    }

    override suspend fun deleteDictionary(type: DictionaryType, fileName: String) {
        dictionaryRepository.deleteDictionary(type, fileName)
    }

    override suspend fun moveDictionary(type: DictionaryType, fromIndex: Int, toIndex: Int) {
        dictionaryRepository.moveDictionary(type, fromIndex, toIndex)
    }

    override suspend fun rebuildLookupQuery() {
        dictionaryRepository.rebuildLookupQuery()
    }

    override fun loadSettings(): DictionarySettings = settingsStore.load()

    override fun saveSettings(settings: DictionarySettings) {
        settingsStore.save(settings)
    }
}

internal class DictionaryViewModel(
    private val repository: DictionaryViewModelRepository,
    coroutineScope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val ownedScope = if (coroutineScope == null) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    } else {
        null
    }
    private val scope = coroutineScope ?: ownedScope!!
    private val _uiState = MutableStateFlow(
        DictionaryUiState(settings = repository.loadSettings()),
    )

    val uiState: StateFlow<DictionaryUiState> = _uiState.asStateFlow()

    fun reload() {
        scope.launch {
            reloadDictionaries(clearError = true)
        }
    }

    fun selectType(type: DictionaryType) {
        _uiState.update { it.copy(selectedType = type) }
    }

    fun importDictionaries(uris: List<Uri>, type: DictionaryType) {
        importDictionaries(
            importKeys = uris.map { it.toString() },
            type = type,
            importOperation = {
                repository.importDictionaries(uris, type)
            },
        )
    }

    internal fun importDictionaries(
        importKeys: List<String>,
        type: DictionaryType,
        importOperation: suspend () -> Unit,
    ) {
        if (importKeys.isEmpty()) return
        scope.launch {
            _uiState.update { it.copy(isImporting = true, errorMessage = null) }
            runCatching {
                withContext(ioDispatcher) {
                    importOperation()
                }
            }.onSuccess {
                reloadDictionaries(clearError = true)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        errorMessage = error.localizedMessage ?: "Failed to import dictionary.",
                    )
                }
            }
            _uiState.update { it.copy(isImporting = false) }
        }
    }

    fun setDictionaryEnabled(dictionary: DictionaryInfo, enabled: Boolean) {
        val type = _uiState.value.selectedType
        scope.launch {
            withContext(ioDispatcher) {
                repository.setDictionaryEnabled(type, dictionary.path.name, enabled)
            }
            reloadDictionaries(clearError = false)
        }
    }

    fun deleteDictionary(dictionary: DictionaryInfo) {
        val type = _uiState.value.selectedType
        scope.launch {
            withContext(ioDispatcher) {
                repository.deleteDictionary(type, dictionary.path.name)
            }
            reloadDictionaries(clearError = false)
        }
    }

    fun moveDictionary(fromIndex: Int, toIndex: Int) {
        val type = _uiState.value.selectedType
        scope.launch {
            withContext(ioDispatcher) {
                repository.moveDictionary(type, fromIndex, toIndex)
            }
            reloadDictionaries(clearError = false)
        }
    }

    fun updateSettings(transform: (DictionarySettings) -> DictionarySettings) {
        val next = transform(_uiState.value.settings).normalized()
        repository.saveSettings(next)
        _uiState.update { it.copy(settings = next) }
    }

    fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    private suspend fun reloadDictionaries(clearError: Boolean) {
        val dictionaries = withContext(ioDispatcher) {
            repository.loadDictionaries().also {
                repository.rebuildLookupQuery()
            }
        }
        _uiState.update { state ->
            state.copy(
                dictionaries = dictionaries,
                errorMessage = if (clearError) null else state.errorMessage,
            )
        }
    }

    override fun onCleared() {
        ownedScope?.cancel()
        super.onCleared()
    }
}
