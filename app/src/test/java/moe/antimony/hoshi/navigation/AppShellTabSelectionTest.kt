package moe.antimony.hoshi.navigation

import moe.antimony.hoshi.features.bookshelf.MainTab
import org.junit.Assert.assertEquals
import org.junit.Test

class AppShellTabSelectionTest {
    @Test
    fun repeatedDictionaryTabSelectionRequestsSearchFocus() {
        assertEquals(
            8,
            nextDictionaryFocusRequestKey(
                selectedTab = MainTab.Dictionary,
                requestedTab = MainTab.Dictionary,
                currentKey = 7,
            ),
        )
    }

    @Test
    fun switchingToDictionaryDoesNotRequestRepeatedFocus() {
        assertEquals(
            7,
            nextDictionaryFocusRequestKey(
                selectedTab = MainTab.Books,
                requestedTab = MainTab.Dictionary,
                currentKey = 7,
            ),
        )
    }
}
