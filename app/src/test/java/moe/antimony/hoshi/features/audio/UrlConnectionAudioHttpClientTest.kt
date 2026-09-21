package moe.antimony.hoshi.features.audio

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlConnectionAudioHttpClientTest {
    @Test fun postsUtf8FormAndReturnsResponseBody() {
        val received = AtomicReference<List<String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/dictionary") { exchange ->
            received.set(listOf(exchange.requestMethod, exchange.requestHeaders.getFirst("Content-Type"), exchange.requestBody.bufferedReader().use { it.readText() }))
            val body = "<audio>食べる</audio>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/dictionary"
            val response = UrlConnectionAudioHttpClient().request(url, linkedMapOf("search_query" to "食 +&", "vulgar" to "true"))
            assertEquals(listOf("POST", "application/x-www-form-urlencoded; charset=UTF-8", "search_query=%E9%A3%9F+%2B%26&vulgar=true"), received.get())
            assertEquals(200, response.status)
            assertEquals("text/html; charset=UTF-8", response.contentType)
            assertEquals("<audio>食べる</audio>", response.body.toString(Charsets.UTF_8))
        } finally { server.stop(0) }
    }

    @Test fun reportsRedirectedUrlAndHttpFailures() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/final")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/final") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val response = UrlConnectionAudioHttpClient().request("$base/redirect", null)
            assertEquals("$base/final", response.url)
            assertEquals(404, response.status)
            assertEquals(0, response.body.size)
        } finally { server.stop(0) }
    }
}
