package moe.antimony.hoshi.features.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AboutGalleryImageTest {
    private class Destination : GalleryImageDestination {
        val bytes = ByteArrayOutputStream()
        var published = false
        var deleted = false
        var publishError: Exception? = null
        override fun create() = "image-id"
        override fun open(id: String): OutputStream = bytes
        override fun publish(id: String) { publishError?.let { throw it }; published = true }
        override fun delete(id: String) { deleted = true }
    }
    @Test fun originalBytesArePublishedOnlyAfterCopy() = runTest {
        val target = Destination()
        saveGalleryImage({ byteArrayOf(1, 3, 7).inputStream() }, target)
        assertArrayEquals(byteArrayOf(1, 3, 7), target.bytes.toByteArray())
        assertTrue(target.published); assertFalse(target.deleted)
    }
    @Test fun failedPublicationRemovesPartialImageAndKeepsOriginalCause() = runTest {
        val target = Destination()
        val failure = IOException("Cannot publish", IOException("Media database unavailable"))
        target.publishError = failure
        try { saveGalleryImage({ byteArrayOf(1).inputStream() }, target); fail("Expected failure") }
        catch (error: IOException) { assertSame(failure, error) }
        assertTrue(target.deleted); assertFalse(target.published)
    }
    @Test fun cancellationRemovesPartialImageWithoutConvertingFailure() = runTest {
        val target = Destination()
        try { saveGalleryImage({ throw CancellationException("Closed") }, target); fail("Expected cancellation") }
        catch (expected: CancellationException) { assertEquals("Closed", expected.message) }
        assertTrue(target.deleted); assertFalse(target.published)
    }
}
