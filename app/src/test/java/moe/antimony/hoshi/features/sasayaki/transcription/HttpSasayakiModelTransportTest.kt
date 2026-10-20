package moe.antimony.hoshi.features.sasayaki.transcription

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HttpSasayakiModelTransportTest {
    @Test fun rangeRequestReturnsValidatedOffsetAndClosesConnection() = runBlocking {
        val connection = Connection(206, "bc", "bytes 1-2/3")
        val response = HttpSasayakiModelTransport { connection }.open(connection.url.toString(), 1)
        assertEquals("bytes=1-", connection.getRequestProperty("Range"))
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertFalse(connection.useCaches)
        assertEquals(1L, response.offset)
        assertEquals(3L, response.totalLength)
        assertEquals(2L, response.length)
        assertEquals("bc", response.input.bufferedReader().readText())
        response.close()
        assertTrue(connection.disconnected)
    }

    @Test fun ignoredRangeReturnsZeroOffsetForAFullRestart() = runBlocking {
        val connection = Connection(200, "abc")
        HttpSasayakiModelTransport { connection }.open(connection.url.toString(), 1).use { response ->
            assertEquals(0L, response.offset)
            assertEquals(3L, response.totalLength)
            assertEquals("abc", response.input.bufferedReader().readText())
        }
    }

    @Test fun invalidRangesAreRejectedBeforeOpeningTheBody() = runBlocking {
        for (header in listOf(null, "bytes 0-1/3", "bytes 1-3/3", "bytes 2-1/3", "bytes 1-2/*",
            "bytes 1-2/99999999999999999999999", "bytes 1-1/3", "bytes 1-2/4")) {
            val connection = Connection(206, "bc", header)
            try {
                HttpSasayakiModelTransport { connection }.open(connection.url.toString(), 1)
                fail("Accepted Content-Range: $header")
            } catch (error: SasayakiDownloadValidationException) {
                assertTrue(error.message!!, error.message!!.contains(header.orEmpty()))
            }
            assertFalse(connection.bodyOpened)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun httpFailureRetainsTheActualStatusAndRetryClassification() = runBlocking {
        for (status in listOf(404, 408, 429, 503)) {
            val connection = Connection(status, "")
            try {
                HttpSasayakiModelTransport { connection }.open(connection.url.toString(), 0)
                fail("Expected HTTP failure")
            } catch (error: SasayakiHttpException) {
                assertEquals(status, error.status)
                assertEquals(status != 404, error.retryable)
                assertTrue(error.message!!.contains("HTTP $status"))
            }
            assertTrue(connection.disconnected)
        }
    }

    private class Connection(
        private val status: Int,
        private val body: String,
        private val range: String? = null,
    ) : HttpURLConnection(URL("https://example.org/model")) {
        var disconnected = false
        var bodyOpened = false
        override fun getResponseCode() = status
        override fun getResponseMessage() = "server response"
        override fun getContentLengthLong() = body.toByteArray().size.toLong()
        override fun getHeaderField(name: String?) = if (name == "Content-Range") range else null
        override fun getInputStream() = body.byteInputStream().also { bodyOpened = true }
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
    }
}
