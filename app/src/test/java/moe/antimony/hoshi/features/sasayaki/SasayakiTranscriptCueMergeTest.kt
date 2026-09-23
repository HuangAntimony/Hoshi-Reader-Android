package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import org.junit.Assert.*
import org.junit.Test

class SasayakiTranscriptCueMergeTest {
    @Test fun missingWordEndingStaysBeforeAnOmittedReplyJoinedToTheNextCue() {
        val source = book("俺は何も見てないよ。大丈夫。「そう、そうです！温水君は何も見ていないから！」")
        val tokens = listOf(
            SasayakiToken("俺は何も見てないよ", 569.474, 571.554),
            SasayakiToken("大", 571.554, 572.034), SasayakiToken("丈", 572.034, 572.154),
            SasayakiToken("温", 572.194, 574.354), SasayakiToken("水", 574.354, 574.674),
            SasayakiToken("くん", 574.674, 574.994), SasayakiToken("は何も見ていないから", 574.994, 577.242),
        )
        val result = SasayakiTranscriptAligner.align(source, tokens)
        assertEquals(listOf("俺は何も見てないよ", "大丈夫", "そうそうです温水君は何も見ていないから"), result.matches.map { it.text })
        assertEquals(571.554, result.matches[1].startTime, .0001)
        assertEquals(572.154, result.matches[1].endTime, .0001)
        assertEquals(572.154, result.matches[2].startTime, .0001)
        assertEquals(577.242, result.matches[2].endTime, .0001)
        assertEquals(12, result.matches[2].start)
        assertEquals("0-12", result.matches[2].id)
        assertEquals(0, result.unmatched)
        val session = SasayakiTranscriptAligner.Session(source)
        for (count in 1..tokens.size) {
            assertEquals(SasayakiTranscriptAligner.align(source, tokens.take(count)), session.align(tokens.take(count)))
        }
    }

    @Test fun missingWordOpeningStaysAfterAnOmittedReplyJoinedToThePreviousCue() {
        val result = SasayakiTranscriptAligner.align(
            book("雨の降る静かな朝だった。そうですね。日差しがよく当たるから。"),
            letters("雨の降る静かな朝だっ", 1.0, .2) + SasayakiToken("た", 3.0, 6.0) +
                letters("差しがよく当たるから", 6.0, .2),
        )
        assertEquals(listOf("雨の降る静かな朝だったそうですね", "日差しがよく当たるから"), result.matches.map { it.text })
        assertEquals(6.0, result.matches.first().endTime, .0001)
        assertEquals(6.0, result.matches.last().startTime, .0001)
        assertEquals(0, result.unmatched)
    }

    @Test fun shortMatchedCueCanUseContiguousRecognizedContext() {
        val result = SasayakiTranscriptAligner.align(
            book("雨の降る静かな朝だった。大丈夫。そうです。彼女は窓の外を眺めていた。"),
            letters("雨の降る静かな朝だった大丈夫", 1.0, .2) + letters("彼女は窓の外を眺めていた", 3.8, .3),
        )
        assertEquals(listOf("雨の降る静かな朝だった", "大丈夫", "そうです彼女は窓の外を眺めていた"), result.matches.map { it.text })
        assertEquals(0, result.unmatched)
    }

    @Test fun shortCueContextStopsAtUnmatchedTextAndLongAudioGaps() {
        val left = "雨の降る静かな朝だった"
        val right = "彼女は窓の外を眺めていた"
        for ((prefixGap, shortStart) in listOf("はい。" to 4.3, "" to 10.0)) {
            val result = SasayakiTranscriptAligner.align(book("$left。${prefixGap}大丈夫。そうです。$right。"),
                letters(left, 1.0, .3) + letters("大丈夫", shortStart, .2) + letters(right, shortStart + .6, .3))
            assertEquals(listOf(left, "大丈夫", right), result.matches.map { it.text })
            assertEquals(if (prefixGap.isEmpty()) 1 else 2, result.unmatched)
        }
    }

    @Test fun aLongMissingSentenceTailIsNotMovedIntoTheFollowingReply() {
        val left = "雨の降る静かな朝だった"
        val right = "彼女は窓の外を眺めていた"
        val result = SasayakiTranscriptAligner.align(book("${left}のでした。はい。$right。"),
            letters(left, 1.0, .3) + letters(right, 4.3, .3))
        assertEquals(listOf(left, right), result.matches.map { it.text })
    }

