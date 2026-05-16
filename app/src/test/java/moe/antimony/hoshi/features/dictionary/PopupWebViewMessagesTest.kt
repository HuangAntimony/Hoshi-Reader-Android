package moe.antimony.hoshi.features.dictionary

import org.junit.Assert.assertTrue
import org.junit.Test

class PopupWebViewMessagesTest {

    @Test
    fun dictionaryImageMimeTypeMatchesIosImageHandler() {
        assertTrue(dictionaryImageMimeType("icons/arrow.svg") == "image/svg+xml")
        assertTrue(dictionaryImageMimeType("photo.PNG") == "image/png")
        assertTrue(dictionaryImageMimeType("image.jpeg") == "image/jpeg")
        assertTrue(dictionaryImageMimeType("unknown.bin") == "application/octet-stream")
    }
}
