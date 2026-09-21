package moe.antimony.hoshi.features.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException

class RemoteWordAudioRepositoryTest {
    private val mp3 = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 0, 0)
    private val jpodBase = "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?"

    @Test fun jpodBuildsEncodedUrlsAndValidatesAudio() = runBlocking {
        val cases = listOf(
            Triple("食べる", "たべる", "kanji=%E9%A3%9F%E3%81%B9%E3%82%8B&kana=%E3%81%9F%E3%81%B9%E3%82%8B"),
            Triple("カレー", "カレー", "kana=%E3%82%AB%E3%83%AC%E3%83%BC"),
            Triple("かな", "かな", "kana=%E3%81%8B%E3%81%AA"),
            Triple("食べる", "", "kanji=%E9%A3%9F%E3%81%B9%E3%82%8B"),
            Triple("A +&", "あ", "kanji=A+%2B%26&kana=%E3%81%82"),
        )
        for ((term, reading, query) in cases) {
            val repo = repository { url, form ->
                assertEquals(jpodBase + query, url)
                assertNull(form)
                AudioHttpResponse(url, 200, "audio/mpeg", mp3)
            }
            assertEquals(listOf(WordAudioCandidate(jpodBase + query)), repo.resolve(BuiltInAudioSource.JapanesePod101, term, reading))
        }
    }

    @Test fun jpodRejectsMissingAndInvalidAudio() = runBlocking {
        val responses = listOf(
            AudioHttpResponse("https://example.com", 404, "audio/mpeg", mp3),
            AudioHttpResponse("https://example.com", 200, "audio/mpeg", byteArrayOf()),
            AudioHttpResponse("https://example.com", 200, "text/html", "<html>error</html>".toByteArray()),
            AudioHttpResponse("https://example.com", 200, "audio/mpeg", "<html>error</html>".toByteArray()),
        )
        for (response in responses) {
            assertTrue(repository { _, _ -> response }.resolve(BuiltInAudioSource.JapanesePod101, "猫", "ねこ").isEmpty())
        }
        val placeholder = RemoteWordAudioRepository(
            AudioHttpClient { url, _ -> AudioHttpResponse(url, 200, "audio/mpeg", mp3) },
            Dispatchers.Unconfined,
            digest = { "ae6398b5a27bc8c0a771df6c907ade794be15518174773c58c7c7ddd17098906" },
        )
        assertTrue(placeholder.resolve(BuiltInAudioSource.JapanesePod101, "猫", "ねこ").isEmpty())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", audioSha256("abc".toByteArray()))
    }

    @Test fun languagePodPostsExactQueryFiltersReadingsAndResolvesAgainstFinalUrl() = runBlocking {
        val repo = repository { url, form ->
            assertEquals("https://www.japanesepod101.com/learningcenter/reference/dictionary_post", url)
            assertEquals(mapOf("post" to "dictionary_reference", "match_type" to "exact", "search_query" to "食べる", "vulgar" to "true"), form)
            html("""
                <div class="dc-result-row"><span class="dc-vocab_kana">違う</span><audio><source src="wrong.mp3"></audio></div>
                <div class="dc-result-row"><audio><source src="missing-reading.mp3"></audio></div>
                <div class="dc-result-row"><span class="dc-vocab_kana">たべる</span></div>
                <div class="dc-result-row"><span class="dc-vocab_kana">たべる</span><audio><source src="../a.mp3"></audio></div>
                <div class="dc-result-row"><span class="dc-vocab_kana">たべる</span><audio><source src="../a.mp3"></audio></div>
                <div class="dc-result-row"><span class="dc-vocab_kana">たべる</span><audio><source src="//cdn.example/b.mp3"></audio></div>
                <div class="dc-result-row"><span class="dc-vocab_kana">たべる</span><audio><source src="data:audio/mp3;base64,x"></audio></div>
                <div class="dc-result-row"><span class="dc-vocab_kana">たべる</span><audio><source src=""></audio></div>
            """)
        }
        assertEquals(listOf("https://example.com/a.mp3", "https://cdn.example/b.mp3"), repo.resolve(BuiltInAudioSource.LanguagePod101, "食べる", "たべる").map { it.url })
    }

    @Test fun languagePodAcceptsDifferentReadingWhenQueryHasNoSeparateReading() = runBlocking {
        val repo = repository { _, _ -> html("""<div class="dc-result-row"><span class="dc-vocab_kana">たべる</span><audio><source src="a.mp3"></audio></div>""") }
        assertEquals(listOf(WordAudioCandidate("https://example.com/redirect/a.mp3")), repo.resolve(BuiltInAudioSource.LanguagePod101, "食べる", "食べる"))
    }

    @Test fun jishoFindsExactIdWithoutTreatingColonAsSelectorAndEncodesPath() = runBlocking {
        val repo = repository { url, form ->
            assertEquals("https://jisho.org/search/%E9%A3%9F%20%2B%26%2F", url)
            assertNull(form)
            html("""<audio id="audio_食 +&amp;/:たべる"><source src="//cdn.example/a.mp3"></audio><audio id="audio_other:たべる"><source src="wrong.mp3"></audio>""")
        }
        assertEquals(listOf(WordAudioCandidate("https://cdn.example/a.mp3")), repo.resolve(BuiltInAudioSource.Jisho, "食 +&/", "たべる"))
    }

    @Test fun jishoHandlesRelativeMissingAndUnsupportedSources() = runBlocking {
        for ((body, expected) in listOf(
            """<audio id="audio_猫:ねこ"><source src="../cat.mp3"></audio>""" to listOf(WordAudioCandidate("https://example.com/cat.mp3")),
            """<audio id="audio_犬:いぬ"><source src="dog.mp3"></audio>""" to emptyList(),
            """<audio id="audio_猫:ねこ"></audio>""" to emptyList(),
            """<audio id="audio_猫:ねこ"><source src="javascript:alert(1)"></audio>""" to emptyList(),
        )) {
            assertEquals(expected, repository { _, _ -> html(body) }.resolve(BuiltInAudioSource.Jisho, "猫", "ねこ"))
        }
    }

    @Test fun networkFailuresAreEmptyButCancellationPropagates() = runBlocking {
        for (source in BuiltInAudioSource.entries) {
            assertTrue(repository { _, _ -> throw SocketTimeoutException() }.resolve(source, "猫", "ねこ").isEmpty())
            try {
                repository { _, _ -> throw CancellationException("cancel") }.resolve(source, "猫", "ねこ")
                fail("Cancellation must propagate")
            } catch (_: CancellationException) { }
        }
    }

    private fun html(body: String) = AudioHttpResponse("https://example.com/redirect/page", 200, "text/html; charset=UTF-8", body.toByteArray())
    private fun repository(request: (String, Map<String, String>?) -> AudioHttpResponse) =
        RemoteWordAudioRepository(AudioHttpClient(request), Dispatchers.Unconfined)
}