    @Test fun anUnchangedLongCueDoesNotBlockGroupingIntoItsShortNeighbor() {
        val left = (0 until 100).joinToString("") { (0x4e00 + it).toChar().toString() }
        val right = "彼女は窓の外を眺めていた"
        val result = SasayakiTranscriptAligner.align(book("$left。そう。$right。"),
            listOf(SasayakiToken(left, 1.0, 20.0), SasayakiToken("彼", 20.0, 23.0)) + letters(right.drop(1), 23.0, .2))
        assertEquals(listOf(left, "そう$right"), result.matches.map { it.text })
        assertEquals(20.0, result.matches.first().endTime, .0001)
        assertEquals(20.0, result.matches.last().startTime, .0001)
    }

    @Test fun omittedQuestionWithCommaJoinsTheFollowingLongFirstToken() {
        val result = SasayakiTranscriptAligner.align(
            book("ヤクルトを味わいます。「学校はどうだった、お嬢ちゃん」たまごサンドをむしゃむしゃと食べる。"),
            letters("ヤクルトを味わいます", 994.866, .2) +
                SasayakiToken("た", 996.866, 999.866) + letters("まごサンドをむしゃむしゃと食べる", 999.866, .2),
        )
        assertEquals(listOf("ヤクルトを味わいます", "学校はどうだったお嬢ちゃんたまごサンドをむしゃむしゃと食べる"), result.matches.map { it.text })
        assertEquals(996.866, result.matches.last().startTime, .0001)
        assertEquals(10, result.matches.last().start)
        assertEquals("0-10", result.matches.last().id)
        assertEquals(0, result.unmatched)
    }

    @Test fun omittedSentenceJoinsThePreviousLongLastToken() {
        val result = SasayakiTranscriptAligner.align(
            book("いつもより多く抑揚をつけて鳴きます。彼女の歌声はとても綺麗です。彼女は教えてくれないけれど。"),
            listOf(SasayakiToken("いつもより多く抑揚をつけて", 710.458, 712.818),
                SasayakiToken("泣", 712.818, 712.978), SasayakiToken("き", 712.978, 713.218),
                SasayakiToken("ま", 713.218, 713.298)) +
                SasayakiToken("す", 713.298, 717.258) + letters("彼女は教えてくれないけれど", 717.258, .2),
        )
        assertEquals(listOf("いつもより多く抑揚をつけて鳴きます彼女の歌声はとても綺麗です", "彼女は教えてくれないけれど"), result.matches.map { it.text })
        assertEquals(717.258, result.matches.first().endTime, .0001)
        assertEquals(717.258, result.matches.last().startTime, .0001)
        assertEquals(0, result.unmatched)
    }

    @Test fun ordinaryTokensCanMergeACryUsingTheAvailableGap() {
        val left = "彼女は興味がなさそうにまた"
        val right = "と鳴きました"
        val result = SasayakiTranscriptAligner.align(
            book("$left「ナー」$right。私は窓の外を眺めていた。"),
            letters(left, 1.0, .2) + letters(right, 5.0, .3) + letters("私は窓の外を眺めていた", 7.0, .2),
        )
        assertEquals(listOf(left, "ナー$right", "私は窓の外を眺めていた"), result.matches.map { it.text })
        assertEquals(1.0 + left.length * .2, result.matches[1].startTime, .0001)
        assertEquals(5.0 + right.length * .3, result.matches[1].endTime, .0001)
        assertEquals(0, result.unmatched)
    }

    @Test fun ordinaryTokensWithoutSilenceStillCoverAnInteriorReply() {
        val left = "雨の降る静かな朝だった"
        val right = "彼女は窓の外を眺めていた"
        val result = SasayakiTranscriptAligner.align(book("$left。はい。$right。"),
            letters(left, 1.0, .3) + letters(right, 4.3, .2))
        assertEquals(listOf("${left}はい", right), result.matches.map { it.text })
        assertEquals(4.3, result.matches.first().endTime, .0001)
        assertEquals(4.3, result.matches.last().startTime, .0001)
    }

    @Test fun commaContinuationWinsWhenEdgeTimingsAreOrdinary() {
        val left = "雨の降る静かな朝だった"
        val right = "彼女は窓の外を眺めていた"
        val result = SasayakiTranscriptAligner.align(book("$left。そう、$right。"),
            letters(left, 1.0, .3) + letters(right, 4.3, .2))
        assertEquals(listOf(left, "そう$right"), result.matches.map { it.text })
    }

