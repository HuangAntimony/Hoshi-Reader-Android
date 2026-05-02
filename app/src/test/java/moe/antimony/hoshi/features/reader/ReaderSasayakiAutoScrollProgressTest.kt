package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderSasayakiAutoScrollProgressTest {
    @Test
    fun cueRevealProgressUpdatesReaderPositionAndBookmarkLikeIos() {
        val source = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val onCue = source.substringAfter("onCue = { cue, reveal ->")
            .substringBefore("onClearCue = {")

        assertTrue(onCue.contains("ReaderPaginationScripts.highlightSasayakiCueInvocation(cue.id, reveal)"))
        assertTrue(onCue.contains("ReaderPaginationScripts.doubleResult(progressResult)?.let { progress ->"))
        assertTrue(onCue.contains("val updatedPosition = readerPosition.recordPageProgress(progress)"))
        assertTrue(onCue.contains("readerPosition = updatedPosition"))
        assertTrue(onCue.contains("onSaveBookmark(updatedPosition.displayedPosition.index, updatedPosition.displayedPosition.progress)"))
    }
}
