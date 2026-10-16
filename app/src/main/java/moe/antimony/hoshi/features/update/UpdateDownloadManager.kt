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
import kotlinx.coroutines.launch
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
internal class AndroidUpdateDownloadManager @Inject constructor(
    @ApplicationContext context: Context,
    private val store: UpdateDownloadStore,
) : UpdateDownloadController {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)

    override suspend fun statusFor(update: AvailableUpdate): UpdateDownloadStatus {
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

    override suspend fun enqueue(update: AvailableUpdate): Long {
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
            .setTitle("Hoshi Reader ${update.versionName}")
            .setDescription(appContext.getString(R.string.update_downloading_notification))
            .setMimeType(ApkMimeType)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))
        val downloadId = downloadManager.enqueue(request)
        store.saveDownloading(update, target.name, downloadId, downloadUrl)
        return downloadId
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
        appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "downloads")

    companion object {
        const val ApkMimeType = "application/vnd.android.package-archive"
        const val UpdateFileName = "Hoshi-Reader-update.apk"
    }
}

@AndroidEntryPoint
internal class UpdateDownloadCompleteReceiver : BroadcastReceiver() {
    @Inject lateinit var store: UpdateDownloadStore
    @Inject lateinit var updateDownloadManager: AndroidUpdateDownloadManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId < 0) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    val record = store.load() ?: return@launch
                    if (record.downloadId != downloadId) return@launch
                    val manager = context.applicationContext.getSystemService(DownloadManager::class.java)
                    val downloadState = manager.queryDownloadState(downloadId)
                    if (downloadState?.status != DownloadManager.STATUS_SUCCESSFUL) {
                        store.recordDownloadFailure(downloadId, downloadState.failureDetails(downloadId))
                        return@launch
                    }
                    val file = updateDownloadManager.updateFile(record.fileName)
                    try {
                        validateDownloadedUpdate(file, record.sha256)
                        store.markDownloaded(downloadId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        file.delete()
                        throw error
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    store.recordDownloadFailure(downloadId, error)
                }
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
