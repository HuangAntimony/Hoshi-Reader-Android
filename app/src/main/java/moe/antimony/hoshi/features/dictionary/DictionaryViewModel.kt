package moe.antimony.hoshi.features.dictionary

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.dictionary.DictionaryInfo
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.dictionary.DictionaryType
import moe.antimony.hoshi.dictionary.DictionaryUpdateCandidate
import moe.antimony.hoshi.dictionary.DictionaryUpdateProgress
import moe.antimony.hoshi.dictionary.DictionaryUpdateStage
import moe.antimony.hoshi.dictionary.DictionaryUpdateSummary
import moe.antimony.hoshi.dictionary.RecommendedDictionary
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.features.anki.AnkiSettings
import moe.antimony.hoshi.features.anki.AnkiSettingsRepository
import moe.antimony.hoshi.ui.UiText

internal interface DictionaryViewModelRepository {
    suspend fun loadDictionaries(): Map<DictionaryType, List<DictionaryInfo>>
    suspend fun updatableDictionaries(): List<DictionaryUpdateCandidate>
    suspend fun importDictionaries(
        items: List<DictionaryImportItem>,
        onProgress: (DictionaryImportItem) -> Unit,
    ): DictionaryImportBatchResult
    suspend fun importRecommendedDictionaries(
        dictionaries: List<RecommendedDictionary>,
        onProgress: (DictionaryUpdateProgress) -> Unit,
    )
    suspend fun updateDictionaries(onProgress: (DictionaryUpdateProgress) -> Unit): DictionaryUpdateSummary
    suspend fun setDictionaryEnabled(type: DictionaryType, fileName: String, enabled: Boolean): Boolean
    suspend fun deleteDictionary(type: DictionaryType, fileName: String, title: String): Boolean
    suspend fun moveDictionary(type: DictionaryType, fromIndex: Int, toIndex: Int): Boolean
    suspend fun rebuildLookupQuery()
    val settings: Flow<DictionarySettings>
    val mutationState: StateFlow<DictionaryMutationState>
    suspend fun updateSettings(transform: (DictionarySettings) -> DictionarySettings)
    suspend fun updateAnkiSettings(transform: (AnkiSettings) -> AnkiSettings)
}

internal data class DictionaryImportItem(
    val displayName: String,
    val uri: Uri? = null,
)

internal data class DictionaryImportBatchResult(
    val imported: List<DictionaryImportItem>,
    val failed: List<DictionaryImportItem>,
)

