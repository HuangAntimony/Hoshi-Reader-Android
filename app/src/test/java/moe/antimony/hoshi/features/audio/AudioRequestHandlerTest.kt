package moe.antimony.hoshi.features.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLEncoder
import java.nio.file.Files

class AudioRequestHandlerTest {
    @Test
    fun ankiconnectAndroidLocalAudioUrlIsFetchedAsExternalJsonSource() {
        val filesDir = Files.createTempDirectory("hoshi-audio-request").toFile()
        var fetchedTarget: String? = null
        val handler = AudioRequestHandler(
            localAudioRepository = LocalAudioRepository(filesDir),
            fetchRemoteAudioList = { target ->
                fetchedTarget = target
                """{"type":"audioSourceList","audioSources":[{"name":"nhk16","url":"http://localhost:8765/localaudio/nhk16/yomu.mp3"}]}""".toByteArray()
            },
        )
        val target = "http://localhost:8765/localaudio/get/?term=%E8%AA%AD%E3%82%80&reading=%E3%82%88%E3%82%80"

        val body = handler.handleAudioRequestBody("https://appassets.androidplatform.net/audio?url=${target.urlEncodeForQuery()}")

        assertEquals(target, fetchedTarget)
        assertEquals(
            """{"type":"audioSourceList","audioSources":[{"name":"nhk16","url":"http://localhost:8765/localaudio/nhk16/yomu.mp3"}]}""",
            body?.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun builtInLocalAudioResponseCanReturnOpusUrl() {
        val filesDir = Files.createTempDirectory("hoshi-audio-request").toFile()
        val handler = AudioRequestHandler(
            localAudioRepository = LocalAudioRepository(filesDir),
            findLocalAudio = { term, reading ->
                assertEquals("食べる", term)
                assertEquals("たべる", reading)
                LocalAudioEntry(
                    source = "nhk16",
                    expression = "食べる",
                    reading = "たべる",
                    file = "audio/20170823122755.opus",
                )
            },
        )
        val target = "hoshi-local-audio-source://get/?term=%E9%A3%9F%E3%81%B9%E3%82%8B&reading=%E3%81%9F%E3%81%B9%E3%82%8B"

        val body = handler.handleAudioRequestBody("https://appassets.androidplatform.net/audio?url=${target.urlEncodeForQuery()}")

        assertEquals(
            """{"type":"audioSourceList","audioSources":[{"name":"nhk16","url":"hoshi-local-audio://nhk16/audio%2F20170823122755.opus"}]}""",
            body?.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun builtInLocalAudioResponseUsesConfiguredSourceOrderFromRepository() {
        val filesDir = Files.createTempDirectory("hoshi-audio-request").toFile()
        val handler = AudioRequestHandler(
            localAudioRepository = LocalAudioRepository(filesDir),
            findLocalAudio = { _, _ ->
                LocalAudioResolver.resolve(
                    term = "食べる",
                    reading = "たべる",
                    sourceOrder = listOf("forvo", "nhk16"),
                    rows = listOf(
                        LocalAudioEntry(source = "nhk16", expression = "食べる", reading = "たべる", file = "audio/nhk.mp3"),
                        LocalAudioEntry(source = "forvo", expression = "食べる", reading = "たべる", file = "audio/forvo.mp3"),
                    ),
                )
            },
        )
        val target = "hoshi-local-audio-source://get/?term=%E9%A3%9F%E3%81%B9%E3%82%8B&reading=%E3%81%9F%E3%81%B9%E3%82%8B"

        val body = handler.handleAudioRequestBody("https://appassets.androidplatform.net/audio?url=${target.urlEncodeForQuery()}")

        assertEquals(
            """{"type":"audioSourceList","audioSources":[{"name":"forvo","url":"hoshi-local-audio://forvo/audio%2Fforvo.mp3"}]}""",
            body?.toString(Charsets.UTF_8),
        )
    }

    private fun String.urlEncodeForQuery(): String =
        URLEncoder.encode(this, Charsets.UTF_8.name())
}
