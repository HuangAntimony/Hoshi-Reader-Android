package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.dictionary.LookupPopupAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderLookupPopupResourceHandlerTest {
    @Test
    fun appAssetsRoutesRecognizePopupFontAndImageRequestsForNotFoundFallbacks() {
        assertEquals(
            ReaderLookupPopupAppAssetRoute.Popup,
            readerLookupPopupAppAssetRoute(
                scheme = "https",
                host = "appassets.androidplatform.net",
                path = "/popup/language-ja.js",
            ),
        )
        assertEquals(
            ReaderLookupPopupAppAssetRoute.Popup,
            readerLookupPopupAppAssetRoute(
                scheme = "https",
                host = "appassets.androidplatform.net",
                path = "/popup/icons/missing.svg",
            ),
        )
        assertEquals(
            ReaderLookupPopupAppAssetRoute.Font,
            readerLookupPopupAppAssetRoute(
                scheme = "https",
                host = "appassets.androidplatform.net",
                path = "/fonts/Missing.ttf",
            ),
        )
        assertEquals(
            ReaderLookupPopupAppAssetRoute.Image,
            readerLookupPopupAppAssetRoute(
                scheme = "https",
                host = "appassets.androidplatform.net",
                path = "/image",
            ),
        )
        assertNull(
            readerLookupPopupAppAssetRoute(
                scheme = "https",
                host = "example.com",
                path = "/fonts/Missing.ttf",
            ),
        )
    }

    @Test
    fun popupAssetContentIncludesLanguageUtilities() {
        val response = lookupPopupAssetResponse(
            name = "language-ja.js",
            assets = LookupPopupAssets(
                popupJs = "",
                popupCss = "",
                languageJapaneseJs = "window.hoshiLanguageUtilities = {};",
            ),
        )

        assertEquals("application/javascript", response?.mimeType)
        assertEquals("window.hoshiLanguageUtilities = {};", response?.content)
    }

    @Test
    fun popupAssetContentIncludesReducedMotionGestures() {
        val response = lookupPopupAssetResponse(
            name = "popup-gestures.js",
            assets = LookupPopupAssets(
                popupJs = "",
                popupCss = "",
                popupGesturesJs = "window.hoshiPopupGesturesLoaded = true;",
            ),
        )

        assertEquals("application/javascript", response?.mimeType)
        assertEquals("window.hoshiPopupGesturesLoaded = true;", response?.content)
    }
}
