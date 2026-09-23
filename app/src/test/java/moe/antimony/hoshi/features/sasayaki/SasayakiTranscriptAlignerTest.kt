package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.SasayakiMatchSource
import moe.antimony.hoshi.epub.EpubChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SasayakiTranscriptAlignerTest {
    @Test fun emptyAlignmentStillRecordsTranscriptionSource() {
        val result = SasayakiTranscriptAligner.align(book("<p>本文</p>"), emptyList())
        assertTrue(result.matches.isEmpty())
        assertEquals(SasayakiMatchSource.Transcription, result.source)
    }

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
    fun recognizedPrefixAfterOmittedReplyUsesItsOwnTokensAfterLongPause() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。ふう。訳がわからないと彼女は言った。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("わけ", 12.0, 12.6),
                token("がわからないと彼女は言った", 12.6, 16.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "訳がわからないと彼女は言った"), result.matches.map { it.text })
        assertEquals(13, result.matches.last().start)
        assertEquals(12.0, result.matches.last().startTime, 0.0001)
        assertEquals(16.0, result.matches.last().endTime, 0.0001)
        assertEquals(1, result.unmatched)
    }

    @Test
    fun shortRewritesAroundLocalExactTextPreserveWordTimesAcrossComma() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。なにを呑気にしてんだよ、山田！彼女は窓の外を眺めていた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("何", 5.0, 5.5),
                token("を", 5.5, 5.7), token("のんき", 5.7, 6.5), token("にしてんだよ", 6.5, 8.0),
                token("矢間", 8.4, 9.0), token("彼女は窓の外を眺めていた", 10.0, 14.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "なにを呑気にしてんだよ", "山田", "彼女は窓の外を眺めていた"), result.matches.map { it.text })
        assertEquals(listOf(1.0, 5.0, 8.4, 10.0), result.matches.map { it.startTime })
        assertEquals(listOf(4.0, 8.0, 9.0, 14.0), result.matches.map { it.endTime })
    }

    @Test
    fun adjacentReadingRewritesAcrossCommaKeepBothCueEdges() {
        val source = book(
            "<p>早く迎えに行かないと、<ruby>華恋<rt>かれん</rt></ruby>ちゃんイギリスに行っちゃうんだよ。それでいいの？</p>" +
                "<p>だけど華恋の奴、俺にサヨナラって──</p><p>そんなの迎えに来てって意味に決まってるじゃない！</p>",
        )
        val tokens = listOf(token("早く迎えに行かないとカレンちゃんイギリスに行っちゃうんだよ", 302.968, 308.73),
            token("だけどかれんの", 308.73, 310.77), token("やつ", 310.77, 311.226),
            token("おれ", 311.226, 312.186), token("にさよならって", 312.186, 314.202),
            token("そんなの迎えに来てって意味に決まってるじゃない", 314.202, 318.234))
        val result = SasayakiTranscriptAligner.align(source, tokens)
        val previous = result.matches.single { it.text == "だけど華恋の奴" }
        val next = result.matches.single { it.text == "俺にサヨナラって" }
        assertEquals(308.73, previous.startTime, 0.0001)
        assertEquals(311.226, previous.endTime, 0.0001)
        assertEquals(311.226, next.startTime, 0.0001)
        assertEquals(314.202, next.endTime, 0.0001)
        assertFalse(result.matches.any { "それでいいの" in it.text })
        val session = SasayakiTranscriptAligner.Session(source)
        tokens.indices.forEach { end ->
            val prefix = tokens.take(end + 1)
            assertEquals(SasayakiTranscriptAligner.align(source, prefix), session.align(prefix))
        }
    }

    @Test
    fun proportionalReadingRewritesCannotShareATokenAcrossComma() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。だけど彼女の奴、俺にサヨナラって言ったんだ。</p>"),
            listOf(token("雨の降る静かな朝だっただけど彼女の", 1.0, 5.0),
                token("つ", 5.0, 5.5), token("おれ", 5.5, 6.5), token("にさよならって言ったんだ", 6.5, 10.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "だけど彼女の奴", "俺にサヨナラって言ったんだ"),
            result.matches.map { it.text })
        assertEquals(5.5, result.matches[1].endTime, 0.0001)
        assertEquals(5.5, result.matches[2].startTime, 0.0001)
        assertTrue(result.matches.zipWithNext().all { (left, right) -> left.endTime <= right.startTime })
    }

    @Test
    fun crossCueReadingRecoveryCannotFillAnUnspokenMiddleReply() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。だけど彼女の奴、はい、俺にサヨナラって言ったんだ。</p>"),
            listOf(token("雨の降る静かな朝だっただけど彼女の", 1.0, 5.0),
                token("やつおれ", 5.0, 6.5), token("にさよならって言ったんだ", 6.5, 10.0)),
        )
        assertFalse(result.matches.any { "はい" in it.text })
    }

    @Test
    fun adjacentReadingRewritesCannotBorrowAcrossASentenceBoundary() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。だけど彼女の奴。俺にサヨナラって言ったんだ。</p>"),
            listOf(token("雨の降る静かな朝だっただけど彼女の", 1.0, 5.0),
                token("やつおれ", 5.0, 6.5), token("にさよならって言ったんだ", 6.5, 10.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "だけど彼女の", "にサヨナラって言ったんだ"),
            result.matches.map { it.text })
    }

    @Test
    fun coincidentalParticleCannotMakeAnOmittedCommaCueRecoverable() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。だけど彼女の奴、はい、俺にサヨナラって言ったんだ。</p>"),
            listOf(token("雨の降る静かな朝だっただけど彼女の", 1.0, 5.0),
                token("これは違う", 5.0, 6.5), token("にさよならって言ったんだ", 6.5, 10.0)),
        )
        assertFalse(result.matches.any { "はい" in it.text })
        assertFalse(result.matches.any { "俺" in it.text })
    }

    @Test
    fun sentenceContextRecoversMixedSpellingTailBesideAnOmittedReply() {
        val source = book(
            "<p>この柔らかそうな<ruby>太<rt>ふと</rt></ruby><ruby>腿<rt>もも</rt></ruby>で" +
                "<ruby>膝<rt>ひざ</rt></ruby><ruby>枕<rt>まくら</rt></ruby>を──</p>" +
                "<p>「駄目だよ草介！こんなところで油売ってる場合じゃないよ！」</p>" +
                "<p>隣のテーブルから聞こえてきた。</p>",
        )
        val tokens = listOf(token("この柔らかそうな太", 223.77, 225.53),
            token("ももで膝枕を", 225.53, 227.066), token("こんなところで油売って", 227.32, 230.49),
            token("隣のテーブルから聞こえてきた", 232.728, 236.282))
        val result = SasayakiTranscriptAligner.align(source, tokens)
        assertEquals(listOf("この柔らかそうな太腿で膝枕を", "こんなところで油売って", "隣のテーブルから聞こえてきた"),
            result.matches.map { it.text })
        assertEquals(223.77, result.matches[0].startTime, 0.0001)
        assertEquals(227.066, result.matches[0].endTime, 0.0001)
        assertEquals(227.32, result.matches[1].startTime, 0.0001)
        val session = SasayakiTranscriptAligner.Session(source)
        tokens.indices.forEach { end ->
            val prefix = tokens.take(end + 1)
            assertEquals(SasayakiTranscriptAligner.align(source, prefix), session.align(prefix))
        }
        assertEquals(result, session.align(tokens))
    }

    @Test
    fun sentenceContextRecoversMixedSpellingPrefixAfterAnOmittedReply() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。</p><p>返事はなかった。</p>" +
                "<p>膝枕にもたれていた彼女は窓の外を眺めていた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("ひざ枕に", 8.0, 9.0),
                token("もたれていた彼女は窓の外を眺めていた", 9.0, 14.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "膝枕にもたれていた彼女は窓の外を眺めていた"),
            result.matches.map { it.text })
        assertEquals(8.0, result.matches.last().startTime, 0.0001)
        assertEquals(14.0, result.matches.last().endTime, 0.0001)
        assertEquals(1, result.unmatched)
    }

    @Test
    fun lowSentenceCoverageKeepsItsRecognizedShortReply() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。</p><p>ううん、いいの。</p><p>彼女は窓の外を眺めていた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("いいの", 5.0, 6.0),
                token("彼女わ窓の外を眺めていた", 7.0, 11.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "いいの", "彼女は窓の外を眺めていた"),
            result.matches.map { it.text })
        assertEquals(5.0, result.matches[1].startTime, 0.0001)
        assertEquals(6.0, result.matches[1].endTime, 0.0001)
    }

    @Test
    fun contextAlignmentCannotMoveAnAnchoredRepeatedCharacterToItsNeighbor() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。</p><p>ああ、先生、早く戻って来てくれないか。</p>" +
                "<p>彼女は窓の外を眺めていた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("あ", 5.0, 6.0), token("先生", 6.0, 7.0),
                token("早く戻ってきてくれないか", 7.0, 11.0), token("彼女は窓の外を眺めていた", 12.0, 16.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "ああ", "先生", "早く戻って来てくれないか", "彼女は窓の外を眺めていた"),
            result.matches.map { it.text })
        assertEquals(5.0, result.matches[1].startTime, 0.0001)
        assertEquals(6.0, result.matches[1].endTime, 0.0001)
        assertTrue(result.matches.zipWithNext().all { (left, right) -> left.endTime <= right.startTime })
    }

    @Test
    fun isolatedParticlesInUnrelatedSpeechDoNotBecomeMatchedText() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。</p><p>彼らは駅で待っていた。</p><p>私は窓の外を眺めていた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("音楽は終了です", 5.0, 8.0),
                token("私は窓の外を眺めていた", 9.0, 13.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "私は窓の外を眺めていた"), result.matches.map { it.text })
    }

    @Test
    fun rejectedSentenceCannotRecoverAnUnrelatedFragmentAsAReadingRewrite() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。</p><p>それは本当だった。</p><p>私は窓の外を眺めていた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("音楽は終了です", 5.0, 7.0),
                token("私は窓の外を眺めていた", 8.0, 12.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "私は窓の外を眺めていた"), result.matches.map { it.text })
    }

    @Test
    fun insufficientSentenceEvidenceCannotInferAnOmittedPrefix() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。</p><p>ううんいいの。</p><p>私は窓の外を眺めていた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("いいです", 5.0, 7.0),
                token("私は窓の外を眺めていた", 8.0, 12.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "私は窓の外を眺めていた"), result.matches.map { it.text })
    }

    @Test
    fun boundedNameSpellingErrorsRetainTheWholeName() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。</p><p>八奈見杏菜。</p><p>私は窓の外を眺めていた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("八波杏奈", 5.0, 7.0),
                token("私は窓の外を眺めていた", 8.0, 12.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "八奈見杏菜", "私は窓の外を眺めていた"), result.matches.map { it.text })
        assertEquals(5.0, result.matches[1].startTime, 0.0001)
        assertEquals(7.0, result.matches[1].endTime, 0.0001)
    }

    @Test
    fun abbreviatedRubyCannotReverseThePinnedTextBounds() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝に<ruby>博士号<rt>D</rt></ruby>を取得した彼女は窓の外を眺めていた。</p>"),
            listOf(token("雨の降る静かな朝に博師号を取得した彼女は窓の外を眺めていた", 1.0, 10.0)),
        )
        assertEquals("雨の降る静かな朝に博士号を取得した彼女は窓の外を眺めていた", result.matches.single().text)
    }

    @Test
    fun editDeletionDoesNotGiveAnOmittedSentenceThePreviousWordsTime() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。彼女は窓辺で手紙を読む。はい。私は駅へ向かって歩いた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("彼女は窓へで手紙を読む", 4.0, 8.0),
                token("私は駅へ向かって歩いた", 9.0, 13.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "彼女は窓辺で手紙を読む", "私は駅へ向かって歩いた"), result.matches.map { it.text })
        assertEquals(1, result.unmatched)
    }

    @Test
    fun editDeletionInsideSpokenWordCanUseAdjacentTokenWithoutSilence() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。ふうん。彼女は窓の外を眺めていた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("ふ", 5.0, 5.3),
                token("ん", 5.3, 5.5), token("彼女は窓の外を眺めていた", 6.0, 10.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "ふうん", "彼女は窓の外を眺めていた"), result.matches.map { it.text })
        assertEquals(5.0, result.matches[1].startTime, .0001)
        assertEquals(5.5, result.matches[1].endTime, .0001)
    }

    @Test
    fun singleKanjiReadingCanSpanThreeKanaTokens() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。私は窓の外を眺めていた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("わたし", 5.0, 5.8),
                token("は窓の外を眺めていた", 5.8, 9.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "私は窓の外を眺めていた"), result.matches.map { it.text })
        assertEquals(5.0, result.matches.last().startTime, .0001)
    }

    @Test
    fun ambiguousReadingCannotMoveAcrossAnOmittedSentenceBoundary() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。音。訳がわからないと彼女は言った。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("おと", 5.0, 5.6),
                token("がわからないと彼女は言った", 5.6, 9.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "がわからないと彼女は言った"), result.matches.map { it.text })
        assertEquals(5.6, result.matches.last().startTime, .0001)
    }

    @Test
    fun repairedRubySyllableRetainsTheExactSyllablesTimeOnSharedBaseCharacter() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。<ruby>花<rt>はな</rt></ruby>を見た男と話す。彼女は窓の外を眺めていた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("は", 5.0, 5.5), token("ま", 5.5, 6.0),
                token("を見た", 6.0, 7.0), token("人", 7.0, 7.5), token("と話す", 7.5, 8.0),
                token("彼女は窓の外を眺めていた", 9.0, 13.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "花を見た男と話す", "彼女は窓の外を眺めていた"), result.matches.map { it.text })
        assertEquals(5.0, result.matches[1].startTime, .0001)
        assertEquals(8.0, result.matches[1].endTime, .0001)
        assertEquals(11, result.matches[1].start)
        assertEquals(8, result.matches[1].length)
    }

    @Test
    fun sparseSentenceStillKeepsItsSupportedEndingWhenMiddleWasNotSpoken() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。このペンションを提供してくれてる。彼女は窓の外を眺めていた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("ここ", 5.0, 5.5),
                token("を提供してくれてる", 5.5, 7.5), token("彼女は窓の外を眺めていた", 8.0, 12.0)),
        )
        assertTrue(result.matches.any { it.text == "を提供してくれてる" && it.startTime == 5.5 && it.endTime == 7.5 })
        assertFalse(result.matches.any { "ペンション" in it.text })
    }

    @Test
    fun boundedRecoveryUsesExplicitTokenDurationAndRejectsUnrelatedSpeech() {
        val source = book("<p>雨の降る静かな朝だった。多分。彼女は窓の外を眺めていた。</p>")
        val unrelated = SasayakiTranscriptAligner.align(source,
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("音楽", 4.5, 5.0), token("彼女は窓の外を眺めていた", 5.5, 9.0)),
        )
        val delayed = SasayakiTranscriptAligner.align(source,
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("たぶん", 12.0, 13.0), token("彼女は窓の外を眺めていた", 15.0, 19.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "彼女は窓の外を眺めていた"), unrelated.matches.map { it.text })
        assertEquals(listOf("雨の降る静かな朝だった", "多分", "彼女は窓の外を眺めていた"), delayed.matches.map { it.text })
        assertEquals(12.0, delayed.matches[1].startTime, 0.0001)
        assertEquals(13.0, delayed.matches[1].endTime, 0.0001)
        val overlong = SasayakiTranscriptAligner.align(source,
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("たぶん", 4.5, 14.0), token("彼女は窓の外を眺めていた", 15.0, 19.0)),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "彼女は窓の外を眺めていた"), overlong.matches.map { it.text })
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

    @Test fun incrementalUpdatesExtendPartialSentenceWithoutDuplicateCues() {
        val book = book("<p>雨の降る静かな朝だったけれど彼女はまだ眠っていた。</p>")
        val session = SasayakiTranscriptAligner.Session(book)
        val first = token("雨の降る静かな朝だった", 10.0, 14.0)
        val second = token("けれど彼女はまだ眠っていた", 14.0, 20.0)
        assertEquals("雨の降る静かな朝だった", session.align(listOf(first)).matches.single().text)
        val complete = session.align(listOf(first, second))
        assertEquals("雨の降る静かな朝だったけれど彼女はまだ眠っていた", complete.matches.single().text)
        assertEquals(20.0, complete.matches.single().endTime, 0.001)
        assertEquals(complete, session.align(listOf(first, second), complete = true))
    }

    @Test fun incrementalNewRightAnchorRepairsEarlierUnmatchedSpeech() {
        val book = book("<p>雨の降る静かな朝だった。彼女は窓辺で手紙を読む。私は駅へ向かって歩いた。</p>")
        val tokens = listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("彼女は窓へで手紙を読む", 4.0, 8.0), token("私は駅へ向かって歩いた", 8.0, 12.0))
        val session = SasayakiTranscriptAligner.Session(book)
        session.align(tokens.take(2))
        val result = session.align(tokens)
        assertEquals(listOf("雨の降る静かな朝だった", "彼女は窓辺で手紙を読む", "私は駅へ向かって歩いた"), result.matches.map { it.text })
        assertEquals(4.0, result.matches[1].startTime, 0.001)
        assertEquals(8.0, result.matches[1].endTime, 0.001)
    }

    @Test fun incrementalTokenBoundariesAndRepeatedTextAgreeWithFreshAlignment() {
        val book = book("<p>そうだ。うん。そうだ。うん。</p>", "<p>そうだ。私は駅へ向かって歩いた。うん。彼女は窓の外を眺めていた。日差しがよく当たるから、冷房も強めに設定してあるのだろう。</p>")
        val text = "そうだ私は駅へ向かって歩いたうん彼女は窓の外を眺めていた日差しがよく当たるから房も強めに設定してあるのだろう"
        val tokens = text.mapIndexed { index, c -> token(c.toString(), index.toDouble(), index + 0.9) }
        for (chunkSize in listOf(1, 3, 8, 13)) {
            val session = SasayakiTranscriptAligner.Session(book)
            for (end in (chunkSize..tokens.size step chunkSize).toList() + tokens.size) {
                val prefix = tokens.take(end)
                assertEquals("chunk=$chunkSize end=$end", SasayakiTranscriptAligner.align(book, prefix), session.align(prefix))
            }
        }
    }

    @Test fun incrementalRewritesAndOmittedRepliesAgreeWithFreshAlignment() {
        val book = book("<p>雨の降る静かな朝だった。ふう。訳がわからないと彼女は言った。なにを呑気にしてんだよ、山田！はい。私は駅へ向かって歩いた。</p>")
        val text = "雨の降る静かな朝だったわけがわからないと彼女は言った何をのんきにしてんだよ矢間私は駅へ向かって歩いた"
        val tokens = text.mapIndexed { i, c -> token(c.toString(), i * .2, (i + 1) * .2) }
        for (chunkSize in listOf(1, 5, 13)) {
            val session = SasayakiTranscriptAligner.Session(book)
            for (end in (chunkSize..tokens.size step chunkSize).toList() + tokens.size) {
                val prefix = tokens.take(end)
                val fresh = SasayakiTranscriptAligner.align(book, prefix)
                assertEquals("chunk=$chunkSize end=$end", fresh, session.align(prefix))
                assertFalse(fresh.matches.any { it.text == "ふう" || it.text == "はい" })
            }
        }
    }

    @Test fun chapterEdgesRepairBoundedSpeechWithoutMergingBookCoordinates() {
        val source = book("<p>雨の降る静かな朝だった。彼女は窓辺にいた。</p>", "<p><img src='page.png'></p>",
            "<p>つまり、それって付き合ってなかったのか。私は駅へ向かって歩いた。</p>")
        val tokens = listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("彼女は窓へにいた", 4.5, 7.0),
            token("つまりそれってつきあってなかったのか", 8.0, 12.0), token("私は駅へ向かって歩いた", 13.0, 17.0))
        val result = SasayakiTranscriptAligner.align(source, tokens)
        assertEquals(listOf("雨の降る静かな朝だった", "彼女は窓辺にいた", "つまり", "それって付き合ってなかったのか", "私は駅へ向かって歩いた"), result.matches.map { it.text })
        assertEquals(listOf(0, 0, 2, 2, 2), result.matches.map { it.chapterIndex })
        assertEquals(listOf(0, 11, 0, 3, 18), result.matches.map { it.start })
        assertTrue(result.matches.zipWithNext().all { (a, b) -> a.endTime <= b.startTime })
        val session = SasayakiTranscriptAligner.Session(source)
        for (end in 1..tokens.size) {
            assertEquals(SasayakiTranscriptAligner.align(source, tokens.take(end)), session.align(tokens.take(end)))
        }
    }

    @Test fun crossChapterRepairCannotInventAnUnspokenChapterOrBookEdges() {
        val source = book("<p>まだ読まれていない序文。雨の降る静かな朝だった。</p>", "<p>はい。</p>",
            "<p>彼女は窓の外を眺めていた。まだ読まれていない続き。</p>")
        val result = SasayakiTranscriptAligner.align(source,
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("彼女は窓の外を眺めていた", 5.0, 9.0)))
        assertEquals(listOf("雨の降る静かな朝だった", "彼女は窓の外を眺めていた"), result.matches.map { it.text })
        assertEquals(listOf(0, 2), result.matches.map { it.chapterIndex })
    }

    @Test fun crossChapterRepairKeepsRubyOffsetsAndDoesNotFillAnOmittedReply() {
        val source = book("<p><ruby>雨<rt>あめ</rt></ruby>の降る静かな朝だった。彼女は窓辺で手紙を読む。</p>",
            "<p>はい。</p><p>手紙には<ruby>故郷<rt>ふるさと</rt></ruby>の風景が記されていた。</p>")
        val tokens = listOf(token("あめの降る静かな朝だった", 1.0, 4.0), token("彼女は窓へで手紙を読む", 5.0, 9.0),
            token("手紙にはふるさとの風景が記されていた", 10.0, 15.0))
        val result = SasayakiTranscriptAligner.align(source, tokens)
        assertEquals(listOf("雨の降る静かな朝だった", "彼女は窓辺で手紙を読む", "手紙には故郷の風景が記されていた"), result.matches.map { it.text })
        assertEquals(listOf(0, 0, 1), result.matches.map { it.chapterIndex })
        assertEquals(listOf(0, 11, 2), result.matches.map { it.start })
        assertEquals(5.0, result.matches[1].startTime, .0001)
        assertEquals(9.0, result.matches[1].endTime, .0001)
        val session = SasayakiTranscriptAligner.Session(source)
        tokens.indices.forEach { end ->
            assertEquals(SasayakiTranscriptAligner.align(source, tokens.take(end + 1)), session.align(tokens.take(end + 1)))
        }
        assertEquals(result, session.align(tokens, complete = true))
    }

    @Test fun wholeKanaReplyIsRecoveredEvenWithAnotherRewriteInTheSameAnchorGap() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。彼女は呟いた。</p><p>謝るな。馬鹿。</p><p>私は駅へ向かって歩いた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("彼女はつぶやいた", 5.0, 7.0),
                token("謝るな", 8.0, 10.0), token("バカ", 11.0, 11.8), token("私は駅へ向かって歩いた", 13.0, 17.0)))
        val reply = result.matches.single { it.text == "馬鹿" }
        assertEquals(11.0, reply.startTime, .0001)
        assertEquals(11.8, reply.endTime, .0001)
    }

    @Test fun wholeCueReadingCannotClaimAnExtraSuffixInsidePreviousSpeech() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。彼女は呟いた。馬鹿。私は駅へ向かって歩いた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("彼女はつぶやいたんだ", 5.0, 8.0),
                token("私は駅へ向かって歩いた", 9.0, 13.0)))
        assertFalse(result.matches.any { it.text == "馬鹿" })
    }

    @Test fun numericRewriteAcrossCommaKeepsSeparateCuesAndTokenTimes() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>小学校に上がる前だから、４、５歳くらいかな。それはノーカンだろ。</p>"),
            listOf(token("小学校に上がる前だから", 1.0, 4.0), token("四", 4.0, 4.2), token("十", 4.2, 4.3),
                token("五", 4.3, 4.5), token("歳くらいかなそれはノーカンだろ", 4.5, 8.0)))
        assertEquals(listOf("小学校に上がる前だから", "４", "５歳くらいかな", "それはノーカンだろ"), result.matches.map { it.text })
        assertEquals(4.0, result.matches[1].startTime, .0001)
        assertEquals(4.2, result.matches[1].endTime, .0001)
        assertEquals(4.3, result.matches[2].startTime, .0001)
        assertTrue(result.matches.zipWithNext().all { (a, b) -> a.endTime <= b.startTime })
    }

    @Test fun unequalNumericValuesCannotSupplyAnEntireUnspokenCue() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。４。私は駅へ向かって歩いた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("五", 5.0, 5.5),
                token("私は駅へ向かって歩いた", 6.0, 10.0)))
        assertFalse(result.matches.any { it.text == "４" })
    }

    @Test fun stronglySupportedSentenceAllowsAContractedShortName() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>そんな俺の気遣いもむなしく、八奈見は向かいの席に腰を下ろしてきた。</p>"),
            listOf(token("そんな俺の気遣いもむなしく", 1.0, 4.0), token("波", 4.0, 4.5),
                token("は向かいの席に腰を下ろしてきた", 4.5, 8.0)))
        assertEquals(listOf("そんな俺の気遣いもむなしく", "八奈見は向かいの席に腰を下ろしてきた"), result.matches.map { it.text })
        assertEquals(4.0, result.matches.last().startTime, .0001)
        assertEquals(8.0, result.matches.last().endTime, .0001)
    }

    @Test fun imbalancedGapKeepsDistinctiveRecognizedWordWithoutFillingMissingAnnouncement() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。４限世界史開始が１０分遅れます。私は駅へ向かって歩いた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("予言", 5.0, 5.4),
                token("世界史", 5.4, 6.2), token("私は駅へ向かって歩いた", 7.0, 11.0)))
        assertEquals(listOf("雨の降る静かな朝だった", "世界史", "私は駅へ向かって歩いた"), result.matches.map { it.text })
        assertEquals(5.4, result.matches[1].startTime, .0001)
        assertEquals(6.2, result.matches[1].endTime, .0001)
    }

    @Test fun cueEdgesKeepReadingRewritesBesideOmittedInterjections() {
        val cases = listOf(
            Triple("おお、助かるぞ。", "おたすかるぞ", listOf("お", "助かるぞ")),
            Triple("お前、親切だな。", "おまえしんせつだな", listOf("お前", "親切だな")),
            Triple("あの、ごめんね。私、そんなことになると思わなかった。", "あのわたしそんなことになると思わなかった", listOf("あの", "私", "そんなことになると思わなかった")),
            Triple("わ、わっ、わたし、ぶん、文芸部１年のっ、です。", "わ私ぶ文芸部一年のです", listOf("わ", "わたし", "ぶん", "文芸部１年のっ", "です")),
            Triple("これなんの場面だろう。良く分からんが。", "これなんの場面だろよく分からんが", listOf("これなんの場面だろ", "良く分からんが")),
            Triple("お、さっそく仲良くやってるねー。", "早速仲良くやってるねー", listOf("さっそく仲良くやってるねー")),
            Triple("えー、なんなんだ。", "へえ何なんだ", listOf("え", "なんなんだ")),
        )
        for ((written, spoken, expected) in cases) {
            val result = SasayakiTranscriptAligner.align(
                book("<p>雨の降る静かな朝だった。$written 私は駅へ向かって歩いた。</p>"),
                listOf(token("雨の降る静かな朝だった", 1.0, 4.0)) +
                    spoken.mapIndexed { i, c -> token(c.toString(), 5.0 + i * .12, 5.12 + i * .12) } +
                    token("私は駅へ向かって歩いた", 12.0, 16.0))
            assertEquals(written, listOf("雨の降る静かな朝だった") + expected + "私は駅へ向かって歩いた", result.matches.map { it.text })
            assertTrue(written, result.matches.zipWithNext().all { (a, b) -> a.endTime <= b.startTime + .000001 })
        }
    }

    @Test fun separateTokensKeepRewrittenNameAndNextSentencePronoun() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>そこにいたのは小鞠<ruby>知花<rt>ちか</rt></ruby>。俺をグイと押しのけて鍵を開ける。</p>"),
            listOf(token("そこにいたのは小鞠", 1.0, 4.0), token("千佳", 4.0, 4.5), token("おれ", 4.5, 5.0),
                token("をグイと押しのけて鍵を開ける", 5.0, 9.0)))
        assertEquals(listOf("そこにいたのは小鞠知花", "俺をグイと押しのけて鍵を開ける"), result.matches.map { it.text })
        assertEquals(4.5, result.matches[0].endTime, .0001)
        assertEquals(4.5, result.matches[1].startTime, .0001)
    }

    @Test fun uniqueKanjiCueKeepsOwnSpeechBeforeALongOmission() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。え。誰。どこの。あっ、あの、小鞠です。文芸部、小鞠知花。私は駅へ向かって歩いた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("えっ", 5.0, 5.5), token("誰", 5.5, 6.5),
                token("文芸部小鞠智加", 7.0, 9.0), token("私は駅へ向かって歩いた", 10.0, 14.0)))
        val cue = result.matches.single { it.text == "誰" }
        assertEquals(5.5, cue.startTime, .0001)
        assertEquals(6.5, cue.endTime, .0001)
        assertFalse(result.matches.any { it.text.contains("あの") || it.text.contains("どこの") })
    }

    @Test fun readingDurationUsesRecognizedSyllablesRatherThanOnlyWrittenKanjiCount() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。あの、ごめんね、私、そんなことになると思わなかった。</p>"),
            listOf(token("雨の降る静かな朝だったあの", 1.0, 4.0), token("わたし", 5.0, 6.88),
                token("そんなことになると思わなかった", 6.88, 10.0)))
        val cue = result.matches.single { it.text == "私" }
        assertEquals(5.0, cue.startTime, .0001)
        assertEquals(6.88, cue.endTime, .0001)
        assertFalse(result.matches.any { it.text.contains("ごめんね") })
    }

    @Test fun abnormalEdgeTokenDoesNotDiscardReliablyTimedRestOfCue() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。先生。文芸部。私は駅へ向かって歩いた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("先", 5.0, 5.16), token("生", 5.16, 8.2),
                token("文", 9.0, 12.48), token("芸", 12.48, 12.76), token("部", 12.76, 13.44),
                token("私は駅へ向かって歩いた", 14.0, 18.0)))
        assertEquals(listOf("雨の降る静かな朝だった", "先", "芸部", "私は駅へ向かって歩いた"), result.matches.map { it.text })
        assertEquals(5.16, result.matches[1].endTime, .0001)
        assertEquals(12.48, result.matches[2].startTime, .0001)
    }

    @Test fun rewrittenNameCannotBeTakenByFollowingKanaInterjection() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>恥ずかしそうに頬を押さえる八奈見。</p>", "<p>え、つまりそれって付き合ってなかったのか。</p>"),
            listOf(token("恥ずかしそうに頬を押さえる八", 1.0, 5.0), token("波", 5.0, 5.5),
                token("つまりそれってつきあってなかったのか", 6.0, 10.0)))
        assertFalse(result.matches.any { it.chapterIndex == 1 && it.start == 0 })
    }

    @Test fun wholeCueCannotClaimOnlyPartOfANeighboringRecognitionToken() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。えー、私、えー、そんなことになると思わなかった。私は駅へ向かって歩いた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("へえ", 5.0, 5.5), token("へえ", 5.5, 6.0),
                token("そんなことになると思わなかった", 6.0, 9.0), token("私は駅へ向かって歩いた", 10.0, 14.0)))
        assertFalse(result.matches.any { it.text == "私" })
    }

    @Test fun aReadingCannotSplitOneKanjiStemBetweenAdjacentCueEdges() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。七宮だけは、埃をかぶった肉なんて食いたくない。</p>"),
            listOf(token("雨の降る静かな朝だった七宮だけ", 1.0, 5.0), token("誇", 5.0, 5.3), token("り", 5.3, 5.5),
                token("をかぶった肉なんて食いたくない", 5.5, 9.0)))
        assertFalse(result.matches.any { it.text == "七宮だけは" })
    }

    @Test fun weaklyRecognizedNameStillCompetesWithPreviousKanaTail() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。ああ、葉村君、比留子さんが言った。私は駅へ向かって歩いた。</p>"),
            listOf(token("雨の降る静かな朝だったあ", 1.0, 5.0), token("羽", 5.0, 5.3), token("村くん紘子さんが言った", 5.3, 8.5),
                token("私は駅へ向かって歩いた", 9.0, 13.0)))
        assertFalse(result.matches.any { it.text == "ああ" })
    }

    @Test fun durationFragmentsRecheckCoverageBeforeJoiningUntimedWords() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>雨の降る静かな朝だった。先に話した彼の生まれた村へ向かって歩いた。私は駅へ向かって歩いた。</p>"),
            listOf(token("雨の降る静かな朝だった", 1.0, 4.0), token("先", 5.0, 5.1), token("生", 8.1, 8.2),
                token("ま", 8.2, 28.2), token("れた村へ向かって歩いた", 28.2, 32.2), token("私は駅へ向かって歩いた", 33.0, 37.0)))
        assertEquals(listOf("雨の降る静かな朝だった", "先", "生", "れた村へ向かって歩いた", "私は駅へ向かって歩いた"), result.matches.map { it.text })
    }

    @Test fun partialReadingBeforeALongUnspokenCueRemainsMatched() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>下松が明るい声をかけてきた。でもぶちょー、探偵さんたちも一緒に行くんなら一台じゃ無理だよ。撮影道具もあるし彼女は言った。</p>"),
            listOf(token("下松が明るい声をかけてきたでも", 1.0, 5.0), token("部長", 5.0, 5.75),
                token("撮影道具もあるし彼女は言った", 6.0, 10.0)))
        assertEquals(listOf("下松が明るい声をかけてきた", "でもぶちょー", "撮影道具もあるし彼女は言った"), result.matches.map { it.text })
    }

    @Test fun sentenceEndingBesideAnchorCannotMoveIntoALaterOmittedSentence() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>どうしてそう言いきれる。答える代わりに明智さんは俺を見た。葉村君、時計はハンカチで包んでいたのだね。はい。今はハンカチだけが残されている。</p>"),
            listOf(token("どうしてそう言い", 1.0, 4.0), token("切", 4.0, 4.16), token("れ", 4.16, 4.32), token("る", 4.32, 4.65),
                token("今はハンカチだけが残されている", 5.0, 9.0)))
        assertEquals(listOf("どうしてそう言いきれる", "今はハンカチだけが残されている"), result.matches.map { it.text })
        assertEquals(4.65, result.matches.first().endTime, .0001)
    }

    @Test fun longerParaphraseDoesNotGainTheRelaxedReadingDurationLimit() {
        val result = SasayakiTranscriptAligner.align(
            book("<p>これでは一時も心が休まらず、相当ストレスを溜め込んでいるのではなかろうか。</p>"),
            listOf(token("これでは一時も心が休ま", 1.0, 4.0), token("りはしない彼女は", 4.0, 6.57),
                token("相当ストレスを溜め込んでいるのではなかろうか", 7.0, 12.0)))
        assertFalse(result.matches.any { it.text.endsWith("らず") })
    }

    private fun book(vararg html: String) = EpubBook(
        title = "Generated alignment fixture",
        chapters = html.mapIndexed { index, content ->
            EpubChapter("chapter-$index", "chapter-$index.xhtml", "application/xhtml+xml", "<body>$content</body>")
        },
    )

    private fun token(text: String, start: Double, end: Double) = SasayakiToken(text, start, end)
}
