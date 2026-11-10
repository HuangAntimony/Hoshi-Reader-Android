package moe.antimony.hoshi.features.update

import androidx.work.ListenableWorker.Result
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckWorkerTest {
    @Test
    fun failedBackgroundCheckEndsAndRetainsOriginalDiagnostic() = runBlocking {
        ErrorDiagnosticEvents.buffer.drain()
        val error = GitHubReleaseException("Invalid release response", IllegalArgumentException("Invalid JSON"))

        val result = updateCheckWorkerResult(0) { throw error }

        assertEquals(Result.failure(), result)
        val event = ErrorDiagnosticEvents.buffer.drain().single()
        assertSame(error, event.error)
        assertEquals(UiText.Resource(R.string.about_update_check_failed), event.operation)
    }

    @Test
    fun temporaryBackgroundCheckFailureThenSuccessLeavesNoDiagnostic() = runBlocking {
        ErrorDiagnosticEvents.buffer.drain()
        val repository = GitHubReleaseUpdateRepository(httpClient = object : GitHubHttpClient {
            override fun get(url: String, headers: Map<String, String>): String = throw IOException("Connection reset")
        })

        assertEquals(Result.retry(), updateCheckWorkerResult(0) { repository.latestRelease() })
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
        assertEquals(Result.success(), updateCheckWorkerResult(1) {})
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
    }

    @Test
    fun failedRetryOrPreviouslyRetriedWorkRecordsOneFinalDiagnostic() = runBlocking {
        ErrorDiagnosticEvents.buffer.drain()
        val initial = GitHubReleaseException("DNS lookup failed", IOException("No internet"), GitHubApiFailure.Transient)
        assertEquals(Result.retry(), updateCheckWorkerResult(0) { throw initial })

        for (attempt in listOf(1, 6)) {
            val final = GitHubReleaseException("Read timed out", IOException("Connection reset"), GitHubApiFailure.Transient)
            assertEquals(Result.failure(), updateCheckWorkerResult(attempt) { throw final })
            assertSame(final, ErrorDiagnosticEvents.buffer.drain().single().error)
        }
    }

    @Test
    fun rateLimitedBackgroundCheckEndsAndRetainsOriginalDiagnostic() = runBlocking {
        ErrorDiagnosticEvents.buffer.drain()
        val error = GitHubReleaseException("HTTP 403", failure = GitHubApiFailure.RateLimited)

        assertEquals(Result.failure(), updateCheckWorkerResult(0) { throw error })
        assertSame(error, ErrorDiagnosticEvents.buffer.drain().single().error)
    }

    @Test
    fun cancelledBackgroundCheckDoesNotRetryOrRecordFailure() = runBlocking {
        ErrorDiagnosticEvents.buffer.drain()

        val error = runCatching { updateCheckWorkerResult(0) { throw CancellationException("Stopped") } }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
    }
}
