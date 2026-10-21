package moe.antimony.hoshi.features.sync

import java.io.File
import kotlinx.serialization.Serializable

@Serializable
data class DriveUploadRequest(
    val root: String,
    val book: String,
    val generation: Int,
    val type: SyncFileType,
    val source: Long,
    val parent: String,
    val name: String,
)

enum class SyncTransferPhase { Preparing, Transferring, AwaitingResponse, Retrying }

data class DriveTransferUpdate(
    val bytes: Long,
    val totalBytes: Long?,
    val confirmedBytes: Long = 0,
    val phase: SyncTransferPhase = SyncTransferPhase.Transferring,
    val deltaBytes: Long = 0,
)

interface DriveFileTransfer {
    suspend fun upload(file: File, request: DriveUploadRequest, progress: (DriveTransferUpdate) -> Unit): GoogleDriveFile
    suspend fun download(url: String, destination: File, size: Long?, progress: (DriveTransferUpdate) -> Unit)
    suspend fun acknowledge(request: DriveUploadRequest)
    suspend fun discardObsolete(keep: (DriveUploadRequest) -> Boolean)
    suspend fun reset()
}
