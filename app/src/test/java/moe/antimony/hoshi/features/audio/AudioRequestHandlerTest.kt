package moe.antimony.hoshi.features.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import java.net.URLEncoder
import java.nio.file.Files

class AudioRequestHandlerTest {
    private val remoteRepository = RemoteWordAudioRepository(
        AudioHttpClient { url, _ ->
            if (url.contains("audiomp3.php")) {
                AudioHttpResponse(url, 200, "audio/mpeg", byteArrayOf(0x49, 0x44, 0x33))
            } else {
                AudioHttpResponse(url, 200, "text/html", """
                    <div class="dc-result-row"><span class="dc-vocab_kana">たべる</span><audio id="audio_食べる:たべる"><source src="https://example.com/eat.mp3"></audio></div>
                """.toByteArray())
            }
        },
        Dispatchers.Unconfined,
    )

    @Test
    fun builtInSourcesResolveToPlayableUrlsWithoutCallingCustomJsonFetcher() {
        val handler = AudioRequestHandler(
            localAudioRepository = LocalAudioRepository(Files.createTempDirectory("hoshi-audio-request").toFile()),
            remoteAudioRepository = remoteRepository,
            fetchRemoteAudioList = { error("Built-in sources must not use the custom JSON fetcher") },
        )
        for ((id, expected) in listOf(
            "jpod101" to "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kanji=%E9%A3%9F%E3%81%B9%E3%82%8B&kana=%E3%81%9F%E3%81%B9%E3%82%8B",
            "language-pod-101" to "https://example.com/eat.mp3",
            "jisho" to "https://example.com/eat.mp3",
        )) {
            val target = "hoshi-builtin-audio-source://$id/?term=食べる&reading=たべる"
            val body = handler.handleAudioRequestBody("https://appassets.androidplatform.net/audio?url=${target.urlEncodeForQuery()}")
            val json = kotlinx.serialization.json.Json.parseToJsonElement(body!!.toString(Charsets.UTF_8))
            val expectedJson = kotlinx.serialization.json.Json.parseToJsonElement("""{"type":"audioSourceList","audioSources":[{"name":"","url":"$expected"}]}""")
            assertEquals(expectedJson, json)
        }
    }

    @Test
    fun unknownBuiltInSourceReturnsEmptyListWithoutRemoteFetch() {
        val handler = AudioRequestHandler(
            localAudioRepository = LocalAudioRepository(Files.createTempDirectory("hoshi-audio-request").toFile()),
            remoteAudioRepository = remoteRepository,
            fetchRemoteAudioList = { error("Unknown internal source must not be fetched") },
        )
        val target = "hoshi-builtin-audio-source://unknown/?term=食べる&reading=たべる"
        assertEquals("""{"type":"audioSourceList","audioSources":[]}""",
            handler.handleAudioRequestBody("audio://?url=${target.urlEncodeForQuery()}")?.toString(Charsets.UTF_8))
    }

    @Test
    fun builtInLocalAudioResponseReturnsEveryNamedCandidate() {
        val filesDir = Files.createTempDirectory("hoshi-audio-request").toFile()
        val handler = AudioRequestHandler(
            localAudioRepository = LocalAudioRepository(filesDir),
            remoteAudioRepository = remoteRepository,
            findLocalAudioCandidates = { term, reading ->
                assertEquals("食べる", term)
                assertEquals("たべる", reading)
                listOf(
                    LocalAudioCandidate("NHK16 1", "hoshi-local-audio://nhk16/audio%2Fexact.opus"),
                    LocalAudioCandidate("Forvo (Alice) (食べない)", "hoshi-local-audio://forvo/audio%2Freading.mp3"),
                )
            },
        )
        val target = "hoshi-local-audio-source://get/?term=%E9%A3%9F%E3%81%B9%E3%82%8B&reading=%E3%81%9F%E3%81%B9%E3%82%8B"

        val body = handler.handleAudioRequestBody("https://appassets.androidplatform.net/audio?url=${target.urlEncodeForQuery()}")

        assertEquals(
            """{"type":"audioSourceList","audioSources":[{"name":"NHK16 1","url":"hoshi-local-audio://nhk16/audio%2Fexact.opus"},{"name":"Forvo (Alice) (食べない)","url":"hoshi-local-audio://forvo/audio%2Freading.mp3"}]}""",
            body?.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun ankiconnectAndroidLocalAudioUrlIsFetchedAsExternalJsonSource() {
        val filesDir = Files.createTempDirectory("hoshi-audio-request").toFile()
        var fetchedTarget: String? = null
        val handler = AudioRequestHandler(
            localAudioRepository = LocalAudioRepository(filesDir),
            remoteAudioRepository = remoteRepository,
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
            remoteAudioRepository = remoteRepository,
            findLocalAudioCandidates = { term, reading ->
                assertEquals("食べる", term)
                assertEquals("たべる", reading)
                listOf(
                    LocalAudioCandidate(
                        name = "nhk16",
                        url = "hoshi-local-audio://nhk16/audio%2F20170823122755.opus",
                    ),
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
            remoteAudioRepository = remoteRepository,
            findLocalAudioCandidates = { _, _ ->
                LocalAudioResolver.resolveCandidates(
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
            """{"type":"audioSourceList","audioSources":[{"name":"Forvo ()","url":"hoshi-local-audio://forvo/audio%2Fforvo.mp3"},{"name":"NHK16","url":"hoshi-local-audio://nhk16/audio%2Fnhk.mp3"}]}""",
            body?.toString(Charsets.UTF_8),
        )
    }

    private fun String.urlEncodeForQuery(): String =
        URLEncoder.encode(this, Charsets.UTF_8.name())
}
