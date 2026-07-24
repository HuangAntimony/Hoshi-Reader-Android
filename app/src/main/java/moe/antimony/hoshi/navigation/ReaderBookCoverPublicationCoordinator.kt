package moe.antimony.hoshi.navigation

import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.wallpaper.BookCoverPublishFailure
import moe.antimony.hoshi.features.wallpaper.BookCoverPublishResult
import moe.antimony.hoshi.features.wallpaper.BookCoverTargetResult

internal fun bookCoverPublishFailureMessageRes(result: BookCoverPublishResult): Int {
    val hasOtherFailure = result.lockScreen is BookCoverTargetResult.Failed ||
        result.export is BookCoverTargetResult.Failed
    val iReaderFailure = result.iReader as? BookCoverTargetResult.Failed
    return if (
        !hasOtherFailure &&
        iReaderFailure?.reason == BookCoverPublishFailure.IReaderBookScreenSaverNotSelected
    ) {
        R.string.book_cover_wallpaper_ireader_not_selected_error
    } else {
        R.string.book_cover_wallpaper_publish_failed
    }
}

internal sealed interface ReaderBookCoverPublicationEvent {
    data object NotReady : ReaderBookCoverPublicationEvent

    data class Ready(
        val bookId: String,
        val coverPath: String?,
        val loadGeneration: Int = 0,
    ) : ReaderBookCoverPublicationEvent
}

internal class ReaderBookCoverPublicationCoordinator {
    private var currentReady: ReaderBookCoverPublicationEvent.Ready? = null

    fun shouldPublish(event: ReaderBookCoverPublicationEvent): Boolean = when (event) {
        ReaderBookCoverPublicationEvent.NotReady -> {
            currentReady = null
            false
        }
        is ReaderBookCoverPublicationEvent.Ready -> {
            if (currentReady == event) {
                false
            } else {
                currentReady = event
                true
            }
        }
    }
}
