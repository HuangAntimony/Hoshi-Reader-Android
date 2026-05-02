package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderSasayakiAutoPauseTest {
    @Test
    fun lookupAutoPauseResumesWhenRootPopupClosesLikeIos() {
        val source = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()

        assertTrue(source.contains("var sasayakiWasPausedByLookup by remember { mutableStateOf(false) }"))
        assertTrue(source.contains("fun pauseSasayakiForLookupIfNeeded()"))
        assertTrue(source.contains("sasayakiWasPausedByLookup = true"))
        assertTrue(source.contains("fun resumeSasayakiAfterLookupIfNeeded()"))
        assertTrue(source.contains("if (sasayakiWasPausedByLookup && player != null && !player.isPlaying)"))
        assertTrue(source.contains("player.togglePlayback()"))
        assertTrue(source.contains("fun setLookupPopups(nextPopups: List<LookupPopupItem>)"))
        assertTrue(source.contains("if (nextPopups.isEmpty()) {"))
        assertTrue(source.contains("resumeSasayakiAfterLookupIfNeeded()"))
        assertTrue(source.contains("setLookupPopups(emptyList())"))
        assertTrue(source.contains("onPopupsChange = ::setLookupPopups"))
        assertTrue(source.contains("sasayakiPlaying = sasayakiPlayer?.isPlaying == true || sasayakiWasPausedByLookup"))
    }
}
