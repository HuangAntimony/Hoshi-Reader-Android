package moe.antimony.hoshi.navigation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderRouteDestinationSourceTest {
    @Test
    fun loadingAndErrorStatesUseReaderBackgroundToAvoidDarkModeWhiteFlash() {
        val source = File("src/main/java/moe/antimony/hoshi/navigation/ReaderRouteDestination.kt").readText()
        val loadingBranch = source.substringAfter("ReaderRouteLoadState.Loading -> Box(")
            .substringBefore("is ReaderRouteLoadState.Error -> Box(")
        val errorBranch = source.substringAfter("is ReaderRouteLoadState.Error -> Box(")
            .substringBefore("is ReaderRouteLoadState.Ready -> ReaderWebView(")

        assertTrue(source.contains("isSystemInDarkTheme()"))
        assertTrue(source.contains("readerSettings.backgroundColor(systemDarkTheme)"))
        assertTrue(loadingBranch.contains("readerLoadingBackground"))
        assertTrue(errorBranch.contains("readerLoadingBackground"))
    }
}
