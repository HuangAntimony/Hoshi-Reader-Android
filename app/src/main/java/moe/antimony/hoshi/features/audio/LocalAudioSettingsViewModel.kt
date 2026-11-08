package moe.antimony.hoshi.features.audio

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.SettingsLoadState
import moe.antimony.hoshi.importing.UnsupportedImportFileTypeException
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.withErrorDetails

internal data class LocalAudioSettingsState(
    val database: SettingsLoadState<LocalAudioDatabaseInfo?> = SettingsLoadState.Loading,
    val source: LocalAudioSourceUiState = LocalAudioSourceUiState(),
    val hasRetainedPrivateDatabase: Boolean = false,
    val isRefreshing: Boolean = false,
    val isMutating: Boolean = false,
    val importMode: LocalAudioImportMode? = null,
    val copyProgress: LocalAudioImportProgress? = null,
    val readError: UiText? = null,
    val operationError: UiText? = null,
) {
    val isBusy: Boolean get() = isRefreshing || isMutating
    val databaseInfo: LocalAudioDatabaseInfo?
        get() = (database as? SettingsLoadState.Loaded)?.value
}

@HiltViewModel
internal class LocalAudioSettingsViewModel internal constructor(
    private val actions: LocalAudioSettingsActions,
) : ViewModel() {
    @Inject constructor(repository: LocalAudioRepository) : this(RepositorySettingsActions(repository))

    private val mutableState = MutableStateFlow(LocalAudioSettingsState())
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (state.value.isBusy) return
        mutableState.update { it.copy(isRefreshing = true) }
        refreshJob = viewModelScope.launch {
            try {
                loadDatabase()
            } finally {
                mutableState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun importDatabase(uri: Uri, mode: LocalAudioImportMode) {
        val error = if (mode == LocalAudioImportMode.Copy) R.string.audio_import_android_db_failed else R.string.audio_link_database_failed
        mutate(error, importMode = mode) {
            var lastProgressUpdate = 0L
            val database = actions.importDatabase(uri, mode) { progress ->
                if (mode == LocalAudioImportMode.Copy &&
                    (progress.totalBytes == progress.copiedBytes ||
                        progress.copiedBytes - lastProgressUpdate >= ProgressUpdateBytes)
                ) {
                    lastProgressUpdate = progress.copiedBytes
                    mutableState.update { it.copy(copyProgress = progress) }
                }
            }
            loadDetails(database)
        }
    }

    fun deleteDatabase() {
        val database = state.value.databaseInfo ?: return
        if (state.value.isBusy) return
        val error = if (database.isExternal) R.string.audio_unlink_database_failed else R.string.audio_delete_database_failed
        mutate(error) {
            val result = runCatching { actions.deleteDatabase() }
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
            // A failed private file deletion can leave an unbound copy available for cleanup.
            loadDatabase()
            result.getOrThrow()
        }
    }

    fun deleteRetainedPrivateDatabase() {
        if (state.value.isBusy || !state.value.hasRetainedPrivateDatabase) return
        mutate(R.string.audio_delete_database_failed) {
            try {
                actions.deleteRetainedPrivateDatabase()
                mutableState.update { it.copy(hasRetainedPrivateDatabase = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                loadDatabase()
                throw error
            }
        }
    }

    fun moveSource(source: String, offset: Int) {
        val order = state.value.source.config?.sourceOrder?.toMutableList() ?: return
        val from = order.indexOf(source)
        val to = from + offset
        if (from !in order.indices || to !in order.indices) return
        order.add(to, order.removeAt(from))
        saveSources { actions.updateSourceOrder(order) }
    }

    fun setSourceEnabled(source: String, enabled: Boolean) {
        if (state.value.source.config?.sourceOrder?.contains(source) != true) return
        saveSources { actions.updateSourceEnabled(source, enabled) }
    }

    private fun saveSources(save: suspend () -> LocalAudioSourceConfig) {
        if (state.value.isBusy) return
        mutate(R.string.audio_local_source_save_failed) {
            val result = runCatching { save() }
            mutableState.update { it.copy(source = it.source.withSaveResult(result)) }
        }
    }

    private suspend fun loadDatabase() {
        val database = try {
            actions.databaseInfo()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = UiText.Resource(R.string.audio_local_database_read_failed).withErrorDetails(error)
            mutableState.update {
                if (it.database is SettingsLoadState.Loaded) {
                    it.copy(source = LocalAudioSourceUiState(), readError = message)
                } else {
                    it.copy(database = SettingsLoadState.Failed(message))
                }
            }
            return
        }
        loadDetails(database)
    }

    private suspend fun loadDetails(database: LocalAudioDatabaseInfo?) {
        mutableState.update {
            it.copy(
                database = SettingsLoadState.Loaded(database),
                source = if (it.databaseInfo == database) it.source else LocalAudioSourceUiState(),
                readError = null,
            )
        }
        try {
            val retained = actions.hasRetainedPrivateDatabase()
            mutableState.update { it.copy(hasRetainedPrivateDatabase = retained) }
            val config = database?.let { actions.ensureSourceConfig() }
            mutableState.update { it.copy(source = it.source.copy(config = config)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = UiText.Resource(R.string.audio_local_database_read_failed).withErrorDetails(error)
            mutableState.update { it.copy(source = LocalAudioSourceUiState(), readError = message) }
        }
    }

    private fun mutate(
        @StringRes errorResource: Int,
        importMode: LocalAudioImportMode? = null,
        block: suspend () -> Unit,
    ) {
        if (state.value.isMutating) return
        mutableState.update {
            it.copy(
                isMutating = true,
                operationError = null,
                importMode = importMode,
                copyProgress = if (importMode == LocalAudioImportMode.Copy) LocalAudioImportProgress(0, null) else null,
            )
        }
        viewModelScope.launch {
            try {
                // A picker result can arrive during the initial load or resume refresh.
                refreshJob?.join()
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val resource = (error as? UnsupportedImportFileTypeException)?.messageRes ?: errorResource
                val message = UiText.Resource(resource).withErrorDetails(error)
                mutableState.update { it.copy(operationError = message) }
            } finally {
                mutableState.update { it.copy(isMutating = false, importMode = null, copyProgress = null) }
            }
        }
    }
}

internal interface LocalAudioSettingsActions {
    suspend fun databaseInfo(): LocalAudioDatabaseInfo?
    suspend fun ensureSourceConfig(): LocalAudioSourceConfig
    suspend fun importDatabase(
        uri: Uri,
        mode: LocalAudioImportMode,
        onProgress: (LocalAudioImportProgress) -> Unit,
    ): LocalAudioDatabaseInfo
    suspend fun deleteDatabase()
    suspend fun updateSourceOrder(sourceOrder: List<String>): LocalAudioSourceConfig
    suspend fun updateSourceEnabled(source: String, enabled: Boolean): LocalAudioSourceConfig
    suspend fun hasRetainedPrivateDatabase(): Boolean
    suspend fun deleteRetainedPrivateDatabase()
}

private class RepositorySettingsActions(private val repository: LocalAudioRepository) : LocalAudioSettingsActions {
    override suspend fun databaseInfo() = repository.databaseInfo()
    override suspend fun ensureSourceConfig() = repository.ensureSourceConfig()
    override suspend fun importDatabase(
        uri: Uri,
        mode: LocalAudioImportMode,
        onProgress: (LocalAudioImportProgress) -> Unit,
    ) = repository.importDatabase(uri, mode, onProgress)
    override suspend fun deleteDatabase() = repository.deleteDatabase()
    override suspend fun updateSourceOrder(sourceOrder: List<String>) = repository.updateSourceOrder(sourceOrder)
    override suspend fun updateSourceEnabled(source: String, enabled: Boolean) = repository.updateSourceEnabled(source, enabled)
    override suspend fun hasRetainedPrivateDatabase() = repository.hasRetainedPrivateDatabase()
    override suspend fun deleteRetainedPrivateDatabase() = repository.deleteRetainedPrivateDatabase()
}

private const val ProgressUpdateBytes = 64L * 1024L * 1024L
