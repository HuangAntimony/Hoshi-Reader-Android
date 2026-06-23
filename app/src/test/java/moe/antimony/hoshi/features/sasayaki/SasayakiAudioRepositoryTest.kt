package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class SasayakiAudioRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun privateCopiedAudioSourceResolvesOnlyExistingFilesUnderBookAudioDirectory() {
        val bookRoot = temporaryFolder.newFolder("book")
        val repository = SasayakiAudioRepository(bookRoot)
        val audioFile = bookRoot.resolve("Sasayaki/sasayaki_audio.m4b")
        audioFile.parentFile!!.mkdirs()
        audioFile.writeText("audio")

        val source = repository.playbackSource(playback(audioFileName = "sasayaki_audio.m4b"))

        assertEquals(SasayakiPlaybackSource.PrivateFile(audioFile.canonicalFile), source)
        assertNull(repository.playbackSource(playback(audioFileName = "../outside.m4b")))
        assertNull(repository.playbackSource(playback(audioFileName = "missing.m4b")))
    }

    @Test
    fun deleteAudioRemovesOnlyResolvedPrivateAudioFile() {
        val bookRoot = temporaryFolder.newFolder("delete-book")
        val repository = SasayakiAudioRepository(bookRoot)
        val audioFile = bookRoot.resolve("Sasayaki/sasayaki_audio.mp3")
        audioFile.parentFile!!.mkdirs()
        audioFile.writeText("audio")

        assertTrue(repository.deleteAudio(playback(audioFileName = "sasayaki_audio.mp3")))

        assertFalse(audioFile.exists())
        assertFalse(repository.deleteAudio(playback(audioFileName = "../outside.mp3")))
    }

    @Test
    fun storageSummaryDescribesPrivateCopyExternalLinkAndMissingAudio() {
        val repository = SasayakiAudioRepository(temporaryFolder.newFolder("summary-book"))

        assertEquals(
            "Copied to app storage. The original audiobook file can be deleted.",
            repository.storageSummary(playback(audioFileName = "sasayaki_audio.m4b")),
        )
        assertEquals(
            "Linked to the external audiobook file. Keep the original file available.",
            repository.storageSummary(playback(audioUri = "content://audio/book.m4b")),
        )
        assertEquals(
            "Select an .mp3 or .m4b audiobook",
            repository.storageSummary(playback()),
        )
    }

    @Test
    fun audiobookChaptersReadsPrivateCopiedAudio() {
        val bookRoot = temporaryFolder.newFolder("chapters-book")
        val repository = SasayakiAudioRepository(bookRoot)
        val audioFile = bookRoot.resolve("Sasayaki/sasayaki_audio.m4b")
        audioFile.parentFile!!.mkdirs()
        audioFile.writeBytes(
            minimalMp4WithChpl(
                durationSeconds = 20.0,
                chapters = listOf(
                    SasayakiChapterFixture(startSeconds = 0.0, title = "Opening"),
                    SasayakiChapterFixture(startSeconds = 10.0, title = "Ending"),
                ),
            ),
        )

        val chapters = repository.audiobookChapters(playback(audioFileName = "sasayaki_audio.m4b"))

        assertEquals(listOf("Opening", "Ending"), chapters.map { it.title })
    }

    @Test
    fun audiobookChaptersReadsExternalUriThroughChannelProvider() {
        val repository = SasayakiAudioRepository(temporaryFolder.newFolder("external-chapters-book"))
        val externalFile = temporaryFolder.newFile("external.m4b").also { file ->
            file.writeBytes(
                minimalMp4WithChpl(
                    durationSeconds = 20.0,
                    chapters = listOf(
                        SasayakiChapterFixture(startSeconds = 0.0, title = "External Opening"),
                    ),
                ),
            )
        }

        var openedUri: String? = null
        val chapters = repository.audiobookChapters(
            playback(audioUri = "content://audio/external.m4b"),
            openExternalAudio = { uriString ->
                openedUri = uriString
                Files.newByteChannel(externalFile.toPath())
            },
        )

        assertEquals("content://audio/external.m4b", openedUri)
        assertEquals(listOf("External Opening"), chapters.map { it.title })
    }

    @Test
    fun audiobookMetadataUsesResolvedSourceAndNormalizesMetadata() {
        val bookRoot = temporaryFolder.newFolder("metadata-book")
        val repository = SasayakiAudioRepository(bookRoot)
        val audioFile = bookRoot.resolve("Sasayaki/sasayaki_audio.m4b")
        audioFile.parentFile!!.mkdirs()
        audioFile.writeText("audio")
        val artwork = byteArrayOf(1, 2, 3)
        var sourceRead: SasayakiPlaybackSource? = null

        val metadata = repository.audiobookMetadata(playback(audioFileName = "sasayaki_audio.m4b")) { source ->
            sourceRead = source
            SasayakiAudiobookMetadata(
                title = "  Recorded Book  ",
                artist = "",
                albumArtist = " Narrator ",
                author = "Author",
                artworkData = artwork,
            )
        }

        assertEquals(SasayakiPlaybackSource.PrivateFile(audioFile.canonicalFile), sourceRead)
        assertEquals("Recorded Book", metadata.title)
        assertEquals("Narrator", metadata.artist)
        assertArrayEquals(artwork, metadata.artworkData)
    }

    @Test
    fun audiobookMetadataReturnsEmptyWhenAudioMissingOrReaderFails() {
        val repository = SasayakiAudioRepository(temporaryFolder.newFolder("missing-metadata-book"))

        assertEquals(
            SasayakiAudiobookMetadata.Empty,
            repository.audiobookMetadata(playback()) {
                error("Should not read metadata without an audio source.")
            },
        )
        assertEquals(
            SasayakiAudiobookMetadata.Empty,
            repository.audiobookMetadata(playback(audioUri = "content://audio/book.m4b")) {
                error("Metadata read failed.")
            },
        )
    }

    private fun playback(
        audioUri: String? = null,
        audioFileName: String? = null,
    ): SasayakiPlaybackData =
        SasayakiPlaybackData(
            lastPosition = 0.0,
            audioUri = audioUri,
            audioFileName = audioFileName,
        )
}
