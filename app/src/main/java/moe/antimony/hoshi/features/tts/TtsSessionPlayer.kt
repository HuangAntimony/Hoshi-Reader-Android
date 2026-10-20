package moe.antimony.hoshi.features.tts

import androidx.annotation.OptIn
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** A sentence transport, including the time spent synthesizing, with no seekable audio timeline. */
@OptIn(UnstableApi::class)
internal class TtsSessionPlayer(
    player: Player,
    private val snapshot: () -> TtsPlaybackState,
    private val playSentence: () -> Unit,
    private val pauseSentence: () -> Unit,
    private val previousSentence: () -> Unit,
    private val nextSentence: () -> Unit,
    private val stopSentences: () -> Unit,
) : ForwardingSimpleBasePlayer(player) {
    fun refresh() = invalidateState()

    override fun getState(): State {
        val actual = super.getState()
        val value = snapshot()
        val sentence = value.sentence.takeIf { value.started }
        // Reading a book's source is not media loading: an empty session must remain idle.
        val preparingSentence = sentence != null && value.preparing
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            Player.COMMAND_GET_VOLUME,
        )
        if (value.hasPrevious) commands.addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        if (value.hasNext) commands.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        val playlist = sentence?.let {
            val metadata = MediaMetadata.Builder().setTitle(value.title).setArtist(it.text.take(160)).build()
            listOf(MediaItemData.Builder("${value.bookId}:${it.chapter}:${it.start}")
                .setMediaItem(MediaItem.Builder().setMediaId("tts:${value.bookId}").setMediaMetadata(metadata).build())
                .setMediaMetadata(metadata).setIsSeekable(false).setDurationUs(C.TIME_UNSET).build())
        }.orEmpty()
        return actual.buildUpon()
            .setAvailableCommands(commands.build())
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (playlist.isEmpty()) C.INDEX_UNSET else 0)
            .setContentPositionMs(0)
            .setContentBufferedPositionMs { 0 }
            .setTotalBufferedDurationMs { 0 }
            .setPlayWhenReady(value.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (sentence == null) Player.STATE_IDLE else if (preparingSentence) Player.STATE_BUFFERING else actual.playbackState)
            .setIsLoading(preparingSentence)
            .build()
    }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) playSentence() else pauseSentence()
        return Futures.immediateVoidFuture()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> previousSentence()
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> nextSentence()
        }
        return Futures.immediateVoidFuture()
    }
    override fun handleStop(): ListenableFuture<*> { stopSentences(); return Futures.immediateVoidFuture() }
    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}