    @Test fun expansionStopsAtRealMatchesAndNeverPropagatesIntoBookEnds() {
        val source = book("はじめに。雨の降る静かな朝だった。はい。彼女は窓の外を眺めていた。そう。私は駅へ向かって歩いた。おしまい。")
        val tokens = letters("雨の降る静かな朝だった", 1.0, .3) +
            letters("彼女は窓の外を眺めていた", 5.0, .2) + letters("私は駅へ向かって歩いた", 9.0, .3)
        val session = SasayakiTranscriptAligner.Session(source)
        for (count in 1..tokens.size) {
            assertEquals(SasayakiTranscriptAligner.align(source, tokens.take(count)), session.align(tokens.take(count)))
        }
        val result = session.align(tokens)
        assertEquals("雨の降る静かな朝だったはい彼女は窓の外を眺めていたそう私は駅へ向かって歩いた", result.matches.joinToString("") { it.text })
        assertEquals(2, result.unmatched)
        assertTrue(result.matches.zipWithNext().all { (a, b) -> a.start + a.length == b.start && a.endTime <= b.startTime + .00001 })
    }

    @Test fun omissionAcrossChapterBoundaryIsNotGrouped() {
        val source = book("雨の降る静かな朝だった。はい。", "彼女は窓の外を眺めていた。")
        val result = SasayakiTranscriptAligner.align(source,
            letters("雨の降る静かな朝だった", 1.0, .3) + letters("彼女は窓の外を眺めていた", 5.0, .2))
        assertEquals(listOf("雨の降る静かな朝だった", "彼女は窓の外を眺めていた"), result.matches.map { it.text })
        assertEquals(1, result.unmatched)
    }

    @Test fun multipleCommaCuesCanBelongToOneOmittedSentence() {
        val left = "雨の降る静かな朝だった"
        val right = "彼女は窓の外を眺めていた"
        val result = SasayakiTranscriptAligner.align(book("$left。そう、でも、やっぱり、駄目。$right。"),
            letters(left, 1.0, .3) + letters(right, 4.3, .2))
        assertEquals(listOf("${left}そうでもやっぱり駄目", right), result.matches.map { it.text })
        assertEquals(0, result.unmatched)
    }

    @Test fun groupingLimitsNeverDiscardExistingMatches() {
        val left = "雨の降る静かな朝だった"
        val right = "彼女は窓の外を眺めていた"
        for ((omitted, rightStart) in listOf("はい。そう。うん。" to 5.0, "あ".repeat(49) + "。" to 5.0, "はい。" to 30.0)) {
            val result = SasayakiTranscriptAligner.align(book("$left。$omitted$right。"),
                letters(left, 1.0, .3) + letters(right, rightStart, .2))
            assertEquals(listOf(left, right), result.matches.map { it.text })
            assertEquals(4.3, result.matches.first().endTime, .0001)
            assertEquals(rightStart, result.matches.last().startTime, .0001)
            assertTrue(result.unmatched > 0)
        }
    }

    @Test fun prependingKeepsCodePointOffsetsAcrossRubyAndSupplementaryCharacters() {
        val left = "雨の降る静かな朝だった"
        val source = book("$left。𠮟る声。<ruby>彼女<rt>かのじょ</rt></ruby>は窓の外を眺めていた。")
        val result = SasayakiTranscriptAligner.align(source, letters(left, 1.0, .2) +
            listOf(SasayakiToken("か", 3.2, 6.2)) + letters("のじょは窓の外を眺めていた", 6.2, .2))
        val next = result.matches.last()
        assertEquals(result.matches.toString(), "𠮟る声彼女は窓の外を眺めていた", next.text)
        assertEquals(11, next.start)
        assertEquals(next.text.codePointCount(0, next.text.length), next.length)
        assertEquals("0-11", next.id)
        assertEquals(0, result.unmatched)
    }

    private fun letters(text: String, start: Double, step: Double) = text.mapIndexed { i, ch ->
        SasayakiToken(ch.toString(), start + i * step, start + (i + 1) * step)
    }

    private fun book(vararg text: String) = EpubBook(title = "Cue merging fixture", chapters = text.mapIndexed { i, content ->
        EpubChapter("chapter-$i", "chapter-$i.xhtml", "application/xhtml+xml", "<body><p>$content</p></body>")
    })
}
