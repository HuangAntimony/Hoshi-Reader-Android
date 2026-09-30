package moe.antimony.hoshi.features.tts

import org.junit.Assert.*
import org.junit.Test

class TtsTextTest {
    @Test fun imageBackedRubyIsSpokenOnceWithoutAddingSourceCharacters() {
        val sentence = TtsText.chapter(0, "<p><ruby><img src='glyph.png'/><rt>たず</rt></ruby>ねた。<ruby><img src='glyph.png'/><rt>き</rt></ruby>く。</p>")
        assertEquals(listOf("ねた。", "く。"), sentence.map { it.text })
        assertEquals(listOf(0, 2), sentence.map { it.start })
        assertEquals(listOf("たずねた。", "きく。"), sentence.map { TtsText.spoken(it, null, emptyList()) })
    }

    @Test fun zeroWidthReadingsAtSharedAndEndPositionsAreEmittedOnlyOnce() {
        val sentence = TtsSentence(0, 0, 1, "字", listOf(
            TtsRuby(0, 0, "あ"), TtsRuby(0, 0, "い"), TtsRuby(0, 1, "じ"), TtsRuby(1, 1, "う"),
        ))
        assertEquals("あいじう", TtsText.spoken(sentence, null, emptyList()))
        assertEquals("あいしう", TtsText.spoken(sentence, null, listOf(TtsWord("a", "字", "し"))))
    }

    @Test fun sentenceSelectionRetainsImageGlyphReadingsFromItsOwnSourceSentence() {
        val source = TtsText.chapter(0, "<p>前。</p><p><ruby><img src='glyph.png'/><rt>たず</rt></ruby>ねた。</p><p><ruby><img src='glyph.png'/><rt>き</rt></ruby>く。</p>")
        val selected = TtsText.selection(source, "ねた。", 1, 0)
        assertEquals("たずねた。", TtsText.spoken(selected, null, emptyList()))
        assertEquals("ねた。", selected.text)
        assertEquals(1, selected.start)
    }

    @Test fun invalidRubyRangesCannotMoveBackwardsOrSkipSourceText() {
        val sentence = TtsSentence(0, 0, 2, "文字", listOf(TtsRuby(0, -1, "あ"), TtsRuby(0, 8, "い"), TtsRuby(1, 0, "う")))
        assertEquals("文字", TtsText.spoken(sentence, null, emptyList()))
    }

    @Test fun rubyWithoutBaseDoesNotCreateAnEmptySourceRange() {
        val sentence = TtsText.chapter(0, "<p>前<ruby><rt>から</rt></ruby><ruby>東京<rt>とうきょう</rt></ruby>です。</p>").single()
        assertEquals("前東京です。", sentence.text)
        assertEquals(listOf(TtsRuby(1, 3, "とうきょう")), sentence.ruby)
        assertEquals("前とうきょうです。", TtsText.spoken(sentence, null, emptyList()))
    }

    @Test fun selectionUsesTheAnchoredOccurrenceAndRebasesRubyAfterBracketTrimming() {
        val source = TtsText.chapter(0, "<p><ruby>東京<rt>とうきょう</rt></ruby>です。</p><p>「<ruby>東京<rt>とうけい</rt></ruby>です。</p>")
        val selected = TtsText.selection(source, "東京です。", 4, 0)
        assertEquals("とうけいです。", TtsText.spoken(selected, null, emptyList()))
        assertEquals("東京です。", selected.text)
        assertEquals(4, selected.start)
    }

    @Test fun adjacentQuotedSentencesAndRubyStartSeparateSentences() {
        val sentences = TtsText.chapter(0, "<p>「一つ。」<ruby>次<rt>つぎ</rt></ruby>です。「三つ。」</p>")
        assertEquals(listOf("「一つ。」", "次です。", "「三つ。」"), sentences.map { it.text })
        assertEquals("つぎです。", TtsText.spoken(sentences[1], null, emptyList()))
    }

    @Test fun entitiesAreDecodedAsInTheLiveDomAndScriptsNeverSpeak() {
        val sentences = TtsText.chapter(0, "<body><p>&#x6771;&#20140;です。</p><p>次。</p><script>bad</script></body>")
        assertEquals("東京です。", sentences.first().text)
        assertEquals(4, sentences[1].start)
    }

    @Test fun dictionaryOverridesRubyWithoutChangingSourceCoordinates() {
        val sentences = TtsText.chapter(2, "<body><p>「<ruby>東京<rt>とうきょう</rt></ruby>へ行く。」</p><p>次です。</p></body>")
        assertEquals(listOf("「東京へ行く。」", "次です。"), sentences.map { it.text })
        val first = sentences.first()
        assertEquals(0, first.start)
        assertEquals(5, first.length)
        assertEquals("「とうきょうへ行く。」", TtsText.spoken(first, "book", emptyList()))
        val entries = listOf(TtsWord("global", "東京", "とうけい"), TtsWord("local", "東京", "とんきん", "book"))
        assertEquals("「とんきんへ行く。」", TtsText.spoken(first, "book", entries))
        assertEquals("「とうけいへ行く。」", TtsText.spoken(first, "other", entries))
        assertEquals(5, sentences[1].start)
    }

    @Test fun longestLiteralWinsAndDeletedEntryRestoresRuby() {
        val sentence = TtsText.chapter(0, "<p>東京駅に東京駅。</p>").single()
        val words = listOf(TtsWord("a", "東京", "とんきん", "book"), TtsWord("b", "東京駅", "とうきょうえき"))
        assertEquals("とうきょうえきにとうきょうえき。", TtsText.spoken(sentence, "book", words))
        assertEquals("東京駅に東京駅。", TtsText.spoken(sentence, "book", emptyList()))
    }

    @Test fun imageBoundariesAndSupplementaryCharactersPreserveOffsets() {
        val sentences = TtsText.chapter(0, "<p>𠮷野です。</p><img src='x'/><p>続き！</p><script>ignored</script>")
        assertEquals(4, sentences[0].length)
        assertEquals(4, sentences[1].start)
        assertTrue(sentences[1].imageBefore)
        assertEquals(2, sentences.size)
    }

    @Test fun longSynthesisChunksNeverSplitSurrogatePairs() {
        val text = "𠮷野。".repeat(50)
        val chunks = TtsText.chunks(text, 13)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 13 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }

    @Test fun dictionaryRejectsConflictsAndNonKana() {
        val original = TtsWord("a", "東京", "とうきょう", "book")
        assertEquals(TtsWordError.Duplicate, validateTtsWord(original.copy(id = "b"), listOf(original)))
        assertNull(validateTtsWord(original.copy(reading = "トーキョー"), listOf(original)))
        assertNull(validateTtsWord(original.copy(id = "b", bookId = null), listOf(original)))
        assertEquals(TtsWordError.Reading, validateTtsWord(original.copy(reading = "Tokyo"), emptyList()))
    }
}
