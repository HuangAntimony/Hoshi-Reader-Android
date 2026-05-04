package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiHandlebarRendererTest {
    @Test
    fun rendersAllIosCoreHandlebars() {
        val payload = AnkiMiningPayload(
            expression = "食べる",
            reading = "たべる",
            matched = "食べる",
            furiganaPlain = "食[た]べる",
            frequenciesHtml = "<span>1139</span>",
            freqHarmonicRank = "1139",
            glossary = "<ol><li>eat</li></ol>",
            glossaryFirst = "<li>eat</li>",
            singleGlossaries = mapOf("JMdict" to "<li>eat</li>"),
            pitchPositions = "<span>2</span>",
            pitchCategories = "heiban",
            popupSelectionText = "食べる",
            audio = "https://audio.example/taberu.mp3",
            selectedDictionary = "JMdict",
            dictionaryMedia = emptyList(),
        )
        val context = AnkiMiningContext(
            sentence = "パンを食べる。",
            documentTitle = "テスト本",
            coverPath = "cover.jpg",
            sasayakiAudioPath = "cue.m4a",
        )

        val rendered = AnkiHandlebarRenderer.render(
            template = "{expression}|{reading}|{furigana-plain}|{audio}|{glossary}|{glossary-first}|" +
                "{selected-glossary}|{popup-selection-text}|{sentence}|{frequencies}|{frequency-harmonic-rank}|" +
                "{pitch-accent-positions}|{pitch-accent-categories}|{document-title}|{book-cover}|{sasayaki-audio}",
            payload = payload,
            context = context,
        )

        assertEquals(
            "食べる|たべる|食[た]べる|https://audio.example/taberu.mp3|<ol><li>eat</li></ol>|<li>eat</li>|" +
                "<li>eat</li>|食べる|パンを<b>食べる</b>。|<span>1139</span>|1139|" +
                "<span>2</span>|heiban|テスト本|cover.jpg|cue.m4a",
            rendered,
        )
    }

    @Test
    fun rendersSingleGlossaryHandlebarsAndUnknownValuesAsEmptyStrings() {
        val rendered = AnkiHandlebarRenderer.render(
            template = "{single-glossary-JMdict}|{single-glossary-Unknown}|{unknown}",
            payload = AnkiMiningPayload(
                expression = "読む",
                singleGlossaries = mapOf("JMdict" to "read"),
            ),
            context = AnkiMiningContext(sentence = "本を読む。"),
        )

        assertEquals("read||", rendered)
    }

    @Test
    fun sentenceFallsBackToRawContextWhenMatchedPayloadIsBlank() {
        val rendered = AnkiHandlebarRenderer.render(
            template = "{sentence}",
            payload = AnkiMiningPayload(expression = "読む", matched = ""),
            context = AnkiMiningContext(sentence = "本を読む。"),
        )

        assertEquals("本を読む。", rendered)
    }

    @Test
    fun selectedGlossaryFallsBackToFirstGlossaryWhenNoDictionaryIsSelected() {
        val rendered = AnkiHandlebarRenderer.render(
            template = "{selected-glossary}",
            payload = AnkiMiningPayload(
                expression = "読む",
                glossaryFirst = "read",
                singleGlossaries = mapOf("JMdict" to "read"),
                selectedDictionary = "",
            ),
            context = AnkiMiningContext(sentence = "本を読む。"),
        )

        assertEquals("read", rendered)
    }
}
