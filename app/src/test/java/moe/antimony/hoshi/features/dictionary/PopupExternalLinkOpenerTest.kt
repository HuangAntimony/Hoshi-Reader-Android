package moe.antimony.hoshi.features.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupExternalLinkOpenerTest {
    @Test
    fun opensHttpLinksFromDictionaryGlossaries() {
        val opened = mutableListOf<ExternalBrowserRequest>()

        val handled = openPopupExternalLink(
            "https://www.pixiv.net/artworks/123",
            openExternalBrowser = opened::add,
        )

        assertTrue(handled)
        assertEquals(listOf(ExternalBrowserRequest("https://www.pixiv.net/artworks/123")), opened)
    }

    @Test
    fun ignoresNonWebLinksFromPopupMessages() {
        val opened = mutableListOf<ExternalBrowserRequest>()

        val handled = openPopupExternalLink(
            "javascript:alert(1)",
            openExternalBrowser = opened::add,
        )

        assertFalse(handled)
        assertTrue(opened.isEmpty())
    }
}
