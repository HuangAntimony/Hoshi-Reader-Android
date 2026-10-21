package moe.antimony.hoshi.features.sync

import java.io.File

/** DriveService fixture adapter: production protocol is exercised by MockWebServer tests. */
internal open class TestDriveFileTransfer(private val client: GoogleDriveClient) : DriveFileTransfer {
    override suspend fun upload(file: File, request: DriveUploadRequest, progress: (DriveTransferUpdate) -> Unit): GoogleDriveFile {
        progress(DriveTransferUpdate(file.length(), file.length(), deltaBytes = file.length()))
        return client.write(file, request.name, request.parent)
    }
    override suspend fun download(url: String, destination: File, size: Long?, progress: (DriveTransferUpdate) -> Unit) {
        var last = 0L
        client.performDownload(url, destination) { received, total ->
            progress(DriveTransferUpdate(received, total ?: size, received, deltaBytes = received - last))
            last = received
        }
    }
    override suspend fun acknowledge(request: DriveUploadRequest) = Unit
    override suspend fun discardObsolete(keep: (DriveUploadRequest) -> Boolean) = Unit
    override suspend fun reset() = Unit
}
