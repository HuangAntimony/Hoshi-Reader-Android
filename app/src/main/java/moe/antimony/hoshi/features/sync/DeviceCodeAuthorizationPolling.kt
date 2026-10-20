package moe.antimony.hoshi.features.sync

import java.io.IOException
import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.diagnosticMessage

internal fun Throwable.isTransientDeviceCodePollingFailure(): Boolean = this is IOException

internal fun Throwable.toDeviceCodePollingFailureResult(): DriveAuthorizationResult =
    if (this is CancellationException) {
        throw this
    } else if (isTransientDeviceCodePollingFailure()) {
        DriveAuthorizationResult.TransientNetworkFailure
    } else {
        val details = diagnosticMessage()
        ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.sync_google_drive_authorization_failed), this)
        DriveAuthorizationResult.Failed(details)
    }

internal fun nextDeviceCodePollIntervalSeconds(
    currentIntervalSeconds: Long,
    result: DriveAuthorizationResult,
): Long =
    when (result) {
        DriveAuthorizationResult.SlowDown -> currentIntervalSeconds + DeviceCodeDriveAuthorizer.SlowDownIncrementSeconds
        DriveAuthorizationResult.TransientNetworkFailure ->
            (currentIntervalSeconds * DeviceCodeDriveAuthorizer.TransientNetworkBackoffMultiplier)
                .coerceAtMost(DeviceCodeDriveAuthorizer.MaxTransientNetworkBackoffSeconds)
        else -> currentIntervalSeconds
    }
