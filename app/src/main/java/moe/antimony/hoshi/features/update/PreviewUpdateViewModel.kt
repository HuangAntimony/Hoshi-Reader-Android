package moe.antimony.hoshi.features.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.withErrorDetails

internal data class PreviewUpdateState(
    val loaded: Boolean = false,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val preparingInstall: Boolean = false,
    val pendingBuild: PreviewBuild? = null,
    val download: UpdateDownloadRecord? = null,
    val message: UiText? = null,
    val error: UiText? = null,
    val installRequest: File? = null,
)

@HiltViewModel
internal class PreviewUpdateViewModel internal constructor(
    private val operations: PreviewUpdateOperations,
    private val currentCommitSha: String,
) : ViewModel() {
    @Inject constructor(repository: PreviewUpdateRepository) : this(repository, BuildConfig.BUILD_COMMIT_SHA)

    private val mutableState = MutableStateFlow(PreviewUpdateState())
    val state = mutableState.asStateFlow()
    private var foreground = false
    private var foregroundGeneration = 0L
    private var autoInstallSha: String? = null
    private var observer: Job? = null

    init { observeDownloads() }

    private fun observeDownloads() {
        observer?.cancel()
        mutableState.update { it.copy(loading = true) }
        observer = viewModelScope.launch {
            try {
                operations.records.collect { record ->
                    val visible = record?.takeUnless { shouldDeletePreviewApk(it, currentCommitSha) }
                    mutableState.update { it.copy(loaded = true, loading = false, download = visible) }
                    if (visible?.status == UpdateDownloadRecordStatus.Failed) {
                        autoInstallSha = null
                        mutableState.update { it.copy(error = UiText.Joined(listOf(
                            UiText.Resource(R.string.preview_download_failed),
                            UiText.Literal(visible.failureDetails.orEmpty()),
                        ))) }
                    }
                    if (visible != null && foreground && autoInstallSha != null && autoInstallSha == visible.previewCommitSha &&
                        visible.status == UpdateDownloadRecordStatus.Downloaded) {
                        autoInstallSha = null
                        requestInstall(visible)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(loading = false, loaded = false) }
                fail(R.string.preview_download_failed, error)
            }
        }
    }

    fun setForeground(active: Boolean) {
        foreground = active
        if (!active) {
            foregroundGeneration++
            autoInstallSha = null
            mutableState.update { it.copy(installRequest = null) }
        } else {
            viewModelScope.launch {
                try { operations.refresh() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { fail(R.string.preview_download_failed, error) }
            }
        }
    }

    fun check() {
        if (state.value.preparingInstall || state.value.installRequest != null || state.value.busy || state.value.loading || state.value.download?.status == UpdateDownloadRecordStatus.Downloading) return
        if (!state.value.loaded) { observeDownloads(); return }
        mutableState.update { it.copy(busy = true, message = null, error = null) }
        viewModelScope.launch {
            try {
                when (val result = operations.latest()) {
                    PreviewLookup.None -> mutableState.update { it.copy(message = UiText.Resource(R.string.preview_unavailable)) }
                    PreviewLookup.NoCompatibleAsset -> mutableState.update { it.copy(message = UiText.Resource(R.string.about_update_no_matching_apk)) }
                    is PreviewLookup.Found -> if (result.build.commitSha == currentCommitSha) {
                        mutableState.update { it.copy(message = UiText.Resource(R.string.preview_up_to_date)) }
                    } else {
                        mutableState.update { it.copy(pendingBuild = result.build) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(R.string.preview_check_failed, error)
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun cancelConfirmation() { mutableState.update { it.copy(pendingBuild = null) } }

    fun confirmDownload() {
        val build = state.value.pendingBuild ?: return
        if (state.value.preparingInstall || state.value.installRequest != null || state.value.busy) return
        mutableState.update { it.copy(pendingBuild = null, busy = true, message = null, error = null) }
        autoInstallSha = build.commitSha.takeIf { foreground }
        viewModelScope.launch {
            try {
                operations.download(build)
                // Already downloaded files need no new store emission.
                state.value.download?.takeIf {
                    it.previewCommitSha == build.commitSha && it.status == UpdateDownloadRecordStatus.Downloaded
                }?.let {
                    if (foreground && autoInstallSha == build.commitSha) {
                        autoInstallSha = null
                        requestInstall(it)
                    }
                }
            } catch (cancelled: CancellationException) {
                autoInstallSha = null
                throw cancelled
            } catch (error: Exception) {
                autoInstallSha = null
                fail(R.string.preview_download_failed, error)
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun installDownloaded() {
        val record = state.value.download?.takeIf { it.status == UpdateDownloadRecordStatus.Downloaded } ?: return
        if (state.value.busy || state.value.installRequest != null) return
        requestInstall(record)
    }

    private fun requestInstall(record: UpdateDownloadRecord) {
        if (state.value.preparingInstall) return
        mutableState.update { it.copy(preparingInstall = true) }
        val generation = foregroundGeneration
        viewModelScope.launch {
            try {
                val file = operations.readyFile(record)
                if (foreground && generation == foregroundGeneration && state.value.download == record) mutableState.update { it.copy(installRequest = file) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(R.string.preview_download_failed, error)
            } finally {
                mutableState.update { it.copy(preparingInstall = false) }
            }
        }
    }

    fun takeInstallRequest(): File? {
        val file = state.value.installRequest.takeIf { foreground }
        mutableState.update { it.copy(installRequest = null) }
        return file
    }

    fun reportInstallMessage(message: String) { mutableState.update { it.copy(error = UiText.Literal(message)) } }
    fun dismissError() { mutableState.update { it.copy(error = null) } }

    private fun fail(resource: Int, error: Throwable) {
        mutableState.update { it.copy(error = UiText.Resource(resource).withErrorDetails(error)) }
    }
}
