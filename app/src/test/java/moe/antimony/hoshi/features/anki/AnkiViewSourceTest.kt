package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AnkiViewSourceTest {
    @Test
    fun fieldMappingRowsKeepScrollingLightweight() {
        val source = File("src/main/java/moe/antimony/hoshi/features/anki/AnkiView.kt").readText()
        val fieldRow = source.substringAfter("private fun AnkiFieldMappingRow(")
            .substringBefore("@Composable\nprivate fun AnkiTextValueRow(")

        assertTrue(source.contains("contentType = { \"anki-field-mapping\" }"))
        assertTrue(source.contains("private fun AnkiTextValueRow("))
        assertTrue(source.contains(".clickable { editing = true }"))
        assertTrue(source.contains("private fun AnkiTextValueDialog("))
        assertFalse(fieldRow.contains("ListItem("))
        assertFalse(fieldRow.contains("OutlinedTextField("))
    }

    @Test
    fun tagsUseFieldStyleTextEditingWithoutHandlebarMenu() {
        val source = File("src/main/java/moe/antimony/hoshi/features/anki/AnkiView.kt").readText()
        val selectedNoteTypeSection = source.substringAfter("val selectedNoteType = uiState.selectedNoteType")
            .substringBefore("@Composable\nprivate fun AnkiDeckRow(")
        val tagsCall = selectedNoteTypeSection.substringAfter("AnkiTextValueRow(")
            .substringAfter("label = \"Tags\"")
            .substringBefore(")")

        assertTrue(selectedNoteTypeSection.contains("AnkiTextValueRow("))
        assertTrue(tagsCall.contains("value = uiState.settings.tags"))
        assertTrue(tagsCall.contains("onValueChange = viewModel::updateTags"))
        assertFalse(tagsCall.contains("handlebarOptions"))
    }
}
