package moe.antimony.hoshi.features.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.diagnosticMessage
import java.security.MessageDigest

@Singleton
internal class AndroidUpdateDownloadManager internal constructor(
    context: Context,
    private val store: UpdateDownloadStore,
    private val subdirectory: String,
) : UpdateDownloadController {
    @Inject constructor(@ApplicationContext context: Context, store: UpdateDownloadStore) : this(context, store, "")
    private val mutex = Mutex()
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)

    override suspend fun statusFor(update: AvailableUpdate): UpdateDownloadStatus = mutex.withLock { statusUnlocked(update) }

    private suspend fun statusUnlocked(update: AvailableUpdate): UpdateDownloadStatus {
        val record = store.load()
        if (record?.matches(update) != true) return UpdateDownloadStatus.None
        val file = updateFile(record.fileName)
        if (record.status == UpdateDownloadRecordStatus.Failed) return UpdateDownloadStatus.None
        if (record.status == UpdateDownloadRecordStatus.Downloaded) {
            if (file.isFile) return UpdateDownloadStatus.Downloaded(file)
            record.downloadId?.let { store.recordDownloadFailure(it, FileNotFoundException(file.absolutePath)) }
            return UpdateDownloadStatus.None
        }
        if (record.status != UpdateDownloadRecordStatus.Downloading) {
            return UpdateDownloadStatus.None
        }
        val downloadId = record.downloadId ?: return UpdateDownloadStatus.None
        val downloadState = downloadManager.queryDownloadState(downloadId)
        return when (downloadState?.status) {
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_PAUSED,
            DownloadManager.STATUS_RUNNING,
            -> UpdateDownloadStatus.Downloading(downloadId)
            DownloadManager.STATUS_SUCCESSFUL -> {
                try {
                    validateDownloadedUpdate(file, record.sha256)
                    store.markDownloaded(downloadId)
                    UpdateDownloadStatus.Downloaded(file)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    file.delete()
                    store.recordDownloadFailure(downloadId, error)
                    UpdateDownloadStatus.None
                }
            }
            DownloadManager.STATUS_FAILED -> {
                store.recordDownloadFailure(downloadId, downloadState.failureDetails(downloadId))
                UpdateDownloadStatus.None
            }
            else -> {
                store.recordDownloadFailure(downloadId, downloadState.failureDetails(downloadId))
                UpdateDownloadStatus.None
            }
        }
    }

    override suspend fun enqueue(update: AvailableUpdate): Long = mutex.withLock {
        when (val status = statusUnlocked(update)) {
            is UpdateDownloadStatus.Downloading -> return@withLock status.downloadId
            is UpdateDownloadStatus.Downloaded -> return@withLock requireNotNull(store.load()?.downloadId)
            UpdateDownloadStatus.None -> Unit
        }
        store.load()?.takeIf { it.status == UpdateDownloadRecordStatus.Downloading }?.downloadId?.let { downloadManager.remove(it) }
        val previousFailedDownloadUrl = store.load()
            ?.takeIf { it.matches(update) && it.status == UpdateDownloadRecordStatus.Failed }
            ?.downloadUrl
        val downloadUrl = update.downloadUrlAfterFailed(previousFailedDownloadUrl)
        val target = updateFile(UpdateFileName)
        target.parentFile?.mkdirs()
        deleteExistingUpdateApksExcept(target)
        if (target.exists()) {
            target.delete()
        }
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle(update.previewCommitSha?.let {
                appContext.getString(R.string.preview_download_title, it.take(7))
            } ?: "Hoshi Reader ${update.versionName}")
            .setDescription(appContext.getString(R.string.update_downloading_notification))
            .setMimeType(ApkMimeType)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))
        persistStartedDownload(
            start = { downloadManager.enqueue(request) },
            save = { id -> store.saveDownloading(update, target.name, id, downloadUrl) },
            remove = { id -> downloadManager.remove(id) },
        )
    }

    internal fun updateFile(fileName: String): File =
        File(updateDirectory(), fileName)

    internal fun updateApkFiles(): List<File> =
        updateDirectory()
            .listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
            ?.toList()
            .orEmpty()

    private fun deleteExistingUpdateApksExcept(target: File) {
        val directory = target.parentFile ?: return
        directory.listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
            ?.filterNot { file -> file == target }
            ?.forEach { file -> file.delete() }
    }

    internal fun updateDirectory(): File =
        (appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "downloads")).let { root ->
                if (subdirectory.isEmpty()) root else File(root, subdirectory)
            }

    internal suspend fun handleCompletion(downloadId: Long) = mutex.withLock {
        try {
            val record = store.load()?.takeIf { it.downloadId == downloadId } ?: return@withLock
            record.toAvailableUpdate()?.let { statusUnlocked(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            store.recordDownloadFailure(downloadId, error)
        }
    }

    internal suspend fun clearPreviewIfInstalled(currentSha: String) = mutex.withLock {
        val record = store.load() ?: return@withLock
        if (!shouldDeletePreviewApk(record, currentSha)) return@withLock
        record.downloadId?.let { downloadManager.remove(it) }
        val file = updateFile(record.fileName)
        if (file.exists() && !file.delete()) throw IOException("Unable to remove installed preview APK: $file")
        store.clear()
    }

    companion object {
        const val ApkMimeType = "application/vnd.android.package-archive"
        const val UpdateFileName = "Hoshi-Reader-update.apk"
    }
}

@AndroidEntryPoint
internal class UpdateDownloadCompleteReceiver : BroadcastReceiver() {
    @Inject @PreviewDownloads lateinit var previewDownloadManager: AndroidUpdateDownloadManager
    @Inject lateinit var updateDownloadManager: AndroidUpdateDownloadManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId < 0) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateDownloadManager.handleCompletion(downloadId)
                previewDownloadManager.handleCompletion(downloadId)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

private suspend fun UpdateDownloadStore.recordDownloadFailure(downloadId: Long, error: Throwable) {
    val details = error.diagnosticMessage()
    ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.about_update_download_failed), error)
    markFailed(downloadId, details)
}

