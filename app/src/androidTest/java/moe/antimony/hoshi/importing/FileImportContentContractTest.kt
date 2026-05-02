package moe.antimony.hoshi.importing

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileImportContentContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun singleFileImportUsesGetContentSoOtherAppsCanProvideFiles() {
        val mimeTypes = ImportFileType.Epub.mimeTypes

        val intent = FileImportContent().createIntent(context, mimeTypes)

        assertEquals(Intent.ACTION_GET_CONTENT, intent.action)
        assertEquals("*/*", intent.type)
        assertTrue(intent.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
        assertArrayEquals(mimeTypes, intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertFalse(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertTrue(intent.flags.toInt() and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun multipleFileImportKeepsGetContentAndEnablesMultipleSelection() {
        val mimeTypes = ImportFileType.DictionaryArchive.mimeTypes

        val intent = MultipleFileImportContent().createIntent(context, mimeTypes)

        assertEquals(Intent.ACTION_GET_CONTENT, intent.action)
        assertEquals("*/*", intent.type)
        assertTrue(intent.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
        assertArrayEquals(mimeTypes, intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertTrue(intent.flags.toInt() and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun singleMimeImportUsesTheRequestedMimeTypeDirectly() {
        val intent = FileImportContent().createIntent(context, arrayOf("application/vnd.sqlite3"))

        assertEquals(Intent.ACTION_GET_CONTENT, intent.action)
        assertEquals("application/vnd.sqlite3", intent.type)
        assertNotNull(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
    }

    @Test
    fun openDocumentContentUsesPersistableDocumentAccess() {
        val mimeTypes = ImportFileType.SasayakiAudiobook.mimeTypes

        val intent = OpenDocumentContent().createIntent(context, mimeTypes)

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertTrue(intent.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
        assertArrayEquals(mimeTypes, intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertTrue(intent.flags.toInt() and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags.toInt() and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
    }

    @Test
    fun multipleFileImportDeduplicatesDataAndClipDataUris() {
        val uri = Uri.parse("content://example/dictionary.zip")
        val result = Intent().apply {
            data = uri
            clipData = ClipData.newUri(context.contentResolver, "dictionary.zip", uri)
        }

        val uris = MultipleFileImportContent().parseResult(Activity.RESULT_OK, result)

        assertEquals(listOf(uri), uris)
    }

    @Test
    fun validationRejectsWrongExtensionBeforeOpeningFile() {
        val uri = Uri.fromFile(File(context.cacheDir, "not-a-subtitle.m4b"))

        val result = runCatching {
            context.contentResolver.validateImportFile(uri, ImportFileType.SasayakiSubtitle)
        }

        assertTrue(result.exceptionOrNull() is UnsupportedImportFileTypeException)
        assertEquals("Select an .srt subtitle file.", result.exceptionOrNull()?.message)
    }

    @Test
    fun validationAcceptsExpectedExtensionCaseInsensitively() {
        val uri = Uri.fromFile(File(context.cacheDir, "MATCH.SRT"))

        val displayName = context.contentResolver.validateImportFile(uri, ImportFileType.SasayakiSubtitle)

        assertEquals("MATCH.SRT", displayName)
    }
}
