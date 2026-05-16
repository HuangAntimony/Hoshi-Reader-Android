package moe.antimony.hoshi.features.dictionary

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.dictionary.DictionaryInfo
import moe.antimony.hoshi.dictionary.DictionaryRename
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.dictionary.DictionaryType
import moe.antimony.hoshi.dictionary.DictionaryUpdateCandidate
import moe.antimony.hoshi.dictionary.DictionaryUpdateProgress
import moe.antimony.hoshi.dictionary.DictionaryUpdateStage
import moe.antimony.hoshi.dictionary.DictionaryUpdateSummary
import moe.antimony.hoshi.dictionary.RecommendedDictionary
import moe.antimony.hoshi.features.anki.AnkiSettings
import moe.antimony.hoshi.features.anki.AnkiSettingsRepository

internal interface DictionaryViewModelRepository {
    suspend fun loadDictionaries(): Map<DictionaryType, List<DictionaryInfo>>
    suspend fun updatableDictionaries(): List<DictionaryUpdateCandidate>
    suspend fun importDictionaries(
        items: List<DictionaryImportItem>,
        onProgress: (DictionaryImportItem) -> Unit,
    )
    suspend fun importRecommendedDictionaries(
        dictionaries: List<RecommendedDictionary>,
        onProgress: (DictionaryUpdateProgress) -> Unit,
    )
    suspend fun updateDictionaries(onProgress: (DictionaryUpdateProgress) -> Unit): DictionaryUpdateSummary
    suspend fun setDictionaryEnabled(type: DictionaryType, fileName: String, enabled: Boolean)
    suspend fun deleteDictionary(type: DictionaryType, fileName: String)
    suspend fun moveDictionary(type: DictionaryType, fromIndex: Int, toIndex: Int)
    suspend fun rebuildLookupQuery()
    val settings: Flow<DictionarySettings>
    suspend fun updateSettings(transform: (DictionarySettings) -> DictionarySettings)
    suspend fun updateAnkiSettings(transform: (AnkiSettings) -> AnkiSettings)
}

internal data class DictionaryImportItem(
    val displayName: String,
    val uri: Uri? = null,
)