@Singleton
internal class AndroidDictionaryViewModelRepository @Inject constructor(
    private val contentResolver: ContentResolver,
    private val dictionaryRepository: DictionaryRepository,
    private val settingsRepository: DictionarySettingsRepository,
    private val ankiSettingsRepository: AnkiSettingsRepository,
    private val dictionaryUpdateService: DictionaryUpdateService,
    private val mutationCoordinator: DictionaryMutationCoordinator,
) : DictionaryViewModelRepository {
    override val settings: Flow<DictionarySettings> = settingsRepository.settings
    override val mutationState: StateFlow<DictionaryMutationState> = mutationCoordinator.state

    override suspend fun loadDictionaries(): Map<DictionaryType, List<DictionaryInfo>> =
        DictionaryType.entries.associateWith { type ->
            dictionaryRepository.loadDictionaries(type)
        }

    override suspend fun importDictionaries(
        items: List<DictionaryImportItem>,
        onProgress: (DictionaryImportItem) -> Unit,
    ): DictionaryImportBatchResult =
        mutationCoordinator.runExclusive(DictionaryMutationOperation.Import) {
            val lowRamImport = settingsRepository.settings.first().lowRamDictionaryImport
            val imported = mutableListOf<DictionaryImportItem>()
            val failed = mutableListOf<DictionaryImportItem>()
            var importedCount = 0
            items.forEach { item ->
                report(DictionaryUpdateProgress(DictionaryUpdateStage.Importing, item.displayName))
                onProgress(item)
                try {
                    importedCount += dictionaryRepository.importDictionary(
                        contentResolver = contentResolver,
                        uri = requireNotNull(item.uri),
                        lowRamImport = lowRamImport,
                    )
                    imported += item
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    failed += item
                }
            }
            if (importedCount > 0) {
                markDictionariesChanged()
            }
            DictionaryImportBatchResult(imported = imported, failed = failed)
        } ?: DictionaryImportBatchResult(imported = emptyList(), failed = emptyList())

    override suspend fun updatableDictionaries(): List<DictionaryUpdateCandidate> =
        dictionaryRepository.updatableDictionaries()

    override suspend fun importRecommendedDictionaries(
        dictionaries: List<RecommendedDictionary>,
        onProgress: (DictionaryUpdateProgress) -> Unit,
    ) {
        mutationCoordinator.runExclusive(DictionaryMutationOperation.RecommendedImport) {
            val importedCount = dictionaryRepository.importRecommendedDictionaries(
                dictionaries = dictionaries,
                onProgress = { progress ->
                    report(progress)
                    onProgress(progress)
                },
                lowRamImport = settingsRepository.settings.first().lowRamDictionaryImport,
            )
            if (importedCount > 0) {
                markDictionariesChanged()
            }
        }
    }

    override suspend fun updateDictionaries(
        onProgress: (DictionaryUpdateProgress) -> Unit,
    ): DictionaryUpdateSummary =
        dictionaryUpdateService.updateDictionaries(onProgress)

    override suspend fun setDictionaryEnabled(type: DictionaryType, fileName: String, enabled: Boolean): Boolean =
        mutationCoordinator.runExclusive(DictionaryMutationOperation.Edit) {
            dictionaryRepository.setDictionaryEnabled(type, fileName, enabled)
            markDictionariesChanged()
            true
        } ?: false

    override suspend fun deleteDictionary(type: DictionaryType, fileName: String, title: String): Boolean =
        mutationCoordinator.runExclusive(DictionaryMutationOperation.Edit) {
            dictionaryRepository.deleteDictionary(type, fileName)
            settingsRepository.update { current ->
                current.copy(collapsedDictionaries = current.collapsedDictionaries - title)
            }
            markDictionariesChanged()
            true
        } ?: false

    override suspend fun moveDictionary(type: DictionaryType, fromIndex: Int, toIndex: Int): Boolean =
        mutationCoordinator.runExclusive(DictionaryMutationOperation.Edit) {
            dictionaryRepository.moveDictionary(type, fromIndex, toIndex)
            markDictionariesChanged()
            true
        } ?: false

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

@HiltViewModel
internal class DictionaryViewModel : ViewModel {
    private val repository: DictionaryViewModelRepository
    private val ioDispatcher: CoroutineDispatcher
    private val injectedScope: CoroutineScope?
    private var lastCompletedChangeVersion = 0L
    private val scope: CoroutineScope
        get() = injectedScope ?: viewModelScope

    @Inject
    constructor(
        repository: DictionaryViewModelRepository,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        repository = repository,
        coroutineScope = null,
        ioDispatcher = ioDispatcher,
        marker = Unit,
    )

    internal constructor(
        repository: DictionaryViewModelRepository,
        coroutineScope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher,
    ) : this(
        repository = repository,
        coroutineScope = coroutineScope,
        ioDispatcher = ioDispatcher,
        marker = Unit,
    )

    private constructor(
        repository: DictionaryViewModelRepository,
        coroutineScope: CoroutineScope?,
        ioDispatcher: CoroutineDispatcher,
        @Suppress("UNUSED_PARAMETER") marker: Unit,
    ) : super() {
        this.repository = repository
        this.ioDispatcher = ioDispatcher
        injectedScope = coroutineScope
        lastCompletedChangeVersion = repository.mutationState.value.completedChangeVersion
        collectSettings()
        collectMutationState()
    }

    private val _uiState = MutableStateFlow(DictionaryUiState())

    val uiState: StateFlow<DictionaryUiState> = _uiState.asStateFlow()

    private fun collectSettings() {
        scope.launch {
            repository.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
    }

    private fun collectMutationState() {
        scope.launch {
            repository.mutationState.collect { mutationState ->
                val operation = mutationState.operation
                _uiState.update {
                    it.copy(
                        mutationOperation = operation,
                        isImporting = operation == DictionaryMutationOperation.Import ||
                            operation == DictionaryMutationOperation.RecommendedImport,
                        isUpdating = operation == DictionaryMutationOperation.ManualUpdate ||
                            operation == DictionaryMutationOperation.AutoUpdate,
                        showBlockingProgress = operation == DictionaryMutationOperation.Import ||
                            operation == DictionaryMutationOperation.RecommendedImport ||
                            operation == DictionaryMutationOperation.ManualUpdate ||
                            operation == DictionaryMutationOperation.AutoUpdate,
                        currentImportMessage = mutationState.progress?.message(),
                    )
                }
                if (mutationState.completedChangeVersion != lastCompletedChangeVersion) {
                    lastCompletedChangeVersion = mutationState.completedChangeVersion
                    reloadDictionaries(clearError = false)
                }
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
                        errorMessage = error.localizedMessage?.let(UiText::Literal)
                            ?: UiText.Resource(R.string.dictionary_download_failed),
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
                reloadDictionaries(clearError = true)
                if (summary.failures.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            errorMessage = UiText.Resource(
                                R.string.dictionary_update_failed_list_format,
                                summary.failures.joinToString(separator = "\n") { failure ->
                                    "${failure.title}: ${failure.message}"
                                },
                            ),
                        )
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        errorMessage = error.localizedMessage?.let(UiText::Literal)
                            ?: UiText.Resource(R.string.dictionary_update_failed),
                    )
                }
            }
            _uiState.update { it.copy(isUpdating = false, currentImportMessage = null) }
        }
    }

    internal fun importDictionaries(
        importItems: List<DictionaryImportItem>,
        importOperation: suspend ((DictionaryImportItem) -> Unit) -> DictionaryImportBatchResult,
    ) {
        if (importItems.isEmpty()) return
        scope.launch {
            _uiState.update { it.copy(isImporting = true, currentImportMessage = null, errorMessage = null) }
            runCatching {
                withContext(ioDispatcher) {
                    importOperation { item ->
                        _uiState.update { state ->
                            state.copy(
                                currentImportMessage = item.importProgressMessage(),
                            )
                        }
                    }
                }
            }.onSuccess { result ->
                reloadDictionaries(clearError = true)
                if (result.failed.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            errorMessage = UiText.Resource(
                                R.string.dictionary_import_failed_list_format,
                                result.failed.joinToString(separator = "\n") { item -> item.displayName },
                            ),
                        )
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        errorMessage = error.localizedMessage?.let(UiText::Literal)
                            ?: UiText.Resource(R.string.dictionary_import_failed),
                    )
                }
            }
            _uiState.update { it.copy(isImporting = false, currentImportMessage = null) }
        }
    }

    fun setDictionaryEnabled(dictionary: DictionaryInfo, enabled: Boolean) {
        val type = _uiState.value.selectedType
        scope.launch {
            val changed = withContext(ioDispatcher) {
                repository.setDictionaryEnabled(type, dictionary.path.name, enabled)
            }
            if (changed) {
                reloadDictionaries(clearError = false)
            }
        }
    }

    fun deleteDictionary(dictionary: DictionaryInfo) {
        val type = _uiState.value.selectedType
        scope.launch {
            val changed = withContext(ioDispatcher) {
                repository.deleteDictionary(type, dictionary.path.name, dictionary.index.title)
            }
            if (changed) {
                reloadDictionaries(clearError = false)
            }
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

    fun consumeErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
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

}

private fun DictionaryUpdateProgress.message(): UiText =
    when (stage) {
        DictionaryUpdateStage.Fetching -> UiText.Resource(R.string.dictionary_fetching_named_format, title)
        DictionaryUpdateStage.Checking -> UiText.Resource(R.string.dictionary_checking_named_format, title)
        DictionaryUpdateStage.Downloading -> UiText.Resource(R.string.dictionary_downloading_named_format, title)
        DictionaryUpdateStage.Importing -> UiText.Resource(R.string.dictionary_importing_named_format, title)
    }

private fun DictionaryImportItem.importProgressMessage(): UiText =
    displayName.takeIf { it.isNotBlank() }
        ?.let { UiText.Resource(R.string.dictionary_importing_named_format, it) }
        ?: UiText.Resource(R.string.dictionary_importing_default)
