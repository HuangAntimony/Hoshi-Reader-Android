package moe.antimony.hoshi.features.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DictionaryDeepLinkRequestTest {
    @Test
    fun decodedTextDefaultsToOverlay() {
        val request = DictionaryDeepLinkRequest.from(
            action = "android.intent.action.VIEW",
            uri = "hoshi://search?text=%E9%A3%9F%E3%81%B9%E3%82%8B",
        )

        assertEquals("食べる", request?.text)
        assertEquals(DictionaryDeepLinkDestination.Overlay, request?.destination)
    }

    @Test
    fun appModePreservesDecodedTextForDictionarySearch() {
        val request = DictionaryDeepLinkRequest.from(
            action = "android.intent.action.VIEW",
            uri = "hoshi://search?text=%20%E7%8C%AB%20&mode=app",
        )

        assertEquals(" 猫 ", request?.text)
        assertEquals(DictionaryDeepLinkDestination.MainApp, request?.destination)
    }

    @Test
    fun literalPlusIsPreservedAsLookupText() {
        val request = DictionaryDeepLinkRequest.from(
            action = "android.intent.action.VIEW",
            uri = "hoshi://search?text=C++",
        )

        assertEquals("C++", request?.text)
        assertEquals(DictionaryDeepLinkDestination.Overlay, request?.destination)
    }

    @Test
    fun missingOrBlankTextUsesMainApp() {
        assertEquals(
            DictionaryDeepLinkDestination.MainApp,
            DictionaryDeepLinkRequest.from(
                action = "android.intent.action.VIEW",
                uri = "hoshi://search",
            )?.destination,
        )
        assertEquals(
            DictionaryDeepLinkDestination.MainApp,
            DictionaryDeepLinkRequest.from(
                action = "android.intent.action.VIEW",
                uri = "hoshi://search?text=%20%20%20",
            )?.destination,
        )
    }

    @Test
    fun unknownModeKeepsDefaultOverlayAndFirstParameterWins() {
        val request = DictionaryDeepLinkRequest.from(
            action = "android.intent.action.VIEW",
            uri = "hoshi://search?mode=popup&text=%E7%8C%AB&text=%E7%8A%AC&ignored=value",
        )

        assertEquals("猫", request?.text)
        assertEquals(DictionaryDeepLinkDestination.Overlay, request?.destination)
    }

    @Test
    fun rejectsOtherActionsSchemesHostsAndMalformedUris() {
        assertNull(
            DictionaryDeepLinkRequest.from(
                action = "android.intent.action.SEND",
                uri = "hoshi://search?text=%E7%8C%AB",
            ),
        )
        assertNull(
            DictionaryDeepLinkRequest.from(
                action = "android.intent.action.VIEW",
                uri = "other://search?text=%E7%8C%AB",
            ),
        )
        assertNull(
            DictionaryDeepLinkRequest.from(
                action = "android.intent.action.VIEW",
                uri = "hoshi://other?text=%E7%8C%AB",
            ),
        )
        assertNull(
            DictionaryDeepLinkRequest.from(
                action = "android.intent.action.VIEW",
                uri = "hoshi://search?text=%ZZ",
            ),
        )
    }
}
