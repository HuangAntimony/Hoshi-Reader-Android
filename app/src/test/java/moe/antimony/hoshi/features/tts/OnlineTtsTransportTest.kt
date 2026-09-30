package moe.antimony.hoshi.features.tts

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

class OnlineTtsTransportTest {
    private fun client() = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()

    @Test fun requestTimeoutIsReportedSeparatelyFromNetworkFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("late").setBodyDelay(500, TimeUnit.MILLISECONDS))
            val http = OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build()
            try {
                OnlineTtsTransport(http).request(Request.Builder().url(server.url("/slow")).build())
                fail("Expected timeout")
            } catch (error: TtsException) { assertEquals(TtsFailure.Timeout, error.failure) }
        }
    }

    @Test fun mapsServiceFailuresWithoutLeakingResponseOrCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("private key rejected"))
            val transport = OnlineTtsTransport(client())
            try {
                transport.request(Request.Builder().url(server.url("/tts")).header("X-Goog-Api-Key", "secret").build())
                fail("Expected failure")
            } catch (error: TtsException) {
                assertEquals(TtsFailure.Authentication, error.failure)
                assertFalse(error.toString().contains("secret"))
                assertFalse(error.toString().contains("private"))
            }
            assertEquals(TtsFailure.RateLimited, OnlineTtsTransport.httpFailure(429))
            assertEquals(TtsFailure.Network, OnlineTtsTransport.httpFailure(503))
        }
    }

    @Test fun cancelledRequestCancelsUnderlyingCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val http = client()
            val transport = OnlineTtsTransport(http)
            val task = launch(Dispatchers.Default) { transport.request(Request.Builder().url(server.url("/slow")).build()) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            task.cancelAndJoin()
            withTimeout(2000) { while (http.dispatcher.runningCallsCount() != 0) delay(10) }
        }
    }

    @Test fun edgeWaitsForTurnEndBeforeReturningAllAudioFrames() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(socket: WebSocket, text: String) {
                    if (text.contains("Path:ssml")) {
                        val header = "Path:audio\r\nContent-Type:audio/mpeg\r\n".toByteArray()
                        for (audio in listOf(byteArrayOf(1, 2), byteArrayOf(3, 4))) {
                            socket.send((byteArrayOf(0, header.size.toByte()) + header + audio).toByteString())
                        }
                        socket.send("Path:turn.end\r\n\r\n{}")
                    }
                }
            }))
            val result = OnlineTtsTransport(client()).edgeAudio(Request.Builder().url(server.url("/edge")).build(), "<speak>日本</speak>")
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), result)
        }
    }

    @Test fun edgeRejectsPrematureCloseInsteadOfReturningPartialAudio() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(socket: WebSocket, response: Response) { socket.close(1000, null) }
            }))
            try {
                OnlineTtsTransport(client()).edgeAudio(Request.Builder().url(server.url("/edge")).build(), "<speak/>")
                fail("Expected incomplete synthesis failure")
            } catch (error: TtsException) { assertEquals(TtsFailure.InvalidResponse, error.failure) }
        }
    }
}
