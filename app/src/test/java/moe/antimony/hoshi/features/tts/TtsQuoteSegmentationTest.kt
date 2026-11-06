package moe.antimony.hoshi.features.tts

import org.junit.Assert.*
import org.junit.Test

class TtsQuoteSegmentationTest {
    @Test fun universalQuotesApostrophesAndAdjacentOpenersUseActualPairs() {
        val cases = listOf(
            "前“你好”後" to listOf("前", "“你好”", "後"),
            "앞‘안녕’뒤" to listOf("앞", "‘안녕’", "뒤"),
            "He said \"Hello!\"\"Again?\" End" to listOf("He said ", "\"Hello!\"", "\"Again?\"", " End"),
            "前‘don’t touch John’s book’後" to listOf("前", "‘don’t touch John’s book’", "後"),
            "前“外「内『引用』」側”後" to listOf("前", "“外「内『引用』」側”", "後"),
            "John's book isn't here" to listOf("John's book isn't here"),
            "前“未閉合、後續正文" to listOf("前“未閉合、後續正文"),
        )
        for ((text, expected) in cases) {
            val sentences = TtsText.chapter(0, "<p>$text</p>")
            assertEquals(text, expected, sentences.map { it.text })
            assertEquals(text, sentences.joinToString("") { it.text })
        }
        val spanning = TtsText.chapter(0, "<p>前<span>“一</span>句</p><p>二句”後</p>")
        assertEquals(listOf("前“一句", "二句”後"), spanning.map { it.text })
        assertTrue(spanning.all { it.roles.single().role == TtsTextRole.Narration })
        val inline = TtsText.chapter(0, "<p>前<span>“一</span>句<span>二句”</span>後</p>")
        assertEquals(listOf("前", "“一句二句”", "後"), inline.map { it.text })
    }

    @Test fun inlineBreaksAndImagesKeepParagraphPairingButNestedBlockStartsEndIt() {
        val inline = TtsText.chapter(0, "<p>前<span>“一句</span><br/>二句<img src='x'/>三句”後</p>")
        assertEquals(listOf("前", "“一句", "二句", "三句”", "後"), inline.map { it.text })
        assertTrue(inline.subList(1, 4).all { it.roles.single().role == TtsTextRole.Dialogue })
        assertTrue(inline[3].imageBefore)
        val blocks = TtsText.chapter(0, "<div>前“未完<p>後文”</p></div>")
        assertTrue(blocks.all { it.roles.all { range -> range.role == TtsTextRole.Narration } })
    }

    @Test fun outerQuotesBecomeIndependentReplayUnits() {
        val cases = listOf(
            "彼女は「こんにちは」と言った。" to listOf("彼女は", "「こんにちは」", "と言った。"),
            "「はい」「わかった」" to listOf("「はい」", "「わかった」"),
            "彼は『明日行く』と答えた。" to listOf("彼は", "『明日行く』", "と答えた。"),
            "「彼は『明日行く』と言った」" to listOf("「彼は『明日行く』と言った」"),
            "「おはよう。今日は晴れだ。」" to listOf("「おはよう。", "今日は晴れだ。」"),
            "彼は「はい」。次。" to listOf("彼は", "「はい」。", "次。"),
            "前「はい」！？後。" to listOf("前", "「はい」！？", "後。"),
            "前「閉じない文。" to listOf("前「閉じない文。"),
            "前「話『未完でも外は閉じる」後。" to listOf("前", "「話『未完でも外は閉じる」", "後。"),
        )
        cases.forEach { (text, expected) ->
            val sentences = TtsText.chapter(0, "<p>$text</p>")
            assertEquals(text, expected, sentences.map { it.text })
            assertEquals(text, text, sentences.joinToString("") { it.text })
        }
    }

