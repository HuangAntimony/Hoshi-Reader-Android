package moe.antimony.hoshi.features.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.BuildConfig

internal data class UpdateApkArchiveInfo(
    val packageName: String?,
    val versionName: String?,
)

internal fun shouldDeleteUpdateApk(
    archiveInfo: UpdateApkArchiveInfo?,
    currentPackageName: String,
    currentVersionName: String,
): Boolean =
    archiveInfo?.packageName == currentPackageName &&
        archiveInfo.versionName == currentVersionName

internal class UpdateApkCleanup(
    private val context: Context,
    private val downloadManager: AndroidUpdateDownloadManager,
    private val store: UpdateDownloadStore,
) {
    suspend fun deleteCurrentVersionApks() = withContext(Dispatchers.IO) {
        val deletedFileNames = downloadManager.updateApkFiles()
            .filter { file ->
                shouldDeleteUpdateApk(
                    archiveInfo = context.packageManager.archiveInfo(file.absolutePath),
                    currentPackageName = context.packageName,
                    currentVersionName = BuildConfig.VERSION_NAME,
                )
            }
            .filter { file -> file.delete() }
            .map { file -> file.name }
            .toSet()
        val record = store.load()
        if (record?.fileName?.let { it in deletedFileNames } == true) {
            store.clear()
        }
    }
}

private fun PackageManager.archiveInfo(path: String): UpdateApkArchiveInfo? {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getPackageArchiveInfo(path, 0)
    } ?: return null
    return UpdateApkArchiveInfo(
        packageName = packageInfo.packageName,
        versionName = packageInfo.versionName,
    )
}
