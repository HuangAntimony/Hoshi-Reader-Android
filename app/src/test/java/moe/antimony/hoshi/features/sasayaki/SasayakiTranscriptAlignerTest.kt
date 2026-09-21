package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SasayakiTranscriptAlignerTest {
    @Test
    fun exactSpeechUsesBookSentencesAndTokenTimes() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。私は窓の外を眺めていた。</p>"),
            listOf(token("雨の降る静かな朝だった", 3.0, 7.0), token("私は窓の外を眺めていた", 8.0, 12.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "私は窓の外を眺めていた"), result.matches.map { it.text })
        assertEquals(listOf(0, 11), result.matches.map { it.start })
        assertEquals(listOf(3.0, 8.0), result.matches.map { it.startTime })
        assertEquals(listOf(7.0, 12.0), result.matches.map { it.endTime })
    }

    @Test
    fun completeTranscriptRequiresKnownDurationAndAllowsTrailingSilence() {
        assertFalse(SasayakiTranscript(0.0, 0.0, emptyList()).isComplete)
        assertFalse(SasayakiTranscript(8.0, 10.0, emptyList()).isComplete)
        assertTrue(SasayakiTranscript(8.5, 10.0, emptyList()).isComplete)
    }

    @Test
    fun recoversRecognitionErrorsOnlyBetweenConfirmedTextAnchors() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。彼女は窓辺で手紙を読む。私は駅へ向かって歩いた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("彼女は窓へで手紙を読む", 4.0, 8.0), token("私は駅へ向かって歩いた", 8.0, 12.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "彼女は窓辺で手紙を読む", "私は駅へ向かって歩いた"), result.matches.map { it.text })
        assertEquals(0, result.unmatched)
        assertEquals(4.0, result.matches[1].startTime, 0.001)
        assertEquals(8.0, result.matches[1].endTime, 0.001)
    }

    @Test
    fun missingWordPrefixAfterCommaBelongsToFollowingCue() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>日差しがよく当たるから、冷房も強めに設定してあるのだろう。</p>"),
            listOf(token("日差しがよく当たるから", 475.0, 478.15), token("房も強めに設定してあるのだろう", 478.264, 480.902)),
        )
        assertEquals(listOf("日差しがよく当たるから", "冷房も強めに設定してあるのだろう"), result.matches.map { it.text })
        val (previous, next) = result.matches
        assertEquals(previous.start + previous.length, next.start)
        assertTrue(next.startTime >= previous.endTime && next.startTime <= 478.264)
        assertEquals(480.902, next.endTime, 0.0001)
    }

    @Test
    fun missingWholeShortReplyIsNotInferredFromAdjacentSpeech() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>日差しがよく当たるから。はい。冷房も強めに設定してあるのだろう。</p>"),
            listOf(token("日差しがよく当たるから", 475.0, 478.15), token("冷房も強めに設定してあるのだろう", 478.264, 480.902)),
        )
        assertEquals(listOf("日差しがよく当たるから", "冷房も強めに設定してあるのだろう"), result.matches.map { it.text })
        assertEquals(1, result.unmatched)
    }

    @Test
    fun missingWordPrefixAcrossLongSilenceIsNotInferred() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>日差しがよく当たるから、冷房も強めに設定してあるのだろう。</p>"),
            listOf(token("日差しがよく当たるから", 475.0, 478.15), token("房も強めに設定してあるのだろう", 495.0, 499.0)),
        )
        assertEquals(listOf("日差しがよく当たるから", "房も強めに設定してあるのだろう"), result.matches.map { it.text })
    }

    @Test
    fun rubyReadingsMapBackToBaseTextCodePointOffsets() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>𠮟。<ruby>彼女<rp>（</rp><rt>かのじょ</rt><rp>）</rp></ruby>は窓の外を眺めていた。</p>"),
            listOf(token("カノジョは窓の外を眺めていた", 2.0, 8.0)),
        )
        val match = result.matches.single()
        assertEquals("彼女は窓の外を眺めていた", match.text)
        assertEquals(1, match.start)
        assertEquals(12, match.length)
    }

    @Test
    fun unrelatedOpeningAndUnspokenTailAreNeverInvented() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>この本を手に取った皆様へ。雨の降る静かな朝だった。私は駅へ向かって歩いた。その先には誰も知らない長い物語が待っていた。</p>"),
            listOf(token("制作委員会がお届けします", 0.0, 8.0), token("雨の降る静かな朝だった", 10.0, 14.0), token("私は駅へ向かって歩いた", 15.0, 19.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "私は駅へ向かって歩いた"), result.matches.map { it.text })
        assertEquals(2, result.unmatched)
        assertTrue(result.matches.all { it.startTime >= 10.0 && it.endTime <= 19.0 })
    }

    @Test
    fun partialSentenceEndsAtLastSpokenCharacter() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だったけれど彼女はまだ眠っていた。</p>"),
            listOf(token("雨の降る静かな朝だった", 10.0, 14.0)),
        )
        assertEquals("雨の降る静かな朝だった", result.matches.single().text)
        assertEquals(11, result.matches.single().length)
    }

    @Test
    fun repeatedShortTextCannotCaptureAnotherVolume() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>そうだ。うん。そうだ。うん。</p>", "<p>そうだ。私は駅へ向かって歩いた。うん。彼女は窓の外を眺めていた。</p>"),
            listOf(token("そうだ", 0.0, 1.0), token("私は駅へ向かって歩いた", 2.0, 6.0), token("うん", 7.0, 8.0), token("彼女は窓の外を眺めていた", 9.0, 13.0)),
        )
        assertTrue(result.matches.isNotEmpty())
        assertTrue(result.matches.all { it.chapterIndex == 1 })
        assertTrue(result.matches.any { it.text == "うん" })
    }

    @Test
    fun chapterBoundariesNeverMergeAndKeepOriginalBookIndices() {
        val source = book("<p>ここは目次の文章です。</p>", "<p>雨の降る静かな朝だった。</p>", "<p>彼女は窓の外を眺めていた。</p>")
        val filteredBook = source.copy(chapters = source.chapters.mapIndexed { index, chapter ->
            if (index == 0) chapter.copy(href = "Text/TOC.xhtml") else chapter
        })
        val result = SasayakiTranscriptAligner.align(filteredBook, listOf(token("雨の降る静かな朝だった彼女は窓の外を眺めていた", 0.0, 10.0)))
        assertEquals(listOf(1, 2), result.matches.map { it.chapterIndex })
        assertEquals(listOf(0, 0), result.matches.map { it.start })
        assertEquals(listOf(11, 12), result.matches.map { it.length })
    }

    @Test
    fun emptySilenceAndInvalidTokenTimesProduceNoMatch() {
        val source = book("<p>雨の降る静かな朝だった。</p>")
        val invalid = listOf(
            token("雨の降る静かな朝だった", Double.NaN, 3.0),
            token("雨の降る静かな朝だった", 0.0, Double.POSITIVE_INFINITY),
            token("雨の降る静かな朝だった", -1.0, 4.0),
            token("雨の降る静かな朝だった", 8.0, 7.0),
            token("雨の降る静かな朝だった", 2.0, 2.0),
            token("…！？", 0.0, 3.0),
        )
        assertTrue(SasayakiTranscriptAligner.align(source, emptyList()).matches.isEmpty())
        assertTrue(SasayakiTranscriptAligner.align(source, invalid).matches.isEmpty())
    }

    @Test
    fun doesNotFillLargeOmittedPassageBetweenRealAnchors() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。</p><p>${"黙って読まない文章。".repeat(60)}</p><p>彼女は窓の外を眺めていた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("彼女は窓の外を眺めていた", 5.0, 9.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "彼女は窓の外を眺めていた"), result.matches.map { it.text })
        assertEquals(60, result.unmatched)
    }

    @Test
    fun preservesBothAnchorsWithoutHighlightingUnsupportedHoleInsideOneSentence() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった朗読では省略する箇所彼女は窓の外を眺めていた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("彼女は窓の外を眺めていた", 5.0, 9.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "彼女は窓の外を眺めていた"), result.matches.map { it.text })
    }

    @Test
    fun supplementaryCodePointsAndWidthNormalizationPreserveStoredOffsets() {
        val result = SasayakiTranscriptAligner.align(
            book("<p><img src='illustration.png' alt='画像の代替テキスト'>𠮟られても彼女はＡＢＣを読み続けた。</p>"),
            listOf(token("𠮟られても彼女はabcを読み続けた", 1.0, 5.0)),
        )
        val match = result.matches.single()
        assertEquals(0, match.start)
        assertEquals(17, match.length)
        assertEquals("𠮟られても彼女はＡＢＣを読み続けた", match.text)
    }

    @Test
    fun isolatedRepeatedShortRepliesStayUnmatched() {
        assertTrue(SasayakiTranscriptAligner.align(
            book("<p>そうだ。うん。そうだ。うん。</p>"),
            listOf(token("うん", 1.0, 2.0), token("そうだ", 3.0, 4.0)),
        ).matches.isEmpty())
    }

    @Test
    fun boundedKanaRewriteRecoversWithoutMovingRealAnchors() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。多分。彼女は窓の外を眺めていた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("たぶん", 4.5, 5.0), token("彼女は窓の外を眺めていた", 5.5, 9.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "多分", "彼女は窓の外を眺めていた"), result.matches.map { it.text })
        assertEquals(listOf(1.0, 4.5, 5.5), result.matches.map { it.startTime })
        assertEquals(listOf(4.0, 5.0, 9.0), result.matches.map { it.endTime })
    }

    @Test
    fun boundedRecoveryRejectsUnrelatedSpeechAndLongSilence() {
        val source = book("<p>雨の降る静かな朝だった。多分。彼女は窓の外を眺めていた。</p>")
        val unrelated = SasayakiTranscriptAligner.align(source,
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("音楽", 4.5, 5.0), token("彼女は窓の外を眺めていた", 5.5, 9.0)),
        )
        val delayed = SasayakiTranscriptAligner.align(source,
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("たぶん", 12.0, 13.0), token("彼女は窓の外を眺めていた", 15.0, 19.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "彼女は窓の外を眺めていた"), unrelated.matches.map { it.text })
        assertEquals(listOf("雨の降る静かな朝だった", "彼女は窓の外を眺めていた"), delayed.matches.map { it.text })
    }

    @Test
    fun proportionalRecoveryCannotBorrowPreviousSentenceAudioForOmittedReply() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。まったくなさそうだ。くっそう。彼女は窓の外を眺めていた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("全くなさそうだ", 4.5, 6.0), token("彼女は窓の外を眺めていた", 8.0, 11.0)),
        )
        assertFalse(result.matches.any { "くっそう" in it.text })
        assertEquals("雨の降る静かな朝だった", result.matches.first().text)
        assertEquals("彼女は窓の外を眺めていた", result.matches.last().text)
    }

    @Test
    fun voicedHalfWidthKanaNormalizesAcrossDakutenWithoutShiftingOffsets() {
        val source = book("<p>𠮟。ｶﾞﾗｽの窓からｷﾞﾝｺｳを眺めていた。</p>")
        val match = SasayakiTranscriptAligner.align(source,
            listOf(token("ガラスの窓からギンコウを眺めていた", 1.0, 5.0)),
        ).matches.single()
        assertEquals(1, match.start)
        assertEquals(17, match.length)
        assertEquals("ｶﾗｽの窓からｷﾝｺｳを眺めていた", match.text)
        val reverse = SasayakiTranscriptAligner.align(
            book("<p>ガラスの窓からギンコウを眺めていた。</p>"),
            listOf(token("ｶﾞﾗｽの窓からｷﾞﾝｺｳを眺めていた", 1.0, 5.0)),
        )
        assertEquals("ガラスの窓からギンコウを眺めていた", reverse.matches.single().text)
    }

    private fun book(vararg html: String) = EpubBook(
        title = "Generated alignment fixture",
        chapters = html.mapIndexed { index, content ->
            EpubChapter("chapter-$index", "chapter-$index.xhtml", "application/xhtml+xml", "<body>$content</body>")
        },
    )

    private fun token(text: String, start: Double, end: Double) = SasayakiToken(text, start, end)
}
