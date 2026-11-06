package moe.antimony.hoshi.webview

import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.features.dictionary.LookupPopupAssets
import moe.antimony.hoshi.features.dictionary.LookupPopupHtml
import moe.antimony.hoshi.features.reader.lookupPopupAssetResponse
import org.junit.Assert.*
import org.junit.Test

class SelectionLanguageAssetsTest {
    @Test fun japaneseInlineAndExternalPopupsReadOnlyTheirLanguageScriptsOnce() {
        val reads = mutableListOf<String>()
        val languages = SelectionLanguageAssets { name -> reads += name; "/* $name */" }
        val assets = LookupPopupAssets(popupJs = "", popupCss = "", languageAssets = languages)
        assets.hashCode() // Asset version calculation must not force other languages to load.
        assertTrue(reads.isEmpty())
        repeat(2) { LookupPopupHtml.renderIframeDocument(assets = assets) }
        assertEquals(listOf("language-ja.js", "selection-ja.js"), reads)
        assertEquals("/* selection-ja.js */", lookupPopupAssetResponse("selection-ja.js", assets)?.content)
        assertEquals(2, reads.size)
        LookupPopupHtml.renderIframeDocument(assets = assets, contentLanguageProfile = ContentLanguageProfile.Chinese)
        assertEquals(listOf("language-ja.js", "selection-ja.js", "language-cjk.js", "selection-zh.js"), reads)
        LookupPopupHtml.renderIframeDocument(assets = assets, contentLanguageProfile = ContentLanguageProfile.Korean)
        assertEquals(listOf("language-ja.js", "selection-ja.js", "language-cjk.js", "selection-zh.js", "selection-ko.js"), reads)
    }
}
