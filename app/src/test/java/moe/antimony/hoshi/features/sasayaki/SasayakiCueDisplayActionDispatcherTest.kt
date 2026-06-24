package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch

import org.junit.Assert.assertEquals
import org.junit.Test

class SasayakiCueDisplayActionDispatcherTest {
    private val cue = SasayakiMatch("a", 10.0, 12.0, "a", 1, 2, 3)
    private val events = mutableListOf<String>()
    private val dispatcher = SasayakiCueDisplayActionDispatcher(
        onCue = { cue, reveal, source -> events += "cue:${cue.id}:$reveal:$source" },
        onClearCue = { events += "clear" },
    )

    @Test
    fun noneDoesNotDispatchReaderCallbacks() {
        dispatcher.apply(SasayakiCueDisplayAction.None)

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun clearDispatchesOnlyCueClear() {
        dispatcher.apply(SasayakiCueDisplayAction.Clear)

        assertEquals(listOf("clear"), events)
    }

    @Test
    fun displayDispatchesCueAndRevealFlag() {
        dispatcher.apply(
            SasayakiCueDisplayAction.Display(
                cue = cue,
                reveal = true,
                source = SasayakiCueRevealSource.NaturalPlayback,
            ),
        )

        assertEquals(listOf("cue:a:true:NaturalPlayback"), events)
    }

    @Test
    fun clearAndDisplayClearsCueBeforeDispatchingCue() {
        dispatcher.apply(
            SasayakiCueDisplayAction.ClearAndDisplay(
                cue = cue,
                reveal = true,
                source = SasayakiCueRevealSource.NaturalPlayback,
            ),
        )

        assertEquals(listOf("clear", "cue:a:true:NaturalPlayback"), events)
    }
}
