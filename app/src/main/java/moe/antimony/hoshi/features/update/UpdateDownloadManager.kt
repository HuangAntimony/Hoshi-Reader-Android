package moe.antimony.hoshi.features.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

internal class AndroidUpdateDownloadManager(
    context: Context,
    private val store: UpdateDownloadStore,
) : UpdateDownloadController {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)

    override suspend fun statusFor(update: AvailableUpdate): UpdateDownloadStatus {
        val record = store.load()
        if (record?.matches(update) != true) return UpdateDownloadStatus.None
        val file = updateFile(record.fileName)
        if (record.status == UpdateDownloadRecordStatus.Downloaded && file.isFile) {
            return UpdateDownloadStatus.Downloaded(file)
        }
        val downloadStatus = downloadManager.queryStatus(record.downloadId)
        return when (downloadStatus) {
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_PAUSED,
            DownloadManager.STATUS_RUNNING,
            -> UpdateDownloadStatus.Downloading(record.downloadId)
            DownloadManager.STATUS_SUCCESSFUL -> {
                val valid = file.isFile &&
                    (record.sha256 == null || file.sha256Hex().equals(record.sha256, ignoreCase = true))
                if (valid) {
                    store.markDownloaded(record.downloadId)
                    UpdateDownloadStatus.Downloaded(file)
                } else {
                    file.delete()
                    store.markFailed(record.downloadId)
                    UpdateDownloadStatus.None
                }
            }
            DownloadManager.STATUS_FAILED -> {
                store.markFailed(record.downloadId)
                UpdateDownloadStatus.None
            }
            else -> UpdateDownloadStatus.None
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
            .setDescription("Downloading update")
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

    private fun deleteExistingUpdateApksExcept(target: File) {
        val directory = target.parentFile ?: return
        directory.listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
            ?.filterNot { file -> file == target }
            ?.forEach { file -> file.delete() }
    }

    private fun updateDirectory(): File =
        appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "downloads")

    companion object {
        const val ApkMimeType = "application/vnd.android.package-archive"
        const val UpdateFileName = "Hoshi-Reader-update.apk"
    }
}

internal class UpdateDownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId < 0) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    val appContext = context.applicationContext
                    val store = appContext.updateDownloadStore()
                    val record = store.load() ?: return@launch
                    if (record.downloadId != downloadId) return@launch
                    val manager = appContext.getSystemService(DownloadManager::class.java)
                    if (manager.queryStatus(downloadId) != DownloadManager.STATUS_SUCCESSFUL) {
                        store.markFailed(downloadId)
                        return@launch
                    }
                    val file = AndroidUpdateDownloadManager(appContext, store).updateFile(record.fileName)
                    val valid = record.sha256 == null || file.sha256Hex().equals(record.sha256, ignoreCase = true)
                    if (valid) {
                        store.markDownloaded(downloadId)
                    } else {
                        file.delete()
                        store.markFailed(downloadId)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

private fun DownloadManager.queryStatus(downloadId: Long): Int? {
    val cursor = query(DownloadManager.Query().setFilterById(downloadId)) ?: return null
    cursor.use {
        if (!it.moveToFirst()) return null
        return it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
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
