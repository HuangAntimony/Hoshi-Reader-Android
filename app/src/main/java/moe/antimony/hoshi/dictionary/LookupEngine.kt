package moe.antimony.hoshi.dictionary

import de.manhhao.hoshi.HoshiDicts
import de.manhhao.hoshi.LookupResult

object LookupEngine {
    fun lookup(text: String, maxResults: Int = 16): List<LookupResult> =
        HoshiDicts.lookup(HoshiDicts.lookupObject, text, maxResults).toList()
}
