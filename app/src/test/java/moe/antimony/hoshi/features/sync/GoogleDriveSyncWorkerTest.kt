package moe.antimony.hoshi.features.sync

import androidx.work.ListenableWorker.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GoogleDriveSyncWorkerTest {
    @Test fun offlinePreflightAndRateLimitingRemainRetryable() = runTest {
        val offline = GoogleDriveUnavailableException(
            GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage),
        )
        assertEquals(Result.retry(), backgroundSyncResult { throw offline })
        assertEquals(Result.retry(), backgroundSyncResult { throw GoogleDriveApiException("Rate limited", 429) })
    }

    @Test fun missingAuthorizationAndUnsupportedDataDoNotRetryWithoutUserAction() = runTest {
        assertEquals(Result.failure(), backgroundSyncResult {
            throw GoogleDriveUnavailableException(DriveAuthorizationRequiredException())
        })
        assertEquals(Result.failure(), backgroundSyncResult { throw SyncFormatError() })
    }

    @Test fun workerCancellationPropagatesInsteadOfSchedulingARetry() = runTest {
        val cancelled = CancellationException("App returned to foreground")
        val result = runCatching { backgroundSyncResult { throw cancelled } }
        assertSame(cancelled, result.exceptionOrNull())
    }
}
