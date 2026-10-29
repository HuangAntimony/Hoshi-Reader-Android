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
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ReadingSession
import moe.antimony.hoshi.features.sync.Timestamped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderAutoSyncExportControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun shelfReadActionsWaitForRetiringReadersQueuedBookmarkAndNewSessions() = runTest {
        val repository = BookRepository(temporary.root, StandardTestDispatcher(testScheduler))
        for (markRead in listOf(false, true)) {
            val root = repository.createBookDirectory(if (markRead) "read" else "unread")
            repository.saveMetadata(root, BookMetadata(root.name, "Book", folder = root.name, lastAccess = 0.0))
            repository.saveBookInfo(root, BookInfo(200, emptyMap()))
            repository.saveBookmark(root, Bookmark(0, 0.25, 50, 1.0))
            val controller = ReaderAutoSyncExportController(this)
            val allowSave = CompletableDeferred<Unit>()
            controller.launchSave {
                allowSave.await()
                repository.saveBookmark(root, Bookmark(0, 0.5, 100, 2.0))
            }.also { repository.trackReaderSave(root, it) }
            controller.launchSave {
                repository.saveBookmark(root, Bookmark(0, 0.75, 150, repository.currentAppleReferenceDateSeconds()))
                repository.saveTrackedSessions(root, mapOf("NEW" to Timestamped(3, ReadingSession(0, 10, 150))))
            }.also { repository.trackReaderSave(root, it) }

            val action = async { if (markRead) repository.markRead(root) else repository.markUnread(root) }
            runCurrent()
            val waitedForSaves = !action.isCompleted
            val allowLateSave = CompletableDeferred<Unit>()
            controller.launchSave {
                allowLateSave.await()
                repository.saveTrackedSessions(root, mapOf("LATE" to Timestamped(4, ReadingSession(10, 20, 10))))
            }.also { repository.trackReaderSave(root, it) }
            allowSave.complete(Unit)
            runCurrent()
            val waitedForLateSave = !action.isCompleted
            allowLateSave.complete(Unit)
            action.await()
            controller.flushSaves()

            assertTrue("The bookshelf action must wait for queued saves", waitedForSaves)
            assertTrue("The bookshelf action must include saves registered while waiting", waitedForLateSave)
            assertEquals(if (markRead) 200 else 0, repository.loadBookmark(root)!!.characterCount)
            val sessions = repository.loadSessions(root)
            val session = sessions.getValue("NEW").value
            if (markRead) assertEquals(150, session!!.charactersRead) else assertNull(session)
            val lateSession = sessions.getValue("LATE").value
            if (markRead) assertEquals(10, lateSession!!.charactersRead) else assertNull(lateSession)
        }
    }

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
