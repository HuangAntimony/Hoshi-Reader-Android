package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch

import org.junit.Assert.assertEquals
import org.junit.Test

class SasayakiCueDisplayActionDispatcherTest {
    private val cue = SasayakiMatch("a", 10.0, 12.0, "a", 1, 2, 3)
    private val events = mutableListOf<String>()
    private val dispatcher = SasayakiCueDisplayActionDispatcher(
        onCue = { cue, reveal -> events += "cue:${cue.id}:$reveal" },
        onClearCue = { events += "clear" },
        onLoadChapter = { cue, reveal -> events += "load:${cue.chapterIndex}:${cue.id}:$reveal" },
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
        dispatcher.apply(SasayakiCueDisplayAction.Display(cue, reveal = true))

        assertEquals(listOf("cue:a:true"), events)
    }

    @Test
    fun clearAndLoadChapterClearsCueBeforeLoadingChapter() {
        dispatcher.apply(SasayakiCueDisplayAction.ClearAndLoadChapter(cue = cue, reveal = true))

        assertEquals(listOf("clear", "load:1:a:true"), events)
    }
}
