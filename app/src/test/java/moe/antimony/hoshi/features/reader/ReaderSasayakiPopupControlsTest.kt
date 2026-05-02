package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderSasayakiPopupControlsTest {
    @Test
    fun lookupPopupsCarrySasayakiCueForIosPopupControls() {
        val readerSource = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val stackSource = File("src/main/java/moe/antimony/hoshi/features/dictionary/LookupPopupStack.kt").readText()
        val popupSource = File("src/main/java/moe/antimony/hoshi/features/dictionary/LookupPopupView.kt").readText()

        assertTrue(stackSource.contains("val sasayakiCue: SasayakiMatch? = null"))
        assertTrue(readerSource.contains("sasayakiCueForSelection(selection)"))
        assertTrue(readerSource.contains("sasayakiCue = sasayakiCueForSelection(selection)"))
        assertTrue(readerSource.contains("player.findCue(chapterIndex = readerPosition.displayedPosition.index, offset = offset)"))
        assertTrue(popupSource.contains("SasayakiPopupControls("))
    }

    @Test
    fun popupControlsExposeIosReplayToggleAndPlayForwardActions() {
        val readerSource = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val stackSource = File("src/main/java/moe/antimony/hoshi/features/dictionary/LookupPopupStack.kt").readText()
        val popupSource = File("src/main/java/moe/antimony/hoshi/features/dictionary/LookupPopupView.kt").readText()

        assertTrue(stackSource.contains("sasayakiWasPaused: Boolean = false"))
        assertTrue(stackSource.contains("onSasayakiPauseStateCleared: () -> Unit = {}"))
        assertTrue(readerSource.contains("sasayakiWasPaused = sasayakiWasPausedByLookup"))
        assertTrue(readerSource.contains("onSasayakiReplayCue = { cue -> sasayakiPlayer?.playCue(cue, stop = true) }"))
        assertTrue(readerSource.contains("onSasayakiPlayForward = { cue ->"))
        assertTrue(readerSource.contains("sasayakiPlayer?.playCue(cue, stop = false)"))
        assertTrue(readerSource.contains("setLookupPopups(emptyList())"))
        assertTrue(popupSource.contains("Replay Sasayaki Cue"))
        assertTrue(popupSource.contains("Play From Sasayaki Cue"))
        assertTrue(popupSource.contains("WordAudioPlayer.get(context).stop()"))
    }
}
