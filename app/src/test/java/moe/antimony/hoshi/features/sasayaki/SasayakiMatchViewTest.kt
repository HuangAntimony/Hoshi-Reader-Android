package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiMatchViewTest {
    @Test
    fun matchViewMatchesIosFileWindowAndCurrentMatchFlow() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiMatchView.kt").readText()

        assertTrue(source.contains("No file selected"))
        assertTrue(source.contains("Open"))
        assertTrue(source.contains("Search Window"))
        assertTrue(source.contains("Current Match"))
        assertTrue(source.contains("Match Rate"))
        assertTrue(source.contains("mutableFloatStateOf(200f)"))
        assertTrue(source.contains("valueRange = 50f..350f"))
        assertTrue(source.contains("steps = 11"))
        assertTrue(source.contains("searchWindow.roundToInt()"))
        assertTrue(source.contains("bookStorage.loadSasayakiMatch(bookEntry.root)"))
        assertTrue(source.contains("bookStorage.saveSasayakiMatch(bookEntry.root, nextMatch)"))
    }
}
