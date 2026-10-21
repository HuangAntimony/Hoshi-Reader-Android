package moe.antimony.hoshi.features.update

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.diagnosticMessage

@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class PreviewDownloads

@Module
@InstallIn(SingletonComponent::class)
internal object PreviewUpdateModule {
    @Provides @Singleton @PreviewDownloads
    fun store(@ApplicationContext context: Context): UpdateDownloadStore = context.previewDownloadStore()

    @Provides @Singleton @PreviewDownloads
    fun manager(@ApplicationContext context: Context, @PreviewDownloads store: UpdateDownloadStore): AndroidUpdateDownloadManager =
        AndroidUpdateDownloadManager(context, store, "previews")
}

internal interface PreviewUpdateOperations {
    val records: Flow<UpdateDownloadRecord?>
    suspend fun latest(): PreviewLookup
    suspend fun refresh()
    suspend fun download(build: PreviewBuild)
    suspend fun readyFile(record: UpdateDownloadRecord): File
}

internal fun shouldDeletePreviewApk(record: UpdateDownloadRecord, currentSha: String): Boolean =
    currentSha.matches(Regex("[0-9a-f]{40}")) && record.previewCommitSha == currentSha

@Singleton
internal class PreviewUpdateRepository @Inject constructor(
    private val github: GitHubPreviewRepository,
    @param:PreviewDownloads private val store: UpdateDownloadStore,
    @param:PreviewDownloads private val manager: AndroidUpdateDownloadManager,
) : PreviewUpdateOperations {
    override val records = store.record
    override suspend fun latest() = github.latest()
    override suspend fun refresh() = withContext(Dispatchers.IO) {
        manager.clearPreviewIfInstalled(BuildConfig.BUILD_COMMIT_SHA)
        store.load()?.toAvailableUpdate()?.let { manager.statusFor(it) }
        Unit
    }
    override suspend fun download(build: PreviewBuild) = withContext(Dispatchers.IO) {
        manager.enqueue(build.apk)
        Unit
    }
    override suspend fun readyFile(record: UpdateDownloadRecord): File = withContext(Dispatchers.IO) {
        check(record.status == UpdateDownloadRecordStatus.Downloaded) { "Preview download is not complete" }
        val file = manager.updateFile(record.fileName)
        try {
            validateDownloadedUpdate(file, record.sha256)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.preview_download_failed), error)
            record.downloadId?.let { store.markFailed(it, error.diagnosticMessage()) }
            throw error
        }
        file
    }
}