internal class AndroidDictionaryViewModelRepository(
    private val contentResolver: ContentResolver,
    private val dictionaryRepository: DictionaryRepository,
    private val settingsRepository: DictionarySettingsRepository,
    private val ankiSettingsRepository: AnkiSettingsRepository,
) : DictionaryViewModelRepository {
    override val settings: Flow<DictionarySettings> = settingsRepository.settings

    override suspend fun loadDictionaries(): Map<DictionaryType, List<DictionaryInfo>> =
        DictionaryType.entries.associateWith { type ->
            dictionaryRepository.loadDictionaries(type)
        }

    override suspend fun importDictionaries(
        items: List<DictionaryImportItem>,
        onProgress: (DictionaryImportItem) -> Unit,
    ) {
        items.forEach { item ->
            onProgress(item)
            dictionaryRepository.importDictionary(contentResolver, requireNotNull(item.uri))
        }
    }

    override suspend fun updatableDictionaries(): List<DictionaryUpdateCandidate> =
        dictionaryRepository.updatableDictionaries()

    override suspend fun importRecommendedDictionaries(
        dictionaries: List<RecommendedDictionary>,
        onProgress: (DictionaryUpdateProgress) -> Unit,
    ) {
        dictionaryRepository.importRecommendedDictionaries(dictionaries, onProgress)
    }

    override suspend fun updateDictionaries(
        onProgress: (DictionaryUpdateProgress) -> Unit,
    ): DictionaryUpdateSummary =
        dictionaryRepository.updateDictionaries(onProgress)

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

    override suspend fun updateSettings(transform: (DictionarySettings) -> DictionarySettings) {
        settingsRepository.update(transform)
    }

    override suspend fun updateAnkiSettings(transform: (AnkiSettings) -> AnkiSettings) {
        ankiSettingsRepository.update(transform)
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
    private val _uiState = MutableStateFlow(DictionaryUiState())

    val uiState: StateFlow<DictionaryUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            repository.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
    }

    fun reload() {
        scope.launch {
            reloadDictionaries(clearError = true)
        }
    }

    fun selectType(type: DictionaryType) {
        _uiState.update { it.copy(selectedType = type) }
    }

    fun importDictionaries(items: List<DictionaryImportItem>) {
        importDictionaries(
            importItems = items,
            importOperation = { onProgress ->
                repository.importDictionaries(items, onProgress)
            },
        )
    }

    fun updateDictionaries() {
        updateDictionaries { onProgress ->
            repository.updateDictionaries(onProgress)
        }
    }

    fun importRecommendedDictionaries(dictionaries: List<RecommendedDictionary>) {
        importRecommendedDictionaries { onProgress ->
            repository.importRecommendedDictionaries(dictionaries, onProgress)
        }
    }

    internal fun importRecommendedDictionaries(
        importOperation: suspend ((DictionaryUpdateProgress) -> Unit) -> Unit,
    ) {
        scope.launch {
            _uiState.update { it.copy(isImporting = true, currentImportMessage = null, errorMessage = null) }
            runCatching {
                withContext(ioDispatcher) {
                    importOperation { progress ->
                        _uiState.update { state ->
                            state.copy(currentImportMessage = progress.message())
                        }
                    }
                }
            }.onSuccess {
                reloadDictionaries(clearError = true)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        errorMessage = error.localizedMessage ?: "Failed to download dictionaries.",
                    )
                }
            }
            _uiState.update { it.copy(isImporting = false, currentImportMessage = null) }
        }
    }

    internal fun updateDictionaries(
        updateOperation: suspend ((DictionaryUpdateProgress) -> Unit) -> DictionaryUpdateSummary,
    ) {
        scope.launch {
            _uiState.update { it.copy(isUpdating = true, currentImportMessage = null, errorMessage = null) }
            runCatching {
                withContext(ioDispatcher) {
                    updateOperation { progress ->
                        _uiState.update { state ->
                            state.copy(currentImportMessage = progress.message())
                        }
                    }
                }
            }.onSuccess { summary ->
                migrateDictionaryTitles(summary.renamedDictionaries)
                reloadDictionaries(clearError = true)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        errorMessage = error.localizedMessage ?: "Failed to update dictionaries.",
                    )
                }
            }
            _uiState.update { it.copy(isUpdating = false, currentImportMessage = null) }
        }
    }

    internal fun importDictionaries(
        importItems: List<DictionaryImportItem>,
        importOperation: suspend ((DictionaryImportItem) -> Unit) -> Unit,
    ) {
        if (importItems.isEmpty()) return
        scope.launch {
            _uiState.update { it.copy(isImporting = true, currentImportMessage = null, errorMessage = null) }
            runCatching {
                withContext(ioDispatcher) {
                    importOperation { item ->
                        _uiState.update { state ->
                            state.copy(currentImportMessage = "Importing ${item.displayName.ifBlank { "dictionary" }}")
                        }
                    }
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
            _uiState.update { it.copy(isImporting = false, currentImportMessage = null) }
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
            repository.updateSettings { current ->
                current.copy(collapsedDictionaries = current.collapsedDictionaries - dictionary.index.title)
            }
            reloadDictionaries(clearError = false)
        }
    }

    fun moveDictionary(fromIndex: Int, toIndex: Int) {
        val type = _uiState.value.selectedType
        _uiState.update { state ->
            val dictionaries = state.dictionaries[type].orEmpty()
            val reordered = DictionaryDragReorder.previewOrder(dictionaries, fromIndex, toIndex)
            if (reordered == dictionaries) {
                state
            } else {
                state.copy(dictionaries = state.dictionaries + (type to reordered))
            }
        }
        scope.launch {
            withContext(ioDispatcher) {
                repository.moveDictionary(type, fromIndex, toIndex)
            }
            reloadDictionaries(clearError = false)
        }
    }

    fun updateSettings(transform: (DictionarySettings) -> DictionarySettings) {
        val next = transform(_uiState.value.settings).normalized()
        _uiState.update { it.copy(settings = next) }
        scope.launch {
            repository.updateSettings { next }
        }
    }

    fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    private suspend fun reloadDictionaries(clearError: Boolean) {
        val (dictionaries, updatableDictionaries) = withContext(ioDispatcher) {
            val dictionaries = repository.loadDictionaries().also {
                repository.rebuildLookupQuery()
            }
            dictionaries to repository.updatableDictionaries()
        }
        _uiState.update { state ->
            state.copy(
                dictionaries = dictionaries,
                updatableDictionaries = updatableDictionaries,
                errorMessage = if (clearError) null else state.errorMessage,
            )
        }
    }

    private suspend fun migrateDictionaryTitles(renames: List<DictionaryRename>) {
        if (renames.isEmpty()) return
        val renameMap = renames.associate { it.oldTitle to it.newTitle }
        repository.updateSettings { current ->
            current.copy(
                collapsedDictionaries = current.collapsedDictionaries.mapTo(mutableSetOf()) { title ->
                    renameMap[title] ?: title
                },
            )
        }
        repository.updateAnkiSettings { current ->
            current.copy(
                fieldMappings = current.fieldMappings.mapValues { (_, template) ->
                    renameMap.entries.fold(template) { value, (oldTitle, newTitle) ->
                        value.replace(
                            oldValue = "{single-glossary-$oldTitle}",
                            newValue = "{single-glossary-$newTitle}",
                        )
                    }
                },
            )
        }
    }

    override fun onCleared() {
        ownedScope?.cancel()
        super.onCleared()
    }
}

private fun DictionaryUpdateProgress.message(): String =
    when (stage) {
        DictionaryUpdateStage.Fetching -> "Fetching $title"
        DictionaryUpdateStage.Checking -> "Checking $title"
        DictionaryUpdateStage.Downloading -> "Downloading $title"
        DictionaryUpdateStage.Importing -> "Importing $title"
    }
