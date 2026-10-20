package moe.antimony.hoshi.features.tts

import org.junit.Assert.*
import org.junit.Test

class TtsDialogueTest {
    private fun parts(sentence: TtsSentence, words: List<TtsWord> = emptyList(), ruby: Boolean = true) =
        TtsText.spokenParts(sentence, "book", words, ruby).map { it.role to it.text }

    @Test fun mixedSentenceKeepsOffsetsAndUsesDialogueOnlyInsideQuotes() {
        val sentence = TtsText.chapter(2, "<p>彼女は「こんにちは」と言った。</p>").single()
        assertEquals(listOf(TtsTextRole.Narration to "彼女は", TtsTextRole.Dialogue to "「こんにちは」", TtsTextRole.Narration to "と言った。"), parts(sentence))
        assertEquals(2, sentence.chapter)
        assertEquals(0, sentence.start)
        assertEquals(12, sentence.length)
    }

    @Test fun quotesContinueAcrossSentencesParagraphsAndImagesButNotChapters() {
        val sentences = TtsText.chapter(0, "<p>「おはよう。</p><img src='image'/><p>今日は晴れだ。」彼は笑った。</p>")
        assertEquals(listOf(TtsTextRole.Dialogue to "「おはよう。"), parts(sentences[0]))
        assertEquals(listOf(TtsTextRole.Dialogue to "今日は晴れだ。」"), parts(sentences[1]))
        assertTrue(sentences[1].imageBefore)
        assertEquals(listOf(TtsTextRole.Narration to "彼は笑った。"), parts(sentences[2]))
        assertEquals(listOf(TtsTextRole.Narration to "続きだ。」"), parts(TtsText.chapter(1, "<p>続きだ。」</p>").single()))
    }

    @Test fun nestedMatchedQuotesAndUnmatchedMarkersAreDeterministic() {
        assertEquals(listOf(TtsTextRole.Dialogue to "「彼は『はい』と言った」"), parts(TtsSentence(0, 0, 0, "「彼は『はい』と言った」")))
        assertEquals(listOf(TtsTextRole.Narration to "「閉じない文。"), parts(TtsSentence(0, 0, 0, "「閉じない文。")))
        assertEquals(listOf(TtsTextRole.Narration to "『引用』以外。"),
            parts(TtsSentence(0, 0, 0, "『引用』以外。", roles = listOf(TtsTextRange(0, 8, TtsTextRole.Narration)))))
    }

    @Test fun replacementAndRubyKeepTheOriginalRoleIncludingRemovedQuoteMarkers() {
        val sentence = TtsText.chapter(0, "<p>彼は「<ruby>今日<rt>きょう</rt></ruby>😀――いい」と言った。</p>").single()
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
        val selected = TtsText.selection(source, source.single().text, 0, 0)
        assertEquals(parts(source.single()), parts(selected))
    }

    @Test fun imageGlyphAtTheEndOfAParagraphKeepsTheSpanningDialogueVoice() {
        val sentence = TtsText.chapter(0, "<p>「話<ruby><img src='g'/><rt>かな</rt></ruby></p><p>続き。」</p>").first()
        assertEquals(listOf(TtsTextRole.Dialogue to "「話かな"), parts(sentence))
    }

    @Test fun symbolicDialogueKeepsItsSourceRoleInLookupIncludingRepeatedOccurrences() {
        val source = TtsText.chapter(0, "<p>彼は😀と言った。彼は「😀」と言った。</p>")
        val quoted = source[1]
        assertEquals(parts(quoted), parts(TtsText.selection(source, quoted.text, quoted.start, 0)))
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
