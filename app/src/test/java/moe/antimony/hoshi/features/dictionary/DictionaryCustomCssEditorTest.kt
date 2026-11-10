package moe.antimony.hoshi.features.dictionary

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryCustomCssEditorTest {
    @Test
    fun selectorSnippetMatchesIosDictionaryCssSelector() {
        assertEquals(
            """
                [data-dictionary="JMdict"] {
                    
                }
                
            """.trimIndent(),
            dictionarySelectorCssSnippet("JMdict"),
        )
    }

    @Test
    fun cssInsertionReplacesCurrentSelectionAndMovesCursorAfterInsertedText() {
        val state = TextFieldState(
            initialText = "body { color: red; }",
            initialSelection = TextRange(7, 18),
        )

        state.insertCustomCssText("font-family: \"Klee\" !important;")

        assertEquals("body { font-family: \"Klee\" !important; }", state.text.toString())
        assertEquals(TextRange(38), state.selection)
    }
}
