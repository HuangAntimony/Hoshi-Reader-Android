package moe.antimony.hoshi.navigation

import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.wallpaper.BookCoverPublishFailure
import moe.antimony.hoshi.features.wallpaper.BookCoverPublishResult
import moe.antimony.hoshi.features.wallpaper.BookCoverTargetResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderBookCoverPublicationCoordinatorTest {
    @Test
    fun iReaderSystemOptionFailureUsesSetupGuidance() {
        assertEquals(
            R.string.book_cover_wallpaper_ireader_not_selected_error,
            bookCoverPublishFailureMessageRes(
                BookCoverPublishResult(
                    lockScreen = BookCoverTargetResult.Skipped,
                    export = BookCoverTargetResult.Success,
                    iReader = BookCoverTargetResult.Failed(
                        BookCoverPublishFailure.IReaderBookScreenSaverNotSelected,
                    ),
                ),
            ),
        )
    }

    @Test
    fun mixedPublicationFailuresUseGenericMessage() {
        assertEquals(
            R.string.book_cover_wallpaper_publish_failed,
            bookCoverPublishFailureMessageRes(
                BookCoverPublishResult(
                    lockScreen = BookCoverTargetResult.Failed(
                        BookCoverPublishFailure.WallpaperUpdateFailed,
                    ),
                    export = BookCoverTargetResult.Skipped,
                    iReader = BookCoverTargetResult.Failed(
                        BookCoverPublishFailure.IReaderBookScreenSaverNotSelected,
                    ),
                ),
            ),
        )
    }

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
