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
    fun singleGlossaryHandlebarMatchesImportedDictionaryTitleWhenPayloadUsesBaseDictionaryName() {
        val rendered = AnkiHandlebarRenderer.render(
            template = "{single-glossary-JMdict [2026-04-27]}",
            payload = AnkiMiningPayload(
                expression = "読む",
                glossaryFirst = "first glossary",
                singleGlossaries = mapOf("JMdict" to "jmdict glossary"),
            ),
            context = AnkiMiningContext(sentence = "本を読む。"),
        )

        assertEquals("jmdict glossary", rendered)
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
    fun sentenceBoldUsesSelectedOccurrenceOnly() {
        val rendered = AnkiHandlebarRenderer.render(
            template = "{sentence}",
            payload = AnkiMiningPayload(expression = "僕", matched = "僕"),
            context = AnkiMiningContext(
                sentence = "僕は僕の本を読んだ。",
                sentenceOffset = 2,
            ),
        )

        assertEquals("僕は<b>僕</b>の本を読んだ。", rendered)
    }

    @Test
    fun sentenceBoldFallsBackToFirstOccurrenceOnlyWithoutSelectionOffset() {
        val rendered = AnkiHandlebarRenderer.render(
            template = "{sentence}",
            payload = AnkiMiningPayload(expression = "僕", matched = "僕"),
            context = AnkiMiningContext(sentence = "僕は僕の本を読んだ。"),
        )

        assertEquals("<b>僕</b>は僕の本を読んだ。", rendered)
    }

    @Test
    fun selectedGlossaryReturnsEmptyWhenNoDictionaryIsSelected() {
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

        assertEquals("", rendered)
    }

    @Test
    fun rendersBriefNoDictionaryAndFallbackGlossaryHandlebars() {
        val glossary =
            """<div class="yomitan-glossary"><ol><li data-dictionary="JMdict"><i>(1, n, JMdict)</i> <span>eat</span></li><li data-dictionary="JMdict"><i>(2)</i> <span>consume</span></li></ol></div>"""
        val firstGlossary =
            """<div class="yomitan-glossary"><ol><li data-dictionary="JMdict"><i>(JMdict)</i> <span>eat</span></li></ol></div>"""
        val payload = AnkiMiningPayload(
            expression = "食べる",
            glossary = glossary,
            glossaryFirst = firstGlossary,
            singleGlossaries = mapOf(
                "JMdict" to glossary,
                "明鏡国語辞典" to """<li data-dictionary="明鏡国語辞典"><i>(名詞, 明鏡国語辞典)</i> <span>辞書</span></li>""",
            ),
            selectedDictionary = "Missing",
        )

        assertEquals(
            """<div class="yomitan-glossary"><ol><li data-dictionary="JMdict"><span>eat</span></li><li data-dictionary="JMdict"><span>consume</span></li></ol></div>""",
            AnkiHandlebarRenderer.render("{glossary-brief}", payload, AnkiMiningContext(sentence = "")),
        )
        assertEquals(
            """<div class="yomitan-glossary"><ol><li data-dictionary="JMdict"><i>(1, n)</i> <span>eat</span></li><li data-dictionary="JMdict"><i>(2)</i> <span>consume</span></li></ol></div>""",
            AnkiHandlebarRenderer.render("{glossary-no-dictionary}", payload, AnkiMiningContext(sentence = "")),
        )
        assertEquals(
            """<div class="yomitan-glossary"><ol><li data-dictionary="JMdict"><span>eat</span></li></ol></div>""",
            AnkiHandlebarRenderer.render("{glossary-first-brief}", payload, AnkiMiningContext(sentence = "")),
        )
        assertEquals(
            """<div class="yomitan-glossary"><ol><li data-dictionary="JMdict"><span>eat</span></li></ol></div>""",
            AnkiHandlebarRenderer.render("{glossary-first-no-dictionary}", payload, AnkiMiningContext(sentence = "")),
        )
        assertEquals(
            firstGlossary,
            AnkiHandlebarRenderer.render("{selected-glossary-fallback}", payload, AnkiMiningContext(sentence = "")),
        )
        assertEquals(
            """<div class="yomitan-glossary"><ol><li data-dictionary="JMdict"><span>eat</span></li></ol></div>""",
            AnkiHandlebarRenderer.render("{selected-glossary-brief-fallback}", payload, AnkiMiningContext(sentence = "")),
        )
    }

    @Test
    fun rendersSelectedAndSingleGlossarySuffixVariantsWithDictionaryNameNormalization() {
        val payload = AnkiMiningPayload(
            expression = "読む",
            glossaryFirst = """<li data-dictionary="JMdict"><i>(JMdict)</i> <span>read</span></li>""",
            singleGlossaries = mapOf(
                "JMdict" to """<li data-dictionary="JMdict"><i>(JMdict)</i> <span>read</span></li>""",
                "明鏡国語辞典" to """<li data-dictionary="明鏡国語辞典"><i>(名詞, 明鏡国語辞典)</i> <span>よむ</span></li>""",
            ),
            selectedDictionary = "JMdict [2026-04-27]",
        )

        val rendered = AnkiHandlebarRenderer.render(
            template = "{selected-glossary-brief}|{selected-glossary-no-dictionary}|" +
                "{single-glossary-JMdict [2026-04-27]-brief}|{single-glossary-明鏡国語辞典-no-dictionary}",
            payload = payload,
            context = AnkiMiningContext(sentence = "本を読む。"),
        )

        assertEquals(
            """<li data-dictionary="JMdict"><span>read</span></li>|""" +
                """<li data-dictionary="JMdict"><span>read</span></li>|""" +
                """<li data-dictionary="JMdict"><span>read</span></li>|""" +
                """<li data-dictionary="明鏡国語辞典"><i>(名詞)</i> <span>よむ</span></li>""",
            rendered,
        )
    }
}
