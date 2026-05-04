package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
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
}
