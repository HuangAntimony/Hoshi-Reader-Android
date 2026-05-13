package moe.antimony.hoshi.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAutoSyncExportControllerTest {
    @Test
    fun flushExportSurvivesCallerScopeCancellationAndWaitsForLatestSave() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val callerJob = Job()
        val callerScope = CoroutineScope(callerJob + Dispatchers.Unconfined)
        val controller = ReaderAutoSyncExportController(appScope)
        val allowSave = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Unit>()
        val exportedAfterSave = CompletableDeferred<Boolean>()

        callerScope.launch {
            controller.launchSave {
                allowSave.await()
                saved.complete(Unit)
            }
            controller.scheduleExport(enabled = true) {
                exportedAfterSave.complete(saved.isCompleted)
            }
            controller.flushExport(enabled = true)
        }.join()

        callerJob.cancel()
        assertFalse(exportedAfterSave.isCompleted)

        allowSave.complete(Unit)

        assertTrue(withTimeout(1_000) { exportedAfterSave.await() })
        appScope.cancel()
    }
}
