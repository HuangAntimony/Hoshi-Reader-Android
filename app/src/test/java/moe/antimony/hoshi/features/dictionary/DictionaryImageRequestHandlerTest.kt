package moe.antimony.hoshi.features.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryImageRequestHandlerTest {
    @Test
    fun dictionaryImageMimeTypeMatchesIosImageHandler() {
        assertEquals("image/svg+xml", dictionaryImageMimeType("icons/arrow.svg"))
        assertEquals("image/png", dictionaryImageMimeType("photo.PNG"))
        assertEquals("image/jpeg", dictionaryImageMimeType("image.jpeg"))
        assertEquals("application/octet-stream", dictionaryImageMimeType("unknown.bin"))
    }
}
