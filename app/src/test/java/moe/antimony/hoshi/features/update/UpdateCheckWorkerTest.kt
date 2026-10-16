package moe.antimony.hoshi.features.update

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
    fun failedBackgroundCheckRetriesAndRetainsOriginalDiagnostic() = runBlocking {
        ErrorDiagnosticEvents.buffer.drain()
        val error = IOException("GitHub unavailable", IOException("Connection reset"))

        val result = updateCheckWorkerResult { throw error }

        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
        val event = ErrorDiagnosticEvents.buffer.drain().single()
        assertSame(error, event.error)
        assertEquals(UiText.Resource(R.string.about_update_check_failed), event.operation)
    }

    @Test
    fun cancelledBackgroundCheckDoesNotRetryOrRecordFailure() = runBlocking {
        ErrorDiagnosticEvents.buffer.drain()

        val error = runCatching { updateCheckWorkerResult { throw CancellationException("Stopped") } }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
    }
}
