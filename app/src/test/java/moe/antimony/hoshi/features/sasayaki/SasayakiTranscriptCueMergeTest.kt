package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import org.junit.Assert.*
import org.junit.Test

class SasayakiTranscriptCueMergeTest {
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
