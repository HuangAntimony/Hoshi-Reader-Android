package moe.antimony.hoshi.features.diagnostics

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.di.CacheDir
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.withErrorDetails

internal class DiagnosticsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nonfatalLogger: NonfatalDiagnosticsLogger,
    @CacheDir private val cacheDir: File,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun load(): ProcessExitDiagnosticsReport = withContext(ioDispatcher) {
        loadDiagnosticsReport(nonfatalLogger, context.getString(R.string.diagnostics_load_failed)) {
            loadProcessExitDiagnosticsReport(context)
        }
    }

    suspend fun shareFile(text: String): File = withContext(ioDispatcher) {
        createDiagnosticsShareFile(cacheDir, text, System.currentTimeMillis())
    }

    suspend fun save(uri: Uri, text: String) = withContext(ioDispatcher) {
        saveDiagnosticsReport(text) { context.contentResolver.openOutputStream(uri) }
    }
}

internal data class DiagnosticsUiState(
    val report: ProcessExitDiagnosticsReport? = null,
    val loadError: UiText? = null,
)

@HiltViewModel
internal class DiagnosticsViewModel internal constructor(
    private val loadReport: suspend () -> ProcessExitDiagnosticsReport,
    private val shareReport: suspend (String) -> File,
    private val saveReport: suspend (Uri, String) -> Unit,
) : ViewModel() {
    @Inject constructor(repository: DiagnosticsRepository) : this(
        repository::load,
        repository::shareFile,
        repository::save,
    )
    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state = _state.asStateFlow()
    private var loadGeneration = 0L
    private var loadJob: Job? = null

    fun reload() {
        val generation = ++loadGeneration
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loadError = null) }
            try {
                val report = loadReport()
                if (generation == loadGeneration) _state.update { it.copy(report = report) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == loadGeneration) {
                    _state.update { it.copy(loadError = UiText.Resource(R.string.diagnostics_load_failed).withErrorDetails(error)) }
                }
            }
        }
    }

    suspend fun shareFile(text: String): File = shareReport(text)
    suspend fun save(uri: Uri, text: String) = saveReport(uri, text)
}
