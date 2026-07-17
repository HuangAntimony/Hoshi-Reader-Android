package moe.antimony.hoshi.navigation

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
