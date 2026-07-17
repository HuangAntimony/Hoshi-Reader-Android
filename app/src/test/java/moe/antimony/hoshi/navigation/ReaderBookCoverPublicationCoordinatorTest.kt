package moe.antimony.hoshi.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderBookCoverPublicationCoordinatorTest {
    @Test
    fun readyRecompositionPublishesOnceAndReopenPublishesAgain() {
        val coordinator = ReaderBookCoverPublicationCoordinator()
        val ready = ReaderBookCoverPublicationEvent.Ready(
            bookId = "book-a",
            coverPath = "/covers/book-a.png",
        )

        assertTrue(coordinator.shouldPublish(ready))
        assertFalse(coordinator.shouldPublish(ready))
        assertFalse(coordinator.shouldPublish(ReaderBookCoverPublicationEvent.NotReady))
        assertTrue(coordinator.shouldPublish(ready))
        assertTrue(coordinator.shouldPublish(ready.copy(loadGeneration = 1)))
    }

    @Test
    fun changingBooksWhileReadyPublishesTheNewCover() {
        val coordinator = ReaderBookCoverPublicationCoordinator()

        assertTrue(
            coordinator.shouldPublish(
                ReaderBookCoverPublicationEvent.Ready("book-a", "/covers/book-a.png"),
            ),
        )
        assertTrue(
            coordinator.shouldPublish(
                ReaderBookCoverPublicationEvent.Ready("book-b", "/covers/book-b.png"),
            ),
        )
    }
}
