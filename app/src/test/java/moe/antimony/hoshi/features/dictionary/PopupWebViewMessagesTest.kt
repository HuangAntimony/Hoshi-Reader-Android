package moe.antimony.hoshi.features.dictionary

import java.io.File
import org.junit.Assert.assertFalse
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
    fun popupBridgeBatchesLookupEntryRequestsLikeIos() {
        val bridgeSource = File("src/main/java/moe/antimony/hoshi/features/dictionary/PopupWebViewMessages.kt")
            .readText()
        val htmlSource = File("src/main/java/moe/antimony/hoshi/features/dictionary/LookupPopupHtml.kt")
            .readText()
        val popupSource = File("src/main/assets/hoshi-popup/popup.js").readText()

        assertTrue(bridgeSource.contains("fun getEntries(start: Int, count: Int): String"))
        assertTrue(htmlSource.contains("getEntries: { postMessage: async function(request)"))
        assertTrue(popupSource.contains("webkit.messageHandlers.getEntries.postMessage"))
        assertTrue(popupSource.contains("count: Math.min(4, window.entryCount - idx)"))
        assertTrue(popupSource.contains("entries.forEach((entry, offset)"))
        assertTrue(popupSource.contains("if (idx === 0 || (idx + 1) % 4 === 0)"))
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
    fun popupSelectionScriptCanTreatNonJapaneseCharactersAsBoundaries() {
        val source = File("src/main/assets/hoshi-popup/selection.js").readText()
        val popupSource = File("src/main/assets/hoshi-popup/popup.js").readText()

        assertTrue(source.contains("const JAPANESE_RANGES = ["))
        assertTrue(source.contains("isCodePointJapanese(codePoint)"))
        assertTrue(source.contains("window.scanNonJapaneseText === false && !this.isCodePointJapanese(char.codePointAt(0))"))
        assertTrue(popupSource.contains("window.hoshiSelection?.isCodePointJapanese(c.codePointAt(0))"))
    }

    @Test
    fun popupMiningUsesStoredHoshiSelectionTextBeforeWebViewClearsNativeSelection() {
        val source = File("src/main/assets/hoshi-popup/popup.js").readText()

        assertTrue(source.contains("function getPopupSelectionText()"))
        assertTrue(source.contains("window.hoshiSelection?.selection?.text"))
        assertTrue(source.contains("lastSelection = getPopupSelectionText();"))
    }

    @Test
    fun popupJavascriptMatchesLatestIosClickAndRedirectFixes() {
        val source = File("src/main/assets/hoshi-popup/popup.js").readText()

        assertTrue(source.contains("target?.closest('summary')"))
        assertTrue(source.contains("requestAnimationFrame(() => {\n        document.scrollingElement.scrollTop = 0;\n        requestAnimationFrame(() => {"))
    }

    @Test
    fun popupJavascriptKeepsEmGlossaryImagesSizedInEmAfterLoad() {
        val source = File("src/main/assets/hoshi-popup/popup.js").readText()

        assertTrue(source.contains("!hasPreferredWidth && !hasPreferredHeight && sizeUnits === 'em'"))
        assertTrue(source.contains("const widthEm = typeof data.width === 'number' ? data.width : data.height / aspectRatio;"))
        assertTrue(source.contains("imageContainer.style.width = `\${widthEm}em`;"))
    }

    @Test
    fun popupJavascriptUsesIosDictionaryCollapseModes() {
        val source = File("src/main/assets/hoshi-popup/popup.js").readText()

        assertTrue(source.contains("window.collapseMode === 'Collapse All'"))
        assertTrue(source.contains("window.collapseMode === 'Custom' && window.collapsedDictionaries.includes(dictName)"))
        assertTrue(source.contains("details.open = !collapsed || (window.expandFirstDictionary && isFirst);"))
        assertFalse(source.contains("window.collapseDictionaries"))
    }
}
