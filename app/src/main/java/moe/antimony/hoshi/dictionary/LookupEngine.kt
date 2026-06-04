package moe.antimony.hoshi.dictionary

import de.manhhao.hoshi.HoshiDicts
import de.manhhao.hoshi.DictionaryStyle
import de.manhhao.hoshi.LookupResult

object LookupEngine {
    fun lookup(
        text: String,
        maxResults: Int = 16,
        scanLength: Int = 16,
        language: String = "ja",
    ): List<LookupResult> =
        synchronized(HoshiDicts) {
            HoshiDicts.setLookupLanguage(HoshiDicts.lookupObject, language)
            HoshiDicts.lookup(HoshiDicts.lookupObject, text, maxResults, scanLength).toList()
        }

    fun getStyles(): List<DictionaryStyle> =
        HoshiDicts.getStyles(HoshiDicts.lookupObject).toList()
}
