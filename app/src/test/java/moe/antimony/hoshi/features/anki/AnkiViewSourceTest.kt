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
            .substringBefore("@Composable\nprivate fun AnkiFieldMappingDialog(")

        assertTrue(source.contains("contentType = { \"anki-field-mapping\" }"))
        assertTrue(fieldRow.contains("Row("))
        assertTrue(fieldRow.contains(".clickable { editing = true }"))
        assertTrue(source.contains("private fun AnkiFieldMappingDialog("))
        assertFalse(fieldRow.contains("ListItem("))
        assertFalse(fieldRow.contains("OutlinedTextField("))
    }
}
