package moe.antimony.hoshi.features.sync

import kotlinx.serialization.Serializable

enum class SyncDirection {
    ImportFromTtu,
    ExportToTtu,
    Synced,
}

enum class SyncMode(val rawValue: String) {
    Auto("Auto"),
    Manual("Manual");

    companion object {
        fun fromRawValue(rawValue: String?): SyncMode =
            entries.firstOrNull { it.rawValue == rawValue } ?: Auto
    }
}

enum class SyncAuthProvider {
    DeviceCode,
}

enum class StatisticsSyncMode(val rawValue: String) {
    Merge("Merge"),
    Replace("Replace");

    companion object {
        fun fromRawValue(rawValue: String?): StatisticsSyncMode =
            entries.firstOrNull { it.rawValue == rawValue } ?: Merge
    }
}

data class SyncSettings(
    val enabled: Boolean = false,
    val mode: SyncMode = SyncMode.Auto,
    val autoSyncEnabled: Boolean = false,
    val authProvider: SyncAuthProvider = SyncAuthProvider.DeviceCode,
    val uploadBooks: Boolean = true,
)

sealed interface DriveAuthStatus {
    data object Connected : DriveAuthStatus
    data object NotConnected : DriveAuthStatus
    data object MissingConfiguration : DriveAuthStatus
    data class Failed(val message: String) : DriveAuthStatus
}

sealed interface DriveAuthorizationResult {
    data class Authorized(val accessToken: String) : DriveAuthorizationResult
    data object Pending : DriveAuthorizationResult
    data object SlowDown : DriveAuthorizationResult
    data object TransientNetworkFailure : DriveAuthorizationResult
    data class Failed(val message: String) : DriveAuthorizationResult
}

open class DriveAuthException(message: String) : Exception(message)

class DriveAuthorizationRequiredException : DriveAuthException("Connect Google Drive before syncing.")

sealed interface SyncResult {
    data class Synced(val title: String) : SyncResult
    data class Imported(val title: String, val characterCount: Int) : SyncResult
    data class Exported(val title: String, val characterCount: Int) : SyncResult
    data object Skipped : SyncResult
}

@Serializable
data class TtuProgress(
    val dataId: Int,
    val exploredCharCount: Int,
    val progress: Double,
    val lastBookmarkModified: Long,
)

@Serializable
data class TtuAudioBook(
    val title: String,
    val playbackPosition: Double,
    val lastAudioBookModified: Long,
)

data class DriveFile(
    val id: String,
    val name: String,
    val parents: List<String> = emptyList(),
    val thumbnailLink: String? = null,
)

data class DriveSyncFiles(
    val bookData: DriveFile? = null,
    val cover: DriveFile? = null,
    val progress: DriveFile?,
    val statistics: DriveFile?,
    val audioBook: DriveFile?,
)

data class ResolvedBookPosition(
    val spineIndex: Int,
    val progress: Double,
)

class GoogleDriveApiException(
    message: String,
    val statusCode: Int? = null,
) : Exception(message) {
    val isStaleCacheError: Boolean get() = statusCode == 404

    companion object {
        const val NoInternetConnectionMessage = "No internet connection."
    }
}

internal fun shouldAttemptDriveRequest(
    hasActiveNetwork: Boolean,
    hasInternetCapability: Boolean,
    @Suppress("UNUSED_PARAMETER") hasValidatedCapability: Boolean,
): Boolean =
    hasActiveNetwork && hasInternetCapability
