package moe.antimony.hoshi.features.tts

import org.junit.Assert.*
import org.junit.Test

class TtsDialogueTest {
    private fun parts(sentence: TtsSentence, words: List<TtsWord> = emptyList(), ruby: Boolean = true) =
        TtsText.spokenParts(sentence, "book", words, ruby).map { it.role to it.text }

    @Test fun selectedMixedSentenceKeepsOffsetsAndUsesDialogueOnlyInsideQuotes() {
        val text = "彼女は「こんにちは」と言った。"
        val sentence = TtsText.selection(TtsText.chapter(2, "<p>$text</p>"), text, 0, 0)
        assertEquals(listOf(TtsTextRole.Narration to "彼女は", TtsTextRole.Dialogue to "「こんにちは」", TtsTextRole.Narration to "と言った。"), parts(sentence))
        assertEquals(2, sentence.chapter)
        assertEquals(0, sentence.start)
        assertEquals(12, sentence.length)
    }

    @Test fun everyQuoteTypePairsWithinAParagraphButNeverAcrossParagraphs() {
        for ((open, close) in listOf('「' to '」', '『' to '』', '“' to '”', '‘' to '’', '"' to '"')) {
            val sentences = TtsText.chapter(0, "<p>${open}おはよう。</p><img src='image'/><p>今日は晴れだ。${close}彼は笑った。</p>")
            assertTrue(sentences.all { it.roles.all { range -> range.role == TtsTextRole.Narration } })
            assertTrue(sentences[1].imageBefore)
            val sameParagraph = TtsText.chapter(0, "<p>${open}おはよう。今日は晴れだ。${close}彼は笑った。</p>")
            assertEquals(listOf(TtsTextRole.Dialogue, TtsTextRole.Dialogue, TtsTextRole.Narration),
                sameParagraph.map { it.roles.single().role })
            assertTrue(TtsDialogue.ranges("${open}第一段\n第二段${close}").all { it.role == TtsTextRole.Narration })
        }
    }

    @Test fun nestedMatchedQuotesAndUnmatchedMarkersAreDeterministic() {
        assertEquals(listOf(TtsTextRole.Dialogue to "「彼は『はい』と言った」"), parts(TtsSentence(0, 0, 0, "「彼は『はい』と言った」")))
        assertEquals(listOf(TtsTextRole.Narration to "「閉じない文。"), parts(TtsSentence(0, 0, 0, "「閉じない文。")))
        assertEquals(listOf(TtsTextRole.Narration to "『引用』以外。"),
            parts(TtsSentence(0, 0, 0, "『引用』以外。", roles = listOf(TtsTextRange(0, 8, TtsTextRole.Narration)))))
    }

    @Test fun replacementAndRubyKeepTheOriginalRoleIncludingRemovedQuoteMarkers() {
        val source = TtsText.chapter(0, "<p>彼は「<ruby>今日<rt>きょう</rt></ruby>😀――いい」と言った。</p>")
        val sentence = TtsText.selection(source, "彼は「今日😀――いい」と言った。", 0, 0)
        val words = listOf(TtsWord("quote", "「", " "), TtsWord("dash", "――", "、"))
        assertEquals(listOf(TtsTextRole.Narration to "彼は", TtsTextRole.Dialogue to " きょう😀、いい」", TtsTextRole.Narration to "と言った。"), parts(sentence, words))
        assertEquals("彼は 今日😀、いい」と言った。", TtsText.spoken(sentence, "book", words, false))
        val cross = listOf(TtsWord("cross", "は「今日", "は本日"))
        assertEquals(listOf(TtsTextRole.Narration to "彼は本日", TtsTextRole.Dialogue to "😀――いい」", TtsTextRole.Narration to "と言った。"), parts(sentence, cross))
    }

    @Test fun lookupInTheMiddleOfMultisentenceDialogueInheritsChapterClassification() {
        val source = TtsText.chapter(0, "<p>「最初。次の<ruby>言葉<rt>ことば</rt></ruby>。最後。」</p>")
        val middle = source[1]
        val selected = TtsText.selection(source, middle.text, middle.start, 0)
        assertEquals(listOf(TtsTextRole.Dialogue to "次のことば。"), parts(selected))
        assertEquals(middle.start, selected.start)
    }

    @Test fun selectionKeepsClosingQuotesWithDialogueAndFollowingNarrationSeparate() {
        val source = TtsText.chapter(0, "<p>彼は「はい」と言った。</p>")
        val selected = TtsText.selection(source, "彼は「はい」と言った。", 0, 0)
        assertEquals(listOf(TtsTextRole.Narration to "彼は", TtsTextRole.Dialogue to "「はい」",
            TtsTextRole.Narration to "と言った。"), parts(selected))
    }

    @Test fun imageGlyphAtTheEndOfAnUnclosedParagraphKeepsNarration() {
        val sentence = TtsText.chapter(0, "<p>「話<ruby><img src='g'/><rt>かな</rt></ruby></p><p>続き。」</p>").first()
        assertEquals(listOf(TtsTextRole.Narration to "「話かな"), parts(sentence))
    }

    @Test fun symbolicDialogueKeepsItsSourceRoleInLookupIncludingRepeatedOccurrences() {
        val source = TtsText.chapter(0, "<p>彼は😀と言った。彼は「😀」と言った。</p>")
        val selected = TtsText.selection(source, "彼は「😀」と言った。", 6, 0)
        assertEquals(listOf(TtsTextRole.Narration to "彼は", TtsTextRole.Dialogue to "「😀」",
            TtsTextRole.Narration to "と言った。"), parts(selected))
    }

    @Test fun incompleteInnerMarkerDoesNotPreventAnOuterMatchedDialogue() {
        assertEquals(listOf(TtsTextRole.Dialogue to "「話『未完でも外は閉じる」"), parts(TtsSentence(0, 0, 0, "「話『未完でも外は閉じる」")))
    }

    @Test fun onlySharedStyleAndRoleCapabilitiesSurviveVoiceChanges() {
        val voices = listOf(TtsVoiceInfo("n", true, listOf("cheerful", "sad"), listOf("Girl")),
            TtsVoiceInfo("d", true, listOf("sad"), emptyList()))
        val settings = TtsSettings(engine = TtsOnlineSource.Azure.id).withRouting(TtsVoiceRouting(true, "n", "d"))
            .withOptions(TtsSynthesisOptions(style = "cheerful", role = "Girl"))
        assertEquals(listOf("sad"), settings.sharedVoice(voices)?.styles)
        assertTrue(settings.sharedVoice(voices)?.roles.isNullOrEmpty())
        assertEquals("", settings.withCompatibleVoiceOptions(voices).options().style)
        assertEquals("", settings.withCompatibleVoiceOptions(voices).options().role)
    }

    @Test fun automaticGptSovitsVoiceSuppressesSharedEngineRateControls() {
        val source = TtsCustomSource("custom:vits", protocol = TtsCustomProtocol.Vits)
        val voices = listOf(TtsVoiceInfo("GPT-SOVITS:0", true), TtsVoiceInfo("VITS:0", true))
        val settings = TtsSettings(engine = source.id, customSources = listOf(source)).withRouting(TtsVoiceRouting(true, "VITS:0", ""))
        assertFalse(settings.supportsSharedRate(voices))
    }
}
