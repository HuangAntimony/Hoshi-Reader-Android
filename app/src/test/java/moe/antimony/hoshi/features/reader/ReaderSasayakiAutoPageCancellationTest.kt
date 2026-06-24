package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSasayakiAutoPageCancellationTest {
    @Test
    fun cancelReleasesAutoPageHoldSynchronously() {
        val job = Job()
        val events = mutableListOf<String>()

        cancelReaderSasayakiAutoPage(
            job = job,
            clearAutoPageJob = { events += "clear-job" },
            clearPendingRestoreCue = { events += "clear-restore-cue" },
            resumeAfterAutoPageHold = { events += "release-hold" },
        )

        assertTrue(job.isCancelled)
        assertEquals(
            listOf("clear-job", "clear-restore-cue", "release-hold"),
            events,
        )
    }
}
