package moe.antimony.hoshi.features.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderParagraphPlaybackStateTest {
    @Test fun latestQueryOwnsDirectionUntilTargetDocumentIsReady() {
        val state = ReaderParagraphPlaybackState()
        state.beginTurn(3, 7, ReaderNavigationDirection.Forward)
        val stale = state.beginQuery()
        val current = state.beginQuery()
        assertFalse(state.accepts(stale))
        assertTrue(state.accepts(current))
        assertEquals(true, state.takePlay(3, 7))
        assertNull(state.takePlay(3, 7))
        state.beginTurn(2, 8, ReaderNavigationDirection.Backward)
        assertEquals(false, state.takePlay(2, 8))
    }

    @Test fun replacementRestoreOrDisablingCannotReuseOldTurn() {
        val state = ReaderParagraphPlaybackState()
        state.beginTurn(3, 7, ReaderNavigationDirection.Forward)
        assertNull(state.takePlay(3, 8))
        state.beginTurn(3, 8, ReaderNavigationDirection.Forward)
        val query = state.beginQuery()
        state.clear()
        assertFalse(state.accepts(query))
        assertNull(state.takePlay(3, 8))
    }
}
