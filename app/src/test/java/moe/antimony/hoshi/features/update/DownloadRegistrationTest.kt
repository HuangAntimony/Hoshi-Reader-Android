package moe.antimony.hoshi.features.update

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.ui.diagnosticMessage
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DownloadRegistrationTest {
    @Test fun leavingScreenStillPersistsStartedDownload() = runTest {
        val gate = CompletableDeferred<Unit>()
        var saved: Long? = null
        val job = launch {
            persistStartedDownload({ 42L }, { gate.await(); saved = it }, { fail("Must retain registered download") })
        }
        runCurrent()
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertEquals(42L, saved)
    }

    @Test fun persistenceFailureRemovesUntrackedDownloadAndPreservesCause() = runTest {
        val failure = IOException("Cannot save download", IOException("Disk full"))
        var removed: Long? = null
        try {
            persistStartedDownload({ 42L }, { throw failure }, { removed = it })
            fail("Expected failure")
        } catch (error: IOException) {
            assertEquals("Cannot save download", error.message)
            assertTrue(error.diagnosticMessage().contains("Disk full"))
        }
        assertEquals(42L, removed)
    }
}
