package moe.antimony.hoshi.features.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioSourceResolverTest {
    @Test
    fun localAudioCandidatesAreRankedNamedAndDeduplicated() {
        val candidates = LocalAudioResolver.resolveCandidates(
            term = "食べる",
            reading = "タベル",
            sourceOrder = listOf("forvo", "nhk16", "custom"),
            rows = listOf(
                LocalAudioEntry(source = "forvo", expression = "食べない", reading = "たべる", file = "audio/reading.mp3", display = "Alice"),
                LocalAudioEntry(source = "nhk16", expression = "食べる", reading = "たべる", file = "audio/exact.opus", display = "1"),
                LocalAudioEntry(source = "nhk16", expression = "食べる", reading = "たべる", file = "audio/exact.opus", display = "duplicate"),
                LocalAudioEntry(source = "custom", expression = "食べる", reading = "たべない", file = "audio/expression.ogg", display = "Speaker"),
            ),
        )

        assertEquals(
            listOf(
                LocalAudioCandidate(
                    name = "NHK16 1",
                    url = "hoshi-local-audio://nhk16/audio%2Fexact.opus",
                ),
                LocalAudioCandidate(
                    name = "Forvo (Alice) (食べない)",
                    url = "hoshi-local-audio://forvo/audio%2Freading.mp3",
                ),
                LocalAudioCandidate(
                    name = "custom (たべない)",
                    url = "hoshi-local-audio://custom/audio%2Fexpression.ogg",
                ),
            ),
            candidates,
        )
    }

    @Test
    fun localAudioCandidatesPreferExactReadingWithinOneSource() {
        val candidates = LocalAudioResolver.resolveCandidates(
            term = "一重",
            reading = "ひとえ",
            rows = listOf(
                LocalAudioEntry("nhk16", "一重", null, "ichijuu.mp3", "イチジュー [0]"),
                LocalAudioEntry("nhk16", "一重", "ひとえ", "hitoe.mp3", "ひト＼エ [2]"),
            ),
        )

        assertEquals(
            listOf("hoshi-local-audio://nhk16/hitoe.mp3", "hoshi-local-audio://nhk16/ichijuu.mp3"),
            candidates.map { it.url },
        )
    }

    @Test
    fun localAudioCandidatesRankIncompleteReadingsBelowKnownMatches() {
        val candidates = LocalAudioResolver.resolveCandidates(
            term = "十八番",
            reading = "おはこ",
            sourceOrder = listOf("nhk16", "daijisen"),
            rows = listOf(
                LocalAudioEntry("nhk16", "十八番", null, "juuhachiban.mp3"),
                LocalAudioEntry("daijisen", "十八番", "おはこ", "daijisen-ohako.mp3"),
                LocalAudioEntry("nhk16", "おはこ", "おはこ", "nhk-ohako.mp3"),
            ),
        )

        assertEquals(
            listOf(
                "hoshi-local-audio://daijisen/daijisen-ohako.mp3",
                "hoshi-local-audio://nhk16/nhk-ohako.mp3",
                "hoshi-local-audio://nhk16/juuhachiban.mp3",
            ),
            candidates.map { it.url },
        )
    }

    @Test
    fun localAudioCandidatesHonorHigherPrioritySourceWithoutReading() {
        val candidates = LocalAudioResolver.resolveCandidates(
            term = "一重",
            reading = "ひとえ",
            sourceOrder = listOf("forvo", "nhk16"),
            sourcesWithoutReadings = setOf("forvo"),
            rows = listOf(
                LocalAudioEntry("nhk16", "一重", "ひとえ", "hitoe.mp3"),
                LocalAudioEntry("forvo", "一重", null, "forvo.mp3"),
            ),
        )

        assertEquals(
            listOf("hoshi-local-audio://forvo/forvo.mp3", "hoshi-local-audio://nhk16/hitoe.mp3"),
            candidates.map { it.url },
        )
    }

    @Test
    fun localAudioCandidatesUseDefaultOrderForUnlistedSources() {
        val candidates = LocalAudioResolver.resolveCandidates(
            term = "一重",
            reading = "ひとえ",
            sourceOrder = listOf("custom"),
            sourcesWithoutReadings = setOf("custom", "nhk16"),
            rows = listOf(
                LocalAudioEntry("forvo", "一重", "ひとえ", "forvo.mp3"),
                LocalAudioEntry("nhk16", "一重", null, "nhk16.mp3"),
                LocalAudioEntry("custom", "一重", null, "custom.mp3"),
            ),
        )

        assertEquals(
            listOf(
                "hoshi-local-audio://custom/custom.mp3",
                "hoshi-local-audio://nhk16/nhk16.mp3",
                "hoshi-local-audio://forvo/forvo.mp3",
            ),
            candidates.map { it.url },
        )
    }

    @Test
    fun localAudioCandidatesSkipDisabledAndUnsupportedRows() {
        val candidates = LocalAudioResolver.resolveCandidates(
            term = "猫",
            reading = "ねこ",
            sourceOrder = listOf("nhk16", "forvo"),
            disabledSources = setOf("nhk16"),
            rows = listOf(
                LocalAudioEntry(source = "nhk16", expression = "猫", reading = "ねこ", file = "audio/disabled.mp3"),
                LocalAudioEntry(source = "forvo", expression = "猫", reading = "ねこ", file = "audio/unsupported.wav"),
            ),
        )

        assertEquals(emptyList<LocalAudioCandidate>(), candidates)
    }

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
    fun localAudioSkipsDisabledSources() {
        val match = LocalAudioResolver.resolve(
            term = "食べる",
            reading = "たべる",
            sourceOrder = listOf("nhk16", "forvo"),
            disabledSources = setOf("nhk16"),
            rows = listOf(
                LocalAudioEntry(source = "nhk16", expression = "食べる", reading = "たべる", file = "audio/nhk.mp3"),
                LocalAudioEntry(source = "forvo", expression = "食べる", reading = "たべる", file = "audio/forvo.mp3"),
            ),
        )

        assertEquals(LocalAudioEntry(source = "forvo", expression = "食べる", reading = "たべる", file = "audio/forvo.mp3"), match)
    }

    @Test
    fun localAudioReturnsNoMatchWhenAllSourcesAreDisabled() {
        val match = LocalAudioResolver.resolve(
            term = "食べる",
            reading = "たべる",
            sourceOrder = listOf("nhk16", "forvo"),
            disabledSources = setOf("nhk16", "forvo"),
            rows = listOf(
                LocalAudioEntry(source = "nhk16", expression = "食べる", reading = "たべる", file = "audio/nhk.mp3"),
                LocalAudioEntry(source = "forvo", expression = "食べる", reading = "たべる", file = "audio/forvo.mp3"),
            ),
        )

        assertNull(match)
    }

    @Test
    fun sourceWithoutReadingsRanksSpellingMatchAboveReadingOnlyMatch() {
        val match = LocalAudioResolver.resolve(
            term = "食べる",
            reading = "たべる",
            sourceOrder = listOf("forvo", "nhk16"),
            sourcesWithoutReadings = setOf("nhk16"),
            rows = listOf(
                LocalAudioEntry(source = "forvo", expression = "食べない", reading = "たべる", file = "audio/reading.mp3"),
                LocalAudioEntry(source = "nhk16", expression = "食べる", reading = null, file = "audio/expression.mp3"),
            ),
        )

        assertEquals(LocalAudioEntry(source = "nhk16", expression = "食べる", reading = null, file = "audio/expression.mp3"), match)
    }

    @Test
    fun expressionWithMismatchedReadingRanksBelowReadingMatch() {
        val match = LocalAudioResolver.resolve(
            term = "食べる",
            reading = "たべる",
            sourceOrder = listOf("nhk16", "forvo"),
            rows = listOf(
                LocalAudioEntry(source = "nhk16", expression = "食べる", reading = "たべない", file = "audio/expression.mp3"),
                LocalAudioEntry(source = "forvo", expression = "食べない", reading = "たべる", file = "audio/reading.mp3"),
            ),
        )

        assertEquals(LocalAudioEntry(source = "forvo", expression = "食べない", reading = "たべる", file = "audio/reading.mp3"), match)
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
