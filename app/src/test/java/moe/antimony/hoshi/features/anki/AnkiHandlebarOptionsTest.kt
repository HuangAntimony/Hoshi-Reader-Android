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
        assertEquals(
            listOf("{single-glossary-JMdict}", "{single-glossary-明鏡国語辞典 第三版}"),
            options.takeLast(2),
        )
    }
}
