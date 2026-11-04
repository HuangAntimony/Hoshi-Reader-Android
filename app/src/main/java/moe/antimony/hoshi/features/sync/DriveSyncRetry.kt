package moe.antimony.hoshi.features.sync

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes

/** One outage spans local saves, foreground polling and network-change callbacks. */
internal class DriveSyncRetry(private val nowMillis: () -> Long) {
    private var delayMillis = 0L
    private var retryAt = 0L
    private var armed = false
    private var reported = false
    private var recoveryReported = false

    fun remainingMillis(): Long = (retryAt - nowMillis()).coerceAtLeast(0)

    fun startAttempt(respectBackoff: Boolean): Boolean {
        if (respectBackoff && remainingMillis() > 0) return false
        armed = true
        return true
    }

    fun recordFailure(error: Throwable) {
        if (!armed || !error.isSyncRetryableFailure()) return
        armed = false
        delayMillis = if (delayMillis == 0L) 30_000L else (delayMillis * 2).coerceAtMost(3_600_000L)
        retryAt = nowMillis() + delayMillis
    }

    fun shouldRecord(recovery: Boolean = false): Boolean = if (recovery) {
        (!recoveryReported).also { recoveryReported = true }
    } else (!reported).also { reported = true }

    fun allowNow() { retryAt = 0 }

    fun reset() {
        delayMillis = 0
        retryAt = 0
        armed = false
        reported = false
        recoveryReported = false
    }
}

internal fun Throwable.isSyncNetworkFailure(): Boolean = when (this) {
    is DrivePayloadNetworkIOException -> true
    is GoogleDriveMetadataNetworkException -> true
    is GoogleDriveSyncException -> cause?.isSyncNetworkFailure() == true || suppressed.any { it.isSyncNetworkFailure() }
    is GoogleDriveUnavailableException -> cause.isSyncNetworkFailure()
    is NativeDriveAuthorizationException -> cause?.isSyncNetworkFailure() == true
    is ApiException -> statusCode == CommonStatusCodes.NETWORK_ERROR || statusCode == CommonStatusCodes.TIMEOUT
    else -> isTransientDriveNetworkFailure()
}

internal fun Throwable.isSyncAuthorizationRecovery(): Boolean = when (this) {
    is NativeDriveAuthorizationRecoveryException -> true
    is GoogleDriveUnavailableException -> cause.isSyncAuthorizationRecovery()
    is GoogleDriveSyncException -> cause?.isSyncAuthorizationRecovery() == true || suppressed.any { it.isSyncAuthorizationRecovery() }
    else -> false
}

internal fun Throwable.isSyncRetryableFailure(): Boolean = isSyncNetworkFailure() || isSyncAuthorizationRecovery()
