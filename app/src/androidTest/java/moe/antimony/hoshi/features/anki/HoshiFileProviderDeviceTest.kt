package moe.antimony.hoshi.features.anki

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Unique scratch files only; no settings, library files or Anki collection are touched. */
@RunWith(AndroidJUnit4::class)
class HoshiFileProviderDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val authority = "${context.packageName}.fileprovider"

    @Test
    fun ttsSentenceExportsCanBeReadThroughTheConfiguredProvider() {
        val directory = File(context.cacheDir, "tts/export").apply { mkdirs() }
        val bytes = byteArrayOf(1, 2, 3, 4)
        // Online TTS, device TTS and mixed-format dialogue exports respectively.
        for ((extension, mimeType) in listOf("mp3" to "audio/mpeg", "wav" to "audio/x-wav", "m4a" to "audio/mp4")) {
            val file = File.createTempFile("sentence-audio-fixture-", ".$extension", directory)
            try {
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(context, authority, file)

                assertEquals("content", uri.scheme)
                assertEquals(authority, uri.authority)
                assertEquals(mimeType, context.contentResolver.getType(uri))
                val received = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                assertArrayEquals(bytes, received)
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun ttsPlaybackCacheRemainsOutsideTheSharingRoots() {
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, authority, File(context.cacheDir, "tts/playback/private.mp3"))
        }
    }
}