private suspend fun UpdateDownloadStore.recordDownloadFailure(downloadId: Long, details: String) {
    ErrorDiagnosticEvents.buffer.recordServiceFailure(UiText.Resource(R.string.about_update_download_failed), details)
    markFailed(downloadId, details)
}

private data class AndroidDownloadState(val status: Int, val reason: Int?)

private fun AndroidDownloadState?.failureDetails(downloadId: Long): String =
    "DownloadManager downloadId=$downloadId status=${this?.status} reason=${this?.reason}"

private fun DownloadManager.queryDownloadState(downloadId: Long): AndroidDownloadState? {
    val cursor = query(DownloadManager.Query().setFilterById(downloadId)) ?: return null
    cursor.use {
        if (!it.moveToFirst()) return null
        val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val reason = if (status == DownloadManager.STATUS_FAILED) {
            it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
        } else null
        return AndroidDownloadState(status, reason)
    }
}

internal suspend fun validateDownloadedUpdate(file: File, expectedSha256: String?) {
    if (!file.isFile) throw FileNotFoundException(file.absolutePath)
    if (expectedSha256 != null) {
        val actualSha256 = file.sha256Hex()
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            throw IOException("SHA-256 mismatch: expected $expectedSha256, actual $actualSha256")
        }
    }
}

private suspend fun File.sha256Hex(): String = withContext(Dispatchers.IO) {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/** Once Android owns the request, persist its identity even if the initiating screen closes. */
internal suspend fun persistStartedDownload(
    start: () -> Long,
    save: suspend (Long) -> Unit,
    remove: (Long) -> Unit,
): Long = withContext(NonCancellable) {
    val id = start()
    try {
        save(id)
    } catch (error: Exception) {
        try { remove(id) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
        throw error
    }
    id
}
