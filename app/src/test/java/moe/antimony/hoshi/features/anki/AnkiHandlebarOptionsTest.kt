package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiHandlebarOptionsTest {
    @Test
    fun includesTermDictionarySpecificGlossaryHandlebarsAfterCoreOptions() {
        val options = AnkiHandlebarOptions.forTermDictionaries(
            listOf("JMdict", "明鏡国語辞典 第三版"),
        )

        assertTrue(options.contains("{expression}"))
        assertTrue(options.contains("{glossary-brief}"))
        assertTrue(options.contains("{selected-glossary-fallback}"))
        assertEquals(
            listOf("{single-glossary-JMdict}", "{single-glossary-明鏡国語辞典 第三版}"),
            options.takeLast(2),
        )
    }

    @Test
    fun hidesAdvancedGlossaryVariantsFromPickerWhileRendererSupportsManualEntry() {
        val options = AnkiHandlebarOptions.forTermDictionaries(listOf("JMdict"))

        assertTrue("{glossary-no-dictionary}" !in options)
        assertTrue("{glossary-first-brief}" !in options)
        assertTrue("{glossary-first-no-dictionary}" !in options)
        assertTrue("{selected-glossary-brief}" !in options)
        assertTrue("{selected-glossary-brief-fallback}" !in options)
        assertTrue("{selected-glossary-no-dictionary}" !in options)
        assertTrue("{selected-glossary-no-dictionary-fallback}" !in options)
        assertTrue("{single-glossary-JMdict-brief}" !in options)
        assertTrue("{single-glossary-JMdict-no-dictionary}" !in options)
    }
}
