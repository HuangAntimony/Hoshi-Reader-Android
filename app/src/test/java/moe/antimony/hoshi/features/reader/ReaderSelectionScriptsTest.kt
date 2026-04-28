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
    fun postsSelectionDataToAndroidBridge() {
        val script = ReaderSelectionScripts.script()

        assertTrue(script.contains("HoshiTextSelection.postMessage(JSON.stringify({"))
        assertTrue(script.contains("text: text"))
        assertTrue(script.contains("sentence: sentence"))
        assertTrue(script.contains("normalizedOffset: normalizedOffset"))
    }

    @Test
    fun convertsAndroidPixelsToWebViewCssPixels() {
        assertEquals(333.33334f, androidPixelsToCssPixels(1000f, 3f), 0.0001f)
    }
}
