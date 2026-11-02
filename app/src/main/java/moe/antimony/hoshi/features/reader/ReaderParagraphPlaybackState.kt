package moe.antimony.hoshi.features.reader

/** Rejects superseded page queries and carries a manual turn through document restoration. */
internal class ReaderParagraphPlaybackState {
    private data class Turn(val chapter: Int, val epoch: Int, val play: Boolean)
    private var turn: Turn? = null
    private var request = 0L

    fun clear() { turn = null; request++ }
    fun beginTurn(chapter: Int, epoch: Int, direction: ReaderNavigationDirection) {
        request++
        turn = Turn(chapter, epoch, direction == ReaderNavigationDirection.Forward)
    }
    fun beginQuery(): Long = ++request
    fun accepts(query: Long): Boolean = query == request
    fun takePlay(chapter: Int, epoch: Int): Boolean? {
        val pending = turn
        turn = null
        return pending?.takeIf { it.chapter == chapter && it.epoch == epoch }?.play
    }
}
