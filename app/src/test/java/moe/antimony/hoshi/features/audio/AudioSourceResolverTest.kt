package moe.antimony.hoshi.features.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioSourceResolverTest {
    @Test
    fun remoteTemplateReplacesTermAndReadingWithUrlEncoding() {
        val url = AudioSourceResolver.expandTemplate(
            "https://example.test/audio/list?term={term}&reading={reading}",
            term = "食べる",
            reading = "たべ る",
        )

        assertEquals(
            "https://example.test/audio/list?term=%E9%A3%9F%E3%81%B9%E3%82%8B&reading=%E3%81%9F%E3%81%B9%20%E3%82%8B",
            url,
        )
    }

    @Test
    fun localAudioMatchesReadingBeforeSourcePriority() {
        val match = LocalAudioResolver.resolve(
            term = "食べる",
            reading = "たべる",
            rows = listOf(
                LocalAudioEntry(source = "nhk16", expression = "食べる", reading = "たべない", file = "audio/wrong.mp3"),
                LocalAudioEntry(source = "forvo", expression = "食べる", reading = "たべる", file = "audio/right.mp3"),
            ),
        )

        assertEquals(LocalAudioEntry(source = "forvo", expression = "食べる", reading = "たべる", file = "audio/right.mp3"), match)
    }

    @Test
    fun localAudioPrefersExactTermAndReadingBeforeReadingFallbackAcrossSources() {
        val match = LocalAudioResolver.resolve(
            term = "食べる",
            reading = "たべる",
            sourceOrder = listOf("forvo", "nhk16"),
            rows = listOf(
                LocalAudioEntry(source = "forvo", expression = "食べない", reading = "たべる", file = "audio/reading.mp3"),
                LocalAudioEntry(source = "nhk16", expression = "食べる", reading = "たべる", file = "audio/exact.mp3"),
            ),
        )

        assertEquals(LocalAudioEntry(source = "nhk16", expression = "食べる", reading = "たべる", file = "audio/exact.mp3"), match)
    }

    @Test
    fun localAudioFallsBackToDefaultSourceOrder() {
        val match = LocalAudioResolver.resolve(
            term = "お冷や",
            reading = "",
            rows = listOf(
                LocalAudioEntry(source = "forvo", expression = "お冷や", reading = "おひや", file = "audio/forvo.mp3"),
                LocalAudioEntry(source = "nhk16", expression = "お冷や", reading = "おひや", file = "audio/nhk.mp3"),
            ),
        )

        assertEquals(LocalAudioEntry(source = "nhk16", expression = "お冷や", reading = "おひや", file = "audio/nhk.mp3"), match)
    }

    @Test
    fun localAudioUsesCustomSourceOrderWithinSameReadingPriority() {
        val match = LocalAudioResolver.resolve(
            term = "食べる",
            reading = "たべる",
            sourceOrder = listOf("forvo", "nhk16"),
            rows = listOf(
                LocalAudioEntry(source = "nhk16", expression = "食べる", reading = "たべる", file = "audio/nhk.mp3"),
                LocalAudioEntry(source = "forvo", expression = "食べる", reading = "たべる", file = "audio/forvo.mp3"),
            ),
        )

        assertEquals(LocalAudioEntry(source = "forvo", expression = "食べる", reading = "たべる", file = "audio/forvo.mp3"), match)
    }

    @Test
    fun localAudioUrlRoundTripsSourceAndFile() {
        val url = LocalAudioResolver.audioUrl(source = "nhk16", file = "audio/20180222111121.opus")

        assertEquals("hoshi-local-audio://nhk16/audio%2F20180222111121.opus", url)
        assertEquals(
            LocalAudioFile(source = "nhk16", file = "audio/20180222111121.opus"),
            LocalAudioResolver.parseAudioUrl(url),
        )
    }

    @Test
    fun localAudioMatchesOpusRows() {
        val match = LocalAudioResolver.resolve(
            term = "食べる",
            reading = "たべる",
            rows = listOf(LocalAudioEntry(source = "nhk16", expression = "食べる", reading = "たべる", file = "audio/a.opus")),
        )

        assertEquals(LocalAudioEntry(source = "nhk16", expression = "食べる", reading = "たべる", file = "audio/a.opus"), match)
    }

    @Test
    fun localAudioIgnoresUnsupportedRows() {
        val match = LocalAudioResolver.resolve(
            term = "食べる",
            reading = "たべる",
            rows = listOf(LocalAudioEntry(source = "nhk16", expression = "食べる", reading = "たべる", file = "audio/a.wav")),
        )

        assertNull(match)
    }
}
