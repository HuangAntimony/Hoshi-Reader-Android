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
        assertTrue(manager.isDefaultFont("Noto Serif CJK JP"))
        assertFalse(manager.isDefaultFont("KleeOne-SemiBold"))
    }

    @Test
    fun defaultFontsAreAndroidJapaneseMinchoAndGothicPresets() {
        assertEquals(listOf("Noto Serif CJK JP", "Noto Sans CJK JP"), ReaderFontManager.defaultFonts)
    }

    @Test
    fun popupFontFaceCssExposesImportedFontsThroughLocalWebViewBridge() {
        val root = createTempDirectory().toFile()
        val manager = ReaderFontManager(root)
        manager.importFont(File(root, "Klee One.ttf").apply { writeBytes(byteArrayOf(1)) })

        val css = manager.popupFontFaceCss()

        assertTrue(css.contains("@font-face"))
        assertTrue(css.contains("""font-family: "Klee One";"""))
        assertTrue(css.contains("""src: url("https://appassets.androidplatform.net/fonts/Klee%20One.ttf");"""))
        assertTrue(css.contains("font-display: swap;"))
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
