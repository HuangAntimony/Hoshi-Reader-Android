package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderSasayakiAutoPauseTest {
    @Test
    fun lookupAutoPauseResumesWhenRootPopupClosesLikeIos() {
        val source = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val stateHolder = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebViewStateHolder.kt").readText()

        assertTrue(stateHolder.contains("var sasayakiWasPausedByLookup by mutableStateOf(false)"))
        assertTrue(source.contains("fun pauseSasayakiForLookupIfNeeded()"))
        assertTrue(source.contains("stateHolder.shouldPauseSasayakiForLookup("))
        assertTrue(stateHolder.contains("sasayakiWasPausedByLookup = true"))
        assertTrue(source.contains("fun resumeSasayakiAfterLookupIfNeeded()"))
        assertTrue(source.contains("if (player != null && !player.isPlaying)"))
        assertTrue(source.contains("player.togglePlayback()"))
        assertTrue(source.contains("fun setLookupPopups(nextPopups: List<LookupPopupItem>)"))
        assertTrue(source.contains("stateHolder.setLookupPopups(nextPopups, ::resumeSasayakiAfterLookupIfNeeded)"))
        assertTrue(stateHolder.contains("if (popups.isEmpty() && sasayakiWasPausedByLookup)"))
        assertTrue(stateHolder.contains("resumeSasayakiAfterLookup()"))
        assertTrue(source.contains("setLookupPopups(emptyList())"))
        assertTrue(source.contains("onPopupsChange = ::setLookupPopups"))
        assertTrue(source.contains("sasayakiPlaying = sasayakiPlayer?.isPlaying == true || sasayakiWasPausedByLookup"))
    }
}
