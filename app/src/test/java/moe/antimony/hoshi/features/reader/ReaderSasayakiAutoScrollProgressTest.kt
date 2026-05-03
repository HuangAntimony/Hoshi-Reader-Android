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
        assertTrue(onCue.contains("val savedPosition = stateHolder.recordDisplayedProgress(progress)"))
        assertTrue(onCue.contains("onSaveBookmark(savedPosition.index, savedPosition.progress)"))
    }

    @Test
    fun readerKeepsScreenAwakeOnlyForPlayingAutoScrollSasayakiLikeIos() {
        val source = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val effect = source.substringAfter("val keepScreenOnForSasayaki =")
            .substringBefore("BackHandler(onBack = onClose)")

        assertTrue(source.contains("import android.view.WindowManager"))
        assertTrue(source.contains("import moe.antimony.hoshi.features.sasayaki.SasayakiScreenAwake"))
        assertTrue(effect.contains("SasayakiScreenAwake.shouldKeepScreenOn("))
        assertTrue(effect.contains("isPlaying = sasayakiPlayer?.isPlaying == true"))
        assertTrue(effect.contains("autoScroll = sasayakiSettings.autoScroll"))
        assertTrue(effect.contains("WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON"))
        assertTrue(effect.contains("window?.addFlags("))
        assertTrue(effect.contains("window?.clearFlags("))
        assertTrue(effect.contains("onDispose"))
    }
}
