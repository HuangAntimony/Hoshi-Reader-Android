package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SasayakiCuePresentationStateTest {
    @Test
    fun defaultsToAutoScrollBeforeFirstPlayback() {
        val state = SasayakiCuePresentationState()

        assertTrue(state.autoScroll)
        assertFalse(state.hasPlayedOnce)
    }

    @Test
    fun autoScrollRemainsComposeObservedAndWritable() {
        val state = SasayakiCuePresentationState()

        state.autoScroll = false

        assertFalse(state.autoScroll)
    }

    @Test
    fun markPlayedOnceOnlyEnablesFirstPlayState() {
        val state = SasayakiCuePresentationState()

        state.markPlayedOnce()

        assertTrue(state.hasPlayedOnce)
        assertTrue(state.autoScroll)
    }
}
