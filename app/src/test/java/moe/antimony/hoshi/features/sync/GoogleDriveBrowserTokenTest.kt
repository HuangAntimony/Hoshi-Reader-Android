package moe.antimony.hoshi.features.sync

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveBrowserTokenTest {
    @Test
    fun postsEncodedParametersAndReadsGoogleTokens() = runBlocking {
        val received = AtomicReference<List<String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/token") { exchange ->
            received.set(listOf(exchange.requestMethod, exchange.requestHeaders.getFirst("Content-Type"), exchange.requestBody.bufferedReader().use { it.readText() }))
            val body = """{"access_token":"access","refresh_token":"refresh","expires_in":3600,"token_type":"Bearer","scope":"drive.file"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val token = requestBrowserToken(mapOf("code" to "a +&=", "code_verifier" to "verifier"), Dispatchers.IO) {
                URL("http://127.0.0.1:${server.address.port}/token").openConnection() as HttpURLConnection
            }
            assertEquals(BrowserTokenResponse("access", 3600, "refresh"), token)
            assertEquals(listOf("POST", "application/x-www-form-urlencoded; charset=UTF-8", "code=a+%2B%26%3D&code_verifier=verifier"), received.get())
            assertFalse(received.get().last().contains("client_secret"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun distinguishesRejectedRefreshFromServerFailureAndRejectsMalformedSuccess() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val responses = mapOf(
            "/invalid" to (400 to """{"error":"invalid_grant"}"""),
            "/unavailable" to (503 to "Service unavailable"),
            "/missing" to (200 to """{"expires_in":3600}"""),
            "/empty" to (200 to """{"access_token":"","expires_in":3600}"""),
        )
        for ((path, response) in responses) {
            server.createContext(path) { exchange ->
                val body = response.second.toByteArray()
                exchange.sendResponseHeaders(response.first, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()
        try {
            for (path in responses.keys) {
                val failure = runCatching {
                    requestBrowserToken(mapOf("refresh_token" to "refresh"), Dispatchers.IO) {
                        URL("http://127.0.0.1:${server.address.port}$path").openConnection() as HttpURLConnection
                    }
                }.exceptionOrNull()
                assertTrue(failure != null)
                if (path == "/invalid") assertEquals("invalid_grant", (failure as BrowserTokenException).error)
                if (path == "/unavailable") assertEquals(null, (failure as BrowserTokenException).error)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun doesNotFollowTokenEndpointRedirects() = runBlocking {
        val redirected = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/token") { exchange ->
            exchange.responseHeaders.add("Location", "/other")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.createContext("/other") { exchange ->
            redirected.incrementAndGet()
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            val failure = runCatching {
                requestBrowserToken(mapOf("refresh_token" to "refresh"), Dispatchers.IO) {
                    URL("http://127.0.0.1:${server.address.port}/token").openConnection() as HttpURLConnection
                }
            }.exceptionOrNull()
            assertTrue(failure is BrowserTokenException)
            assertEquals(0, redirected.get())
        } finally {
            server.stop(0)
        }
    }
}
