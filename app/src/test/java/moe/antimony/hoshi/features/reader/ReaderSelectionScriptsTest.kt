package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSelectionScriptsTest {
    @Test
    fun exposesIosNamedSelectionApi() {
        val script = ReaderSelectionScripts.script()

        assertTrue(script.contains("window.hoshiSelection"))
        assertTrue(script.contains("selectText: function(x, y, maxLength)"))
        assertTrue(script.contains("clearSelection: function()"))
        assertTrue(script.contains("highlightSelection: function(charCount)"))
    }

    @Test
    fun exposesClearSelectionInvocationForNativeDismissPaths() {
        assertEquals("window.hoshiSelection.clearSelection()", ReaderSelectionScripts.clearInvocation())
    }

    @Test
    fun postsSelectionDataToAndroidBridge() {
        val script = ReaderSelectionScripts.script()

        assertTrue(script.contains("HoshiTextSelection.postMessage(JSON.stringify({"))
        assertTrue(script.contains("text: text"))
        assertTrue(script.contains("sentence: sentence"))
        assertTrue(script.contains("normalizedOffset: normalizedOffset"))
    }

    @Test
    fun doesNotStartLookupWhenTapHitsLinkLikeIos() {
        val script = ReaderSelectionScripts.script()

        assertTrue(script.contains("document.elementFromPoint(x, y)"))
        assertTrue(script.contains(".closest('a')"))
        assertTrue(script.contains("return null;"))
    }

    @Test
    fun canTreatNonJapaneseCharactersAsScanBoundariesLikeIos() {
        val script = ReaderSelectionScripts.script()
        val readerSource = java.io.File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()

        assertTrue(script.contains("const JAPANESE_RANGES = ["))
        assertTrue(script.contains("isCodePointJapanese: function(codePoint)"))
        assertTrue(script.contains("window.scanNonJapaneseText === false && !this.isCodePointJapanese(char.codePointAt(0))"))
        assertTrue(readerSource.contains("window.scanNonJapaneseText = ${'$'}scanNonJapaneseText;"))
        assertTrue(readerSource.contains("scanNonJapaneseText = dictionarySettings.scanNonJapaneseText"))
    }

    @Test
    fun convertsAndroidPixelsToWebViewCssPixels() {
        assertEquals(333.33334f, androidPixelsToCssPixels(1000f, 3f), 0.0001f)
    }

    @Test
    fun recognizesNullSelectionResultForTapOutside() {
        assertTrue(ReaderSelectionScripts.didSelectNothing("null"))
        assertTrue(ReaderSelectionScripts.didSelectNothing(null))
        assertTrue(ReaderSelectionScripts.didSelectNothing("undefined"))
        assertTrue(!ReaderSelectionScripts.didSelectNothing("\"猫\""))
    }
}
