package moe.antimony.hoshi.features.update

import java.io.File

internal sealed interface UpdateDownloadStatus {
    data object None : UpdateDownloadStatus
    data class Downloading(val downloadId: Long) : UpdateDownloadStatus
    data class Downloaded(val file: File) : UpdateDownloadStatus
}

internal interface UpdateDownloadController {
    suspend fun statusFor(update: AvailableUpdate): UpdateDownloadStatus
    suspend fun enqueue(update: AvailableUpdate): Long
}

internal sealed interface UpdateCheckOutcome {
    data object UpToDate : UpdateCheckOutcome
    data object NoInstallableAsset : UpdateCheckOutcome
    data class Available(val update: AvailableUpdate) : UpdateCheckOutcome
    data class DownloadStarted(val update: AvailableUpdate, val downloadId: Long) : UpdateCheckOutcome
    data class DownloadInProgress(val update: AvailableUpdate, val downloadId: Long) : UpdateCheckOutcome
    data class DownloadAlreadyFinished(val update: AvailableUpdate, val file: File) : UpdateCheckOutcome
}

internal class UpdateCheckService(
    private val currentVersionName: String,
    private val releaseRepository: ReleaseUpdateRepository,
    private val downloadController: UpdateDownloadController,
) {
    suspend fun check(downloadIfAvailable: Boolean): UpdateCheckOutcome {
        val release = releaseRepository.latestRelease()
        val releaseVersion = AppVersion.parse(release.tagName) ?: return UpdateCheckOutcome.NoInstallableAsset
        val currentVersion = AppVersion.parse(currentVersionName) ?: return UpdateCheckOutcome.UpToDate
        if (releaseVersion <= currentVersion) return UpdateCheckOutcome.UpToDate
        val update = release.availableUpdateOrNull(currentVersionName) ?: return UpdateCheckOutcome.NoInstallableAsset
        return when (val status = downloadController.statusFor(update)) {
            is UpdateDownloadStatus.Downloaded -> UpdateCheckOutcome.DownloadAlreadyFinished(update, status.file)
            is UpdateDownloadStatus.Downloading -> UpdateCheckOutcome.DownloadInProgress(update, status.downloadId)
            UpdateDownloadStatus.None -> {
                if (!downloadIfAvailable) {
                    UpdateCheckOutcome.Available(update)
                } else {
                    UpdateCheckOutcome.DownloadStarted(update, downloadController.enqueue(update))
                }
            }
        }
    }
}