    @Test fun paragraphBoundaryEndsUnmatchedQuotesButPreservesOffsetsAndImages() {
        val sentences = TtsText.chapter(2, "<p>前<span>「一</span>つ</p><img src='x'/><p>二つ」後。</p>")
        assertEquals(listOf("前「一つ", "二つ」後。"), sentences.map { it.text })
        assertEquals(listOf(0, 3), sentences.map { it.start })
        assertEquals(listOf(3, 3), sentences.map { it.length })
        assertEquals(listOf(false, true), sentences.map { it.imageBefore })
        assertEquals(listOf(TtsTextRole.Narration, TtsTextRole.Narration),
            sentences.map { it.roles.single().role })
        assertTrue(sentences.all { it.chapter == 2 })
    }

    @Test fun rubyAndSupplementaryCharactersKeepCoordinatesAndSelection() {
        val text = "𠮷は「今日」と言った。"
        val sentences = TtsText.chapter(0, "<img src='x'/><p>𠮷は「<ruby>今日<rt>きょう</rt></ruby>」と言った。</p>")
        assertEquals(listOf("𠮷は", "「今日」", "と言った。"), sentences.map { it.text })
        assertEquals(listOf(0, 2, 4), sentences.map { it.start })
        assertEquals(listOf(2, 2, 4), sentences.map { it.length })
        assertEquals(listOf(true, false, false), sentences.map { it.imageBefore })
        assertEquals(listOf(TtsRuby(1, 3, "きょう")), sentences[1].ruby)
        val selected = TtsText.selection(sentences, text, 0, 0)
        assertEquals(text, selected.text)
        assertEquals("𠮷は「きょう」と言った。", TtsText.spoken(selected, null, emptyList()))
        assertEquals(listOf(TtsTextRole.Narration, TtsTextRole.Dialogue, TtsTextRole.Narration),
            TtsText.spokenParts(selected, null, emptyList()).map { it.role })
    }

    @Test fun newBoundariesNeverSplitRubyAndZeroWidthReadingsAreOwnedOnce() {
        val atomic = TtsText.chapter(0, "<p>前<ruby>字「引用」字<rt>よみ</rt></ruby>後。</p>")
        assertEquals(1, atomic.size)
        assertEquals("前よみ後。", TtsText.spoken(atomic.single(), null, emptyList()))
        val split = TtsText.chapter(0, "<p>前「<ruby><img src='g'/><rt>あ</rt></ruby>字」<ruby><img src='g'/><rt>い</rt></ruby>後<ruby><img src='g'/><rt>う</rt></ruby></p>")
        assertEquals(listOf("前", "「字」", "後"), split.map { it.text })
        assertEquals(listOf("前", "「あ字」", "い後う"), split.map { TtsText.spoken(it, null, emptyList()) })
        val selected = TtsText.selection(split, "前「字」後", 0, 0)
        assertEquals("前「あ字」い後う", TtsText.spoken(selected, null, emptyList()))
    }

    @Test fun symbolOnlyPiecesNeverLoseTextOrBecomeUnlocatableUnits() {
        listOf("「」後。", "前「」", "前「……」後。", "「😀」「はい」", "前「はい」、後。").forEach { text ->
            val sentences = TtsText.chapter(0, "<p>$text</p>")
            assertEquals(text, text, sentences.joinToString("") { it.text })
            assertTrue(text, sentences.all { it.length > 0 })
        }
    }

    @Test fun replacementScopeFollowsReplayUnitsWhileSelectionKeepsTheWholeText() {
        val text = "彼は「今日」と言った。"
        val sentences = TtsText.chapter(0, "<p>$text</p>")
        listOf(false, true).forEach { regex ->
            val rules = listOf(TtsWord("cross", "は「今日", "は本日", regex = regex))
            assertEquals(text, sentences.joinToString("") { TtsText.spoken(it, null, rules) })
            assertEquals("彼は本日」と言った。", TtsText.spoken(TtsText.selection(sentences, text, 0, 0), null, rules))
        }
    }
}
