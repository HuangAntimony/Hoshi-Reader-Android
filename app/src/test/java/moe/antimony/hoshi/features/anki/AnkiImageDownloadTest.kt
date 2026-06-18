package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnkiImageDownloadTest {
    private val pngBytes = ByteArray(64) { 1 }

    @Test
    fun acceptsImageContentTypeAndNamesByExtensionAndHash() {
        val media = validateAnkiImageBytes(
            url = "https://x/cat.png?sz=1",
            contentType = "image/png",
            data = pngBytes,
            maxBytes = 1_000,
        )
        requireNotNull(media)
        assertEquals("png", media.preferredName.substringAfterLast('.'))
        assertEquals("image/png", media.mimeType)
    }

    @Test
    fun rejectsNonImageContentType() {
        assertNull(validateAnkiImageBytes("https://x/a.png", "text/html", pngBytes, 1_000))
    }

    @Test
    fun rejectsOversizeData() {
        assertNull(validateAnkiImageBytes("https://x/a.png", "image/png", ByteArray(2_000), 1_000))
    }

    @Test
    fun fallsBackToJpgWhenExtensionUnknown() {
        val media = validateAnkiImageBytes("https://x/image", "image/jpeg", pngBytes, 1_000)
        assertEquals("jpg", requireNotNull(media).preferredName.substringAfterLast('.'))
    }
}
