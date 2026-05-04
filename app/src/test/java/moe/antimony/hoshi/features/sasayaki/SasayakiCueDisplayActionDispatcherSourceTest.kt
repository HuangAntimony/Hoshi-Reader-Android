package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiCueDisplayActionDispatcherSourceTest {
    @Test
    fun dispatcherOwnsCueDisplayActionCallbackSequencing() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiCueDisplayActionDispatcher.kt").readText()
        val apply = source.substringAfter("fun apply(")

        assertTrue(source.contains("private val onCue: (SasayakiMatch, Boolean) -> Unit"))
        assertTrue(source.contains("private val onClearCue: () -> Unit"))
        assertTrue(source.contains("private val onLoadChapter: (Int) -> Unit"))
        assertTrue(apply.contains("SasayakiCueDisplayAction.None -> Unit"))
        assertTrue(apply.contains("SasayakiCueDisplayAction.Clear -> onClearCue()"))
        assertTrue(apply.contains("is SasayakiCueDisplayAction.Display -> onCue(action.cue, action.reveal)"))
        assertTrue(apply.contains("is SasayakiCueDisplayAction.ClearAndLoadChapter -> {"))
        assertTrue(apply.contains("onClearCue()"))
        assertTrue(apply.contains("onLoadChapter(action.chapterIndex)"))
        assertTrue(apply.indexOf("onClearCue()") < apply.indexOf("onLoadChapter(action.chapterIndex)"))
        assertFalse(source.contains("mutableStateOf"))
    }
}
