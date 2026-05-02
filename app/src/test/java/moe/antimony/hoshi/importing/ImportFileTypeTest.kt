package moe.antimony.hoshi.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportFileTypeTest {
    @Test
    fun fileTypesExposePickerMimeTypesWithoutAcceptingEverything() {
        assertEquals(listOf("epub"), ImportFileType.Epub.extensions)
        assertEquals(listOf("srt"), ImportFileType.SasayakiSubtitle.extensions)
        assertEquals(listOf("mp3", "m4b"), ImportFileType.SasayakiAudiobook.extensions)
        assertEquals(listOf("db"), ImportFileType.LocalAudioDatabase.extensions)
        assertEquals(listOf("zip"), ImportFileType.DictionaryArchive.extensions)
        assertEquals(listOf("ttf", "otf", "woff", "woff2"), ImportFileType.ReaderFont.extensions)

        val allPickerTypes = listOf(
            ImportFileType.Epub,
            ImportFileType.SasayakiSubtitle,
            ImportFileType.SasayakiAudiobook,
            ImportFileType.LocalAudioDatabase,
            ImportFileType.DictionaryArchive,
            ImportFileType.ReaderFont,
        ).flatMap { it.mimeTypes.toList() }
        assertFalse(allPickerTypes.contains("*/*"))
        assertFalse(ImportFileType.SasayakiAudiobook.mimeTypes.contains("audio/*"))
    }

    @Test
    fun validatesExpectedFileExtensionsCaseInsensitively() {
        assertTrue(ImportFileType.Epub.matchesDisplayName("Book.EPUB"))
        assertTrue(ImportFileType.SasayakiSubtitle.matchesDisplayName("subtitle.srt"))
        assertTrue(ImportFileType.SasayakiAudiobook.matchesDisplayName("audio.MP3"))
        assertTrue(ImportFileType.SasayakiAudiobook.matchesDisplayName("audio.m4b"))
        assertTrue(ImportFileType.LocalAudioDatabase.matchesDisplayName("android.db"))
        assertTrue(ImportFileType.DictionaryArchive.matchesDisplayName("JMdict.zip"))
    }

    @Test
    fun rejectsWrongExtensionsBeforeOpeningTheFile() {
        assertFalse(ImportFileType.SasayakiSubtitle.matchesDisplayName("audiobook.m4b"))
        assertFalse(ImportFileType.SasayakiAudiobook.matchesDisplayName("subtitle.srt"))
        assertFalse(ImportFileType.LocalAudioDatabase.matchesDisplayName("dictionary.zip"))
        assertFalse(ImportFileType.DictionaryArchive.matchesDisplayName("android.db"))
        assertFalse(ImportFileType.Epub.matchesDisplayName("book.zip"))
    }

    @Test
    fun errorMessageNamesExpectedExtensions() {
        val error = ImportFileType.SasayakiAudiobook.unsupportedFileError("subtitle.srt")

        assertEquals("Select an .mp3 or .m4b audiobook file.", error.message)
    }
}
