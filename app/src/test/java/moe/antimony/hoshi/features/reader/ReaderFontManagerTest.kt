package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows
import java.io.File
import kotlin.io.path.createTempDirectory

class ReaderFontManagerTest {
    @Test
    fun importFontStoresFileAndUsesBasenameAsFontNameLikeIos() {
        val root = createTempDirectory().toFile()
        val source = File(root, "KleeOne-SemiBold.ttf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val manager = ReaderFontManager(root)

        val imported = manager.importFont(source)

        assertEquals("KleeOne-SemiBold", imported.name)
        assertEquals("KleeOne-SemiBold.ttf", imported.fileName)
        assertEquals(listOf("KleeOne-SemiBold"), manager.storedFonts().map { it.name })
        assertEquals(source.readBytes().toList(), imported.file.readBytes().toList())
    }

    @Test
    fun deleteFontRemovesImportedFontAndLeavesDefaultsUntouched() {
        val root = createTempDirectory().toFile()
        val source = File(root, "KleeOne-SemiBold.ttf").apply { writeBytes(byteArrayOf(1)) }
        val manager = ReaderFontManager(root)

        manager.importFont(source)
        manager.deleteFont("KleeOne-SemiBold")

        assertTrue(manager.storedFonts().isEmpty())
        assertTrue(manager.isDefaultFont("Hiragino Mincho ProN"))
        assertFalse(manager.isDefaultFont("KleeOne-SemiBold"))
    }

    @Test
    fun importFontRejectsNonFontExtensions() {
        val root = createTempDirectory().toFile()
        val source = File(root, "not-a-font.zip").apply { writeBytes(byteArrayOf(1)) }
        val manager = ReaderFontManager(root)

        assertThrows(IllegalArgumentException::class.java) {
            manager.importFont(source)
        }
    }
}
