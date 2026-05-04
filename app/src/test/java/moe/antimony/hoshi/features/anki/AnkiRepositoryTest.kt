package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiRepositoryTest {
    @Test
    fun dictionaryMediaUsesFilenameInsideExistingGlossaryHtml() {
        assertEquals(
            "hoshi_dict_123.svg",
            ankiInlineMediaReference("""<img src="hoshi_dict_123.svg" />"""),
        )
    }

    @Test
    fun directAudioMediaKeepsSoundFilenameForInlineReplacementFallback() {
        assertEquals(
            "hoshi_sasayaki_123.m4a",
            ankiInlineMediaReference("[sound:hoshi_sasayaki_123.m4a]"),
        )
    }

    @Test
    fun exportedGaijiDictionaryImagesStayInlineInAnkiFields() {
        val html = normalizeAnkiDictionaryHtml(
            """<span data-sc-img data-sc-class="gaiji"><span class="gloss-image-container"><img class="gloss-image" src="hoshi_dict_123.svg"></span></span>""",
        )

        assertTrue(html.contains("""[data-sc-img][data-sc-class="gaiji"] .gloss-image-container"""))
        assertTrue(html.contains("width:1em!important"))
        assertTrue(html.contains("position:static!important"))
    }
}
