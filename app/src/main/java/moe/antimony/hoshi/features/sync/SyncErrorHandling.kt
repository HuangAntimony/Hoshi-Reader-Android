package moe.antimony.hoshi.features.sync

import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.withErrorDetails

fun Throwable.syncMessage(operation: UiText? = null): UiText {
    if (this is CancellationException) throw this
    // Aggregate file failures already contain each original diagnostic and have been recorded.
    // Precondition exceptions contain localized user guidance and are not runtime errors.
    if (this is GoogleDriveSyncException) {
        return if (operation == null) text else UiText.Joined(listOf(operation, text))
    }
    return (operation ?: syncSummary()).withErrorDetails(this)
}

private fun Throwable.syncSummary(): UiText = when (this) {
    is GoogleDriveUnavailableException -> cause.syncSummary()
    is SyncFormatError -> UiText.Resource(R.string.sync_unsupported_format)
    is DriveAuthorizationRequiredException -> UiText.Resource(R.string.sync_connect_google_drive)
    else -> UiText.Resource(R.string.bookshelf_sync_failed)
}

internal suspend fun syncOperationError(action: suspend () -> Unit): UiText? = try {
    action()
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    error.syncMessage()
}
