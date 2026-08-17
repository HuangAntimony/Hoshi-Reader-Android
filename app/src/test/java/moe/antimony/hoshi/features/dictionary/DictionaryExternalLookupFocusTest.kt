package moe.antimony.hoshi.features.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryExternalLookupFocusTest {
    @Test
    fun blankExternalLookupRequestsSearchFocus() {
        assertEquals(
            DictionaryExternalLookupFocus.Request,
            dictionaryExternalLookupFocus("   "),
        )
    }

    @Test
    fun populatedExternalLookupClearsSearchFocus() {
        assertEquals(
            DictionaryExternalLookupFocus.Clear,
            dictionaryExternalLookupFocus("猫"),
        )
    }
}
