package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.tts.TtsPlaybackState
import moe.antimony.hoshi.features.tts.TtsSentence
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTtsPresentationTest {
    private val sentence = TtsSentence(1, 30, 4, "次です。")
    private val paused = TtsPlaybackState(bookId = "book", sentences = listOf(sentence), started = true)

    @Test fun pausedChapterRestoreDoesNotReturnToTheNarrationChapter() {
        assertEquals(ReaderTtsPresentation.None, readerTtsPresentation(paused, sentence, 2, autoScroll = true))
        assertEquals(ReaderTtsPresentation.None, readerTtsPresentation(paused, sentence, 0, autoScroll = true))
    }

    @Test fun pausedSameChapterRestoreOnlyHighlightsWithoutRevealingTheSentence() {
        assertEquals(ReaderTtsPresentation.Highlight, readerTtsPresentation(paused, sentence, 1, autoScroll = true))
    }

    @Test fun playingAndResumingFollowOnlyWhenAutoScrollIsEnabled() {
        val playing = paused.copy(playing = true)
        assertEquals(ReaderTtsPresentation.Follow, readerTtsPresentation(playing, sentence, 2, autoScroll = true))
        assertEquals(ReaderTtsPresentation.Follow, readerTtsPresentation(playing, sentence, 1, autoScroll = true))
        assertEquals(ReaderTtsPresentation.Highlight, readerTtsPresentation(playing, sentence, 1, autoScroll = false))
        assertEquals(ReaderTtsPresentation.None, readerTtsPresentation(playing, sentence, 2, autoScroll = false))
    }

    @Test fun explicitPausedNavigationRevealsTheSelectedSentenceAcrossChapters() {
        assertEquals(ReaderTtsPresentation.Follow,
            readerTtsPresentation(paused, sentence, 2, autoScroll = true, restoring = false, directJump = true))
        assertEquals(ReaderTtsPresentation.Follow,
            readerTtsPresentation(paused, sentence, 1, autoScroll = false, restoring = false, directJump = true))
    }

    @Test fun oldPresentationCannotFollowAfterStopOrSentenceChange() {
        assertEquals(ReaderTtsPresentation.None, readerTtsPresentation(TtsPlaybackState(), sentence, 1, autoScroll = true))
        val changed = paused.copy(sentences = listOf(sentence.copy(start = 40)), playing = true)
        assertEquals(ReaderTtsPresentation.None, readerTtsPresentation(changed, sentence, 1, autoScroll = true))
    }

    @Test fun restoringDuringPreparationCannotBypassPendingImageHolds() {
        val preparing = paused.copy(playing = true, preparing = true)
        assertEquals(ReaderTtsPresentation.None, readerTtsPresentation(preparing, sentence, 1, autoScroll = true))
        assertEquals(ReaderTtsPresentation.None, readerTtsPresentation(preparing, sentence, 2, autoScroll = true))
        assertEquals(ReaderTtsPresentation.Follow,
            readerTtsPresentation(preparing, sentence, 1, autoScroll = true, restoring = false))
    }
}
