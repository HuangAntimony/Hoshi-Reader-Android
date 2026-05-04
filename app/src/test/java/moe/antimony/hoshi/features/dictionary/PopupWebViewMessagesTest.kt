package moe.antimony.hoshi.features.dictionary

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupWebViewMessagesTest {
    @Test
    fun popupWebViewClientServesDictionaryImagesLikeIosImageHandler() {
        val source = File("src/main/java/moe/antimony/hoshi/features/dictionary/PopupWebViewMessages.kt")
            .readText()

        assertTrue(source.contains("isIosImageScheme"))
        assertTrue(source.contains("isAndroidImageEndpoint"))
        assertTrue(source.contains("getMediaFile"))
        assertTrue(source.contains("image/svg+xml"))
    }

    @Test
    fun dictionaryImageMimeTypeMatchesIosImageHandler() {
        assertTrue(dictionaryImageMimeType("icons/arrow.svg") == "image/svg+xml")
        assertTrue(dictionaryImageMimeType("photo.PNG") == "image/png")
        assertTrue(dictionaryImageMimeType("image.jpeg") == "image/jpeg")
        assertTrue(dictionaryImageMimeType("unknown.bin") == "application/octet-stream")
    }

    @Test
    fun popupJavascriptGivesDictionaryImageContainersExplicitHeightForAndroidWebView() {
        val source = File("src/main/assets/hoshi-popup/popup.js").readText()

        assertTrue(source.contains("function applyDictionaryImageContainerFixes(imageContainer)"))
        assertTrue(source.contains("window.disablePopupImageViewportMaxHeight"))
        assertTrue(source.contains("imageContainer.style.maxHeight = 'none';"))
        assertTrue(source.contains("applyDictionaryImageContainerFixes(imageContainer);"))
    }

    @Test
    fun popupBridgeCanReplaceLookupEntriesForInternalRedirects() {
        val source = File("src/main/java/moe/antimony/hoshi/features/dictionary/PopupWebViewMessages.kt")
            .readText()

        assertTrue(source.contains("val onLookupRedirect: (String) -> List<LookupResult>"))
        assertTrue(source.contains("fun lookupRedirect(query: String): Int"))
        assertTrue(source.contains("lookupResultsHolder.results = results"))
        assertTrue(source.contains("return results.size"))
    }

    @Test
    fun popupBridgeExposesAnkiMiningCallbacksToJavascript() {
        val source = File("src/main/java/moe/antimony/hoshi/features/dictionary/PopupWebViewMessages.kt")
            .readText()

        assertTrue(source.contains("val onMineEntry: (String) -> Boolean"))
        assertTrue(source.contains("val onDuplicateCheck: (String) -> Boolean"))
        assertTrue(source.contains("@JavascriptInterface\n    fun mineEntry"))
        assertTrue(source.contains("@JavascriptInterface\n    fun duplicateCheck"))
    }

    @Test
    fun popupSelectionScriptDoesNotLookupLinkedText() {
        val source = File("src/main/assets/hoshi-popup/selection.js").readText()

        assertTrue(source.contains("document.elementFromPoint(x, y)"))
        assertTrue(source.contains(".closest('a')"))
        assertTrue(source.contains("return null;"))
    }

    @Test
    fun popupMiningUsesStoredHoshiSelectionTextBeforeWebViewClearsNativeSelection() {
        val source = File("src/main/assets/hoshi-popup/popup.js").readText()

        assertTrue(source.contains("function getPopupSelectionText()"))
        assertTrue(source.contains("window.hoshiSelection?.selection?.text"))
        assertTrue(source.contains("lastSelection = getPopupSelectionText();"))
    }
}
